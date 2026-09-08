// DOM Elements
const urlInput = document.getElementById('url-input');
const btnClear = document.getElementById('btn-clear');
const btnPaste = document.getElementById('btn-paste');
const btnParse = document.getElementById('btn-parse');
const parseSpinner = document.getElementById('parse-spinner');
const parseIcon = document.getElementById('parse-icon');
const parseText = document.getElementById('parse-text');
const platformBadge = document.getElementById('platform-badge');
const platformBadgeText = document.getElementById('platform-badge-text');

const previewCard = document.getElementById('preview-card');
const previewCover = document.getElementById('preview-cover');
const previewDuration = document.getElementById('preview-duration');
const previewTitle = document.getElementById('preview-title');
const previewAuthor = document.getElementById('preview-author');
const previewPlatform = document.getElementById('preview-platform');
const qualitySelect = document.getElementById('quality-select');
const btnDownload = document.getElementById('btn-download');

const taskList = document.getElementById('task-list');
const taskCount = document.getElementById('task-count');

const btnLogin = document.getElementById('btn-login');
const userStatusText = document.getElementById('user-status-text');
const qrModal = document.getElementById('qr-modal');
const btnCloseModal = document.getElementById('btn-close-modal');
const qrImage = document.getElementById('qr-image');
const qrLoading = document.getElementById('qr-loading');
const qrStatus = document.getElementById('qr-status');
const btnRefreshQr = document.getElementById('btn-refresh-qr');
const toastEl = document.getElementById('toast');

let currentParsedData = null;
let qrPollInterval = null;
let currentQrKey = null;
let toastTimeout = null;
const tasksMap = {};

// ==========================================================================
// 1. Platform Detection & UI Feedback
// ==========================================================================
function detectPlatform(text) {
    if (!text) return null;
    const s = text.toLowerCase();
    if (s.includes('douyin.com') || s.includes('iesdouyin.com')) return 'douyin';
    if (s.includes('kuaishou.com') || s.includes('gifshow.com')) return 'kuaishou';
    if (s.includes('bilibili.com') || s.includes('b23.tv') || s.includes('bili2233.cn') || 
        /bv[a-za-z0-9]{10}/i.test(s) || /\bav\d+\b/i.test(s)) return 'bilibili';
    return null;
}

function updatePlatformBadge(text) {
    const platform = detectPlatform(text);
    if (!platform) {
        platformBadge.className = 'platform-badge hidden';
        return;
    }
    platformBadge.className = `platform-badge platform-${platform}`;
    if (platform === 'bilibili') {
        platformBadgeText.textContent = '哔哩哔哩 (B站)';
    } else if (platform === 'douyin') {
        platformBadgeText.textContent = '抖音无水印';
    } else if (platform === 'kuaishou') {
        platformBadgeText.textContent = '快手无水印';
    }
}

function handleInputChanges() {
    const val = urlInput.value.trim();
    if (val.length > 0) {
        btnClear.classList.remove('hidden');
    } else {
        btnClear.classList.add('hidden');
    }
    updatePlatformBadge(val);
}

urlInput.addEventListener('input', handleInputChanges);

btnClear.addEventListener('click', () => {
    urlInput.value = '';
    handleInputChanges();
    urlInput.focus();
});

// ==========================================================================
// 2. Paste & Share Handling
// ==========================================================================
btnPaste.addEventListener('click', () => {
    if (window.AndroidBridge && window.AndroidBridge.getClipboardText) {
        const text = window.AndroidBridge.getClipboardText();
        if (text && text.trim().length > 0) {
            urlInput.value = text.trim();
            handleInputChanges();
            showToast("已自动读取剪贴板");
        } else {
            showToast("剪贴板为空");
        }
    } else if (navigator.clipboard && navigator.clipboard.readText) {
        navigator.clipboard.readText().then(text => {
            if (text && text.trim().length > 0) {
                urlInput.value = text.trim();
                handleInputChanges();
                showToast("已自动读取剪贴板");
            } else {
                showToast("剪贴板为空");
            }
        }).catch(() => showToast("无法直接访问剪贴板"));
    } else {
        showToast("请手动在输入框中粘贴");
    }
});

