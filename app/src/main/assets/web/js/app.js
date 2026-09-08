// DOM Elements
const urlInput = document.getElementById('url-input');
const btnPaste = document.getElementById('btn-paste');
const btnParse = document.getElementById('btn-parse');
const parseSpinner = document.getElementById('parse-spinner');
const parseText = document.getElementById('parse-text');
const platformBadge = document.getElementById('platform-badge');

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

let currentParsedData = null;
let qrPollInterval = null;
let currentQrKey = null;
const tasksMap = {};

// 1. Platform Detection
function detectPlatform(text) {
    const s = text.toLowerCase();
    if (s.includes('douyin.com') || s.includes('iesdouyin.com')) return 'douyin';
    if (s.includes('kuaishou.com') || s.includes('gifshow.com')) return 'kuaishou';
    if (s.includes('bilibili.com') || s.includes('b23.tv') || /bv[a-za-z0-9]{10}/i.test(s)) return 'bilibili';
    return null;
}

function updatePlatformBadge(text) {
    const platform = detectPlatform(text);
    if (!platform) {
        platformBadge.className = 'platform-badge hidden';
        return;
    }
    platformBadge.className = `platform-badge platform-${platform}`;
    if (platform === 'bilibili') platformBadge.textContent = '哔哩哔哩 B站';
    else if (platform === 'douyin') platformBadge.textContent = '抖音无水印';
    else if (platform === 'kuaishou') platformBadge.textContent = '快手无水印';
}

urlInput.addEventListener('input', (e) => {
    updatePlatformBadge(e.target.value);
});

// 2. Paste & Share handling
btnPaste.addEventListener('click', () => {
    if (window.AndroidBridge && window.AndroidBridge.getClipboardText) {
        const text = window.AndroidBridge.getClipboardText();
        if (text) {
            urlInput.value = text;
            updatePlatformBadge(text);
            showToast("已读取剪贴板");
        } else {
            showToast("剪贴板为空");
        }
    } else {
        navigator.clipboard.readText().then(text => {
            urlInput.value = text;
            updatePlatformBadge(text);
        }).catch(() => showToast("无法读取剪贴板"));
    }
});

window.onShareReceived = function(sharedText) {
    if (!sharedText) return;
    urlInput.value = sharedText;
    updatePlatformBadge(sharedText);
    showToast("收到系统分享，正在解析...");
    triggerParse();
};

// 3. Parse logic
function setParseLoading(loading) {
    if (loading) {
        btnParse.disabled = true;
        parseSpinner.classList.remove('hidden');
        parseText.textContent = "解析中...";
    } else {
        btnParse.disabled = false;
        parseSpinner.classList.add('hidden');
        parseText.textContent = "开始解析";
    }
}

function triggerParse() {
    const input = urlInput.value.trim();
    if (!input) {
        showToast("请输入或粘贴视频链接");
        return;
    }
    setParseLoading(true);
    if (window.AndroidBridge && window.AndroidBridge.parseUrl) {
        window.AndroidBridge.parseUrl(input);
    } else {
        // Dev fallback simulation
        setTimeout(() => {
            setParseLoading(false);
            showToast("原生接口未就绪 (仅调试)");
        }, 1000);
    }
}

btnParse.addEventListener('click', triggerParse);

// Called by Android WebAppBridge
window.onParseResult = function(rawJson) {
    setParseLoading(false);
    try {
        const res = JSON.parse(rawJson);
        if (!res.success) {
            showToast(res.message || "解析失败");
            return;
        }
        renderPreview(res);
        showToast("解析成功！已自动匹配最佳画质");
    } catch (e) {
        showToast("解析结果处理异常: " + e.message);
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
    previewAuthor.textContent = data.author || "未知作者";
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
        const bestAudio = audios.length > 0 ? audios[0].baseUrl || audios[0].base_url : null;

        const qnMap = {
            127: "8K 超高清",
            126: "杜比视界 (Dolby Vision)",
            125: "HDR 真彩",
            120: "4K 超清",
            116: "1080P 60帧高码率",
            112: "1080P 高码率",
            80: "1080P 高清",
            64: "720P 高清",
            32: "480P 清晰"
        };

        const addedQns = new Set();
        videos.forEach(v => {
            const qn = v.id;
            if (!addedQns.has(qn)) {
                addedQns.add(qn);
                const opt = document.createElement('option');
                opt.value = JSON.stringify({
                    videoUrl: v.baseUrl || v.base_url,
                    audioUrl: bestAudio,
                    referer: "https://www.bilibili.com/"
                });
                opt.textContent = `${qnMap[qn] || '画质 ' + qn} (${v.codecs || 'AVC'})`;
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
            optAudio.textContent = "仅提取音频 (MP3/M4A 高音质)";
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
}

// 4. Download Execution
btnDownload.addEventListener('click', () => {
    if (!currentParsedData) return;
    const selectedValue = qualitySelect.value;
    if (!selectedValue) return;

    try {
        const streamInfo = JSON.parse(selectedValue);
        const taskPayload = {
            title: currentParsedData.title || "视频下载",
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
        showToast("提交下载失败: " + e.message);
    }
});

// 5. Tasks Progress handling
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
        taskList.innerHTML = '<div class="empty-hint">暂无下载任务，解析视频后即可一键下载</div>';
        return;
    }

    taskList.innerHTML = list.map(t => {
        let statusLabel = "等待中";
        let statusClass = "status-downloading";
        if (t.status === 'downloading') {
            statusLabel = `下载中 ${t.progress}%`;
        } else if (t.status === 'merging') {
            statusLabel = "合成中...";
            statusClass = "status-merging";
        } else if (t.status === 'saving') {
            statusLabel = "存入相册...";
            statusClass = "status-merging";
        } else if (t.status === 'completed') {
            statusLabel = "已存入相册 ✓";
            statusClass = "status-completed";
        } else if (t.status === 'failed') {
            statusLabel = "下载失败";
            statusClass = "status-failed";
        }

        return `
            <div class="task-item" id="task-${t.id}">
                <div class="task-header">
                    <span class="task-title">${t.title || '下载任务'}</span>
                    <span class="task-status ${statusClass}">${statusLabel}</span>
                </div>
                <div class="progress-bar-bg">
                    <div class="progress-bar-fill" style="width: ${t.progress || 0}%"></div>
                </div>
                <div class="task-footer">
                    <span>${t.speed || ''}</span>
                    <span>${t.error || ''}</span>
                </div>
            </div>
        `;
    }).join('');
}

// 6. QR Code Login Modal
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
        const res = JSON.parse(rawJson);
        if (res.code === 0 && res.data && res.data.url) {
            currentQrKey = res.data.qrcode_key;
            // Generate QR code image using public API
            qrImage.src = `https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(res.data.url)}`;
            qrImage.onload = () => {
                qrLoading.classList.add('hidden');
                qrImage.classList.remove('hidden');
                qrStatus.textContent = "请打开 哔哩哔哩手机客户端 扫码";
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
        const res = JSON.parse(rawJson);
        const data = res.data || {};
        const code = data.code;

        if (code === 0) {
            // Success
            stopQrPolling();
            qrStatus.textContent = "登录成功！";
            showToast("B站登录成功，已解锁专属高清画质！");
            userStatusText.textContent = "已登录 (大会员)";
            setTimeout(() => {
                qrModal.classList.add('hidden');
            }, 1200);
        } else if (code === 86038) {
            // Expired
            stopQrPolling();
            qrStatus.textContent = "二维码已过期，请刷新";
        } else if (code === 86090) {
            qrStatus.textContent = "已扫码，请在手机端点击确认登录";
        }
    } catch (e) {
        console.error(e);
    }
};

btnRefreshQr.addEventListener('click', requestQrCode);

// Toast
function showToast(msg) {
    if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast(msg);
    } else {
        alert(msg);
    }
}

// Initial status check
window.addEventListener('DOMContentLoaded', () => {
    if (window.AndroidBridge && window.AndroidBridge.getStoredCookies) {
        const c = window.AndroidBridge.getStoredCookies();
        if (c && c.length > 10) {
            userStatusText.textContent = "已登录 (有效)";
        }
    }
});