window.onShareReceived = function(sharedText) {
    if (!sharedText) return;
    urlInput.value = sharedText.trim();
    handleInputChanges();
    showToast("收到系统分享，开始解析...");
    triggerParse();
};

// ==========================================================================
// 3. Parse Logic
// ==========================================================================
function setParseLoading(loading) {
    if (loading) {
        btnParse.disabled = true;
        parseSpinner.classList.remove('hidden');
        if (parseIcon) parseIcon.classList.add('hidden');
        parseText.textContent = "解析中...";
    } else {
        btnParse.disabled = false;
        parseSpinner.classList.add('hidden');
        if (parseIcon) parseIcon.classList.remove('hidden');
        parseText.textContent = "开始解析";
    }
}

function triggerParse() {
    const input = urlInput.value.trim();
    if (!input) {
        showToast("请先输入或粘贴视频链接");
        urlInput.focus();
        return;
    }
    setParseLoading(true);
    if (window.AndroidBridge && window.AndroidBridge.parseUrl) {
        window.AndroidBridge.parseUrl(input);
    } else {
        // Fallback for browser testing
        setTimeout(() => {
            setParseLoading(false);
            showToast("原生通信通道就绪 (调试预览)");
        }, 800);
    }
}

btnParse.addEventListener('click', triggerParse);

// Called by Android WebAppBridge
window.onParseResult = function(rawJson) {
    setParseLoading(false);
    try {
        const res = typeof rawJson === 'string' ? JSON.parse(rawJson) : rawJson;
        if (!res.success) {
            showToast(res.message || "未能解析出视频直链");
            return;
        }
        renderPreview(res);
        showToast("解析成功！已自动加载原画流");
    } catch (e) {
        showToast("解析异常: " + e.message);
    }
};

function formatDuration(seconds) {
    if (!seconds || seconds <= 0) return "00:00";
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

function renderPreview(data) {
    currentParsedData = data;
    previewCard.classList.remove('hidden');

    previewTitle.textContent = data.title || "未知标题";
    previewAuthor.textContent = data.author || "媒体作者";
    previewCover.src = data.pic || "";
    previewDuration.textContent = formatDuration(data.duration);

    const platform = data.platform || 'bilibili';
    previewPlatform.textContent = platform === 'bilibili' ? 'Bilibili' : (platform === 'douyin' ? '抖音' : '快手');

    // Populate quality options
    qualitySelect.innerHTML = '';

    if (platform === 'bilibili' && data.play_data && data.play_data.dash) {
        const dash = data.play_data.dash;
        const videos = dash.video || [];
        const audios = dash.audio || [];
        const bestAudio = audios.length > 0 ? (audios[0].baseUrl || audios[0].base_url) : null;

        const qnMap = {
            127: "8K 超高清",
            126: "杜比视界 (Dolby Vision)",
            125: "HDR 真彩",
            120: "4K 超清",
            116: "1080P 60帧",
            112: "1080P 高码率",
            80: "1080P 高清",
            64: "720P 高清",
            32: "480P 清晰"
        };

        const addedKeys = new Set();
        videos.forEach(v => {
            const qn = v.id;
            const codec = (v.codecs && v.codecs.startsWith('hev')) ? 'HEVC' : ((v.codecs && v.codecs.startsWith('avc')) ? 'AVC' : 'AV01');
            const key = `${qn}_${codec}`;
            if (!addedKeys.has(key)) {
                addedKeys.add(key);
                const opt = document.createElement('option');
                opt.value = JSON.stringify({
                    videoUrl: v.baseUrl || v.base_url,
                    audioUrl: bestAudio,
                    referer: "https://www.bilibili.com/"
                });
                const qnLabel = qnMap[qn] || `画质 ${qn}`;
                opt.textContent = `${qnLabel} (${codec})`;
                qualitySelect.appendChild(opt);
            }
        });

        // Add audio-only option
        if (bestAudio) {
            const optAudio = document.createElement('option');
            optAudio.value = JSON.stringify({
                videoUrl: bestAudio,
                audioUrl: null,
                referer: "https://www.bilibili.com/"
            });
            optAudio.textContent = "仅提取音频 (高音质 M4A/MP3)";
            qualitySelect.appendChild(optAudio);
        }

    } else if (data.direct_video_url) {
        // Direct MP4 (Douyin / Kuaishou)
        const opt = document.createElement('option');
        opt.value = JSON.stringify({
            videoUrl: data.direct_video_url,
            audioUrl: null,
            referer: platform === 'douyin' ? "https://www.douyin.com/" : "https://v.kuaishou.com/"
        });
        opt.textContent = "原画超清 (无水印 MP4)";
        qualitySelect.appendChild(opt);
    }

    // Scroll to preview card smoothly
    previewCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

// ==========================================================================
// 4. Download Execution
// ==========================================================================
btnDownload.addEventListener('click', () => {
    if (!currentParsedData) return;
    const selectedValue = qualitySelect.value;
    if (!selectedValue) return;

    try {
        const streamInfo = JSON.parse(selectedValue);
        const taskPayload = {
            title: currentParsedData.title || "媒体下载",
            videoUrl: streamInfo.videoUrl,
            audioUrl: streamInfo.audioUrl,
            referer: streamInfo.referer
        };

        if (window.AndroidBridge && window.AndroidBridge.startDownload) {
            window.AndroidBridge.startDownload(JSON.stringify(taskPayload));
        } else {
            showToast("原生下载服务未连接");
        }
    } catch (e) {
        showToast("创建下载异常: " + e.message);
    }
});

// ==========================================================================
// 5. Tasks Progress Handling
// ==========================================================================
window.onTaskAdded = function(id, title) {
    tasksMap[id] = { id, title, progress: 0, status: 'pending', speed: '' };
    updateTaskUI();
};

window.onTaskProgress = function(id, status, progress, speed, error) {
    if (!tasksMap[id]) {
        tasksMap[id] = { id, title: '下载任务_' + id };
    }
    tasksMap[id].status = status;
    tasksMap[id].progress = progress;
    tasksMap[id].speed = speed;
    tasksMap[id].error = error;
    updateTaskUI();
};

function updateTaskUI() {
    const list = Object.values(tasksMap);
    taskCount.textContent = list.length;
    if (list.length === 0) {
        taskList.innerHTML = `
            <div class="empty-state">
                <div class="empty-icon">
                    <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
                        <line x1="8" y1="21" x2="16" y2="21" />
                        <line x1="12" y1="17" x2="12" y2="21" />
                    </svg>
                </div>
                <p class="empty-title">队列暂无任务</p>
                <p class="empty-desc">解析视频后选择心仪画质，即可一键下载到相册</p>
            </div>
        `;
        return;
    }

    taskList.innerHTML = list.map(t => {
        let statusLabel = "等待中";
        let statusClass = "status-downloading";
        let dotHtml = '<span class="pulse-dot"></span>';

        if (t.status === 'downloading') {
            statusLabel = `下载中 ${t.progress}%`;
        } else if (t.status === 'merging') {
            statusLabel = "合并音视频...";
            statusClass = "status-merging";
        } else if (t.status === 'saving') {
            statusLabel = "存入相册...";
            statusClass = "status-merging";
        } else if (t.status === 'completed') {
            statusLabel = "已存入相册";
            statusClass = "status-completed";
            dotHtml = '✓';
        } else if (t.status === 'failed') {
            statusLabel = "下载失败";
            statusClass = "status-failed";
            dotHtml = '✕';
        }

        return `
            <div class="task-item" id="task-${t.id}">
                <div class="task-header">
                    <span class="task-title" title="${t.title || ''}">${t.title || '下载任务'}</span>
                    <span class="task-status-pill ${statusClass}">
                        ${dotHtml} ${statusLabel}
                    </span>
                </div>
                <div class="progress-track">
                    <div class="progress-fill" style="width: ${t.progress || 0}%"></div>
                </div>
                <div class="task-footer">
                    <span>${t.speed || ''}</span>
                    <span>${t.error || ''}</span>
                </div>
            </div>
        `;
    }).join('');
}

// ==========================================================================
// 6. QR Code Login Modal
// ==========================================================================
btnLogin.addEventListener('click', () => {
    qrModal.classList.remove('hidden');
    requestQrCode();
});

btnCloseModal.addEventListener('click', () => {
    qrModal.classList.add('hidden');
    stopQrPolling();
});

function requestQrCode() {
    qrLoading.classList.remove('hidden');
    qrImage.classList.add('hidden');
    qrStatus.textContent = "正在生成登录二维码...";

    if (window.AndroidBridge && window.AndroidBridge.generateQrCode) {
        window.AndroidBridge.generateQrCode();
    }
}

window.onQrCodeResult = function(rawJson) {
    try {
        const res = typeof rawJson === 'string' ? JSON.parse(rawJson) : rawJson;
        if (res.code === 0 && res.data && res.data.url) {
            currentQrKey = res.data.qrcode_key;
            qrImage.src = `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(res.data.url)}`;
            qrImage.onload = () => {
                qrLoading.classList.add('hidden');
                qrImage.classList.remove('hidden');
                qrStatus.textContent = "请打开 哔哩哔哩手机客户端 扫码确认";
                startQrPolling(currentQrKey);
            };
        } else {
            qrStatus.textContent = "获取二维码失败，请重试";
        }
    } catch (e) {
        qrStatus.textContent = "解析异常: " + e.message;
    }
};

function startQrPolling(key) {
    stopQrPolling();
    qrPollInterval = setInterval(() => {
        if (window.AndroidBridge && window.AndroidBridge.pollQrCode) {
            window.AndroidBridge.pollQrCode(key);
        }
    }, 2000);
}

function stopQrPolling() {
    if (qrPollInterval) {
        clearInterval(qrPollInterval);
        qrPollInterval = null;
    }
}

window.onQrPollResult = function(rawJson) {
    try {
        const res = typeof rawJson === 'string' ? JSON.parse(rawJson) : rawJson;
        const data = res.data || {};
        const code = data.code;

        if (code === 0) {
            // Success
            stopQrPolling();
            qrStatus.textContent = "登录成功！";
            showToast("B站登录成功，已解锁 4K/大会员 画质！");
            updateLoginStatusUI(true);
            setTimeout(() => {
                qrModal.classList.add('hidden');
            }, 1200);
        } else if (code === 86038) {
            // Expired
            stopQrPolling();
            qrStatus.textContent = "二维码已过期，请点击刷新";
        } else if (code === 86090) {
            qrStatus.textContent = "已扫码，请在手机端点击确认登录";
        }
    } catch (e) {
        console.error(e);
    }
};

btnRefreshQr.addEventListener('click', requestQrCode);

function updateLoginStatusUI(isLoggedIn) {
    const indicator = document.querySelector('.status-indicator');
    if (isLoggedIn) {
        userStatusText.textContent = "已登录 (大会员)";
        if (indicator) indicator.classList.add('active');
    } else {
        userStatusText.textContent = "登录B站";
        if (indicator) indicator.classList.remove('active');
    }
}

// ==========================================================================
// 7. Toast Notification System
// ==========================================================================
function showToast(msg) {
    if (toastTimeout) {
        clearTimeout(toastTimeout);
        toastTimeout = null;
    }
    toastEl.textContent = msg;
    toastEl.classList.remove('hidden');

    toastTimeout = setTimeout(() => {
        toastEl.classList.add('hidden');
    }, 2400);

    if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast(msg);
    }
}

// Initial status check
window.addEventListener('DOMContentLoaded', () => {
    if (window.AndroidBridge && window.AndroidBridge.getStoredCookies) {
        const c = window.AndroidBridge.getStoredCookies();
        if (c && c.length > 10) {
            updateLoginStatusUI(true);
        }
    }
});
