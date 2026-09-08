// ==========================================================================
// DOM Elements
// ==========================================================================
const urlInput = document.getElementById('url-input');
const btnClear = document.getElementById('btn-clear');
const btnPaste = document.getElementById('btn-paste');
const btnParse = document.getElementById('btn-parse');
const parseSpinner = document.getElementById('parse-spinner');
const parseIcon = document.getElementById('parse-icon');
const parseText = document.getElementById('parse-text');
const platformBadge = document.getElementById('platform-badge');
const platformBadgeText = document.getElementById('platform-badge-text');

const skeletonCard = document.getElementById('skeleton-card');
const previewCard = document.getElementById('preview-card');
const previewCover = document.getElementById('preview-cover');
const previewDuration = document.getElementById('preview-duration');
const previewTitle = document.getElementById('preview-title');
const previewAuthor = document.getElementById('preview-author');
const previewPlatform = document.getElementById('preview-platform');

const partsSection = document.getElementById('parts-section');
const partsHint = document.getElementById('parts-hint');
const partsContainer = document.getElementById('parts-container');

const qualityHint = document.getElementById('quality-hint');
const streamCardsContainer = document.getElementById('stream-cards-container');
const qualitySelect = document.getElementById('quality-select');
const btnDownload = document.getElementById('btn-download');
const btnDownloadText = document.getElementById('btn-download-text');

const taskList = document.getElementById('task-list');
const taskCount = document.getElementById('task-count');

const btnLogin = document.getElementById('btn-login');
const userStatusText = document.getElementById('user-status-text');

// Modal Elements
const loginModal = document.getElementById('login-modal');
const btnCloseModal = document.getElementById('btn-close-modal');
const tabWebLogin = document.getElementById('tab-web-login');
const tabQrLogin = document.getElementById('tab-qr-login');
const viewWebLogin = document.getElementById('view-web-login');
const viewQrLogin = document.getElementById('view-qr-login');
const btnStartWebLogin = document.getElementById('btn-start-web-login');
const qrImage = document.getElementById('qr-image');
const qrLoading = document.getElementById('qr-loading');
const qrStatus = document.getElementById('qr-status');
const btnSaveQr = document.getElementById('btn-save-qr');
const btnOpenBili = document.getElementById('btn-open-bili');
const btnRefreshQr = document.getElementById('btn-refresh-qr');

// Dynamic Island Toast Elements
const toastEl = document.getElementById('toast');
const toastMsg = document.getElementById('toast-msg');

let currentParsedData = null;
let selectedStreamPayload = null;
let qrPollInterval = null;
let currentQrKey = null;
let currentQrUrl = null;
let toastTimeout = null;
const tasksMap = {};

// ==========================================================================
// 1. Platform Detection & Adaptive Themes
// ==========================================================================
function detectPlatform(text) {
    if (!text) return null;
    const s = text.toLowerCase();
    if (s.includes('douyin.com') || s.includes('iesdouyin.com')) return 'douyin';
    if (s.includes('kuaishou.com') || s.includes('gifshow.com')) return 'kuaishou';
    if (s.includes('bilibili.com') || s.includes('b23.tv') || s.includes('bili2233.cn') || 
        /bv[a-za-z0-9]{10}/i.test(s) || /\bav\d+\b/i.test(s) || /\bep\d+\b/i.test(s) || /\bss\d+\b/i.test(s)) {
        return 'bilibili';
    }
    return null;
}

function updatePlatformThemeAndBadge(text) {
    const platform = detectPlatform(text);
    if (!platform) {
        if (platformBadge) platformBadge.className = 'platform-badge hidden';
        document.body.className = '';
        return;
    }

    document.body.className = `theme-${platform}`;
    if (platformBadge && platformBadgeText) {
        platformBadge.className = `platform-badge platform-${platform}`;
        if (platform === 'bilibili') {
            platformBadgeText.textContent = '哔哩哔哩 (B站)';
        } else if (platform === 'douyin') {
            platformBadgeText.textContent = '抖音无水印';
        } else if (platform === 'kuaishou') {
            platformBadgeText.textContent = '快手无水印';
        }
    }
}

function handleInputChanges() {
    const val = urlInput.value.trim();
    if (val.length > 0) {
        btnClear.classList.remove('hidden');
    } else {
        btnClear.classList.add('hidden');
    }
    updatePlatformThemeAndBadge(val);
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
            showToast("已自动读取剪贴板内容");
        } else {
            showToast("剪贴板中暂无文本");
        }
    } else if (navigator.clipboard && navigator.clipboard.readText) {
        navigator.clipboard.readText().then(text => {
            if (text && text.trim().length > 0) {
                urlInput.value = text.trim();
                handleInputChanges();
                showToast("已自动读取剪贴板内容");
            } else {
                showToast("剪贴板中暂无文本");
            }
        }).catch(() => showToast("请长按输入框进行粘贴"));
    } else {
        showToast("请长按输入框进行粘贴");
    }
});

window.onShareReceived = function(sharedText) {
    if (!sharedText) return;
    urlInput.value = sharedText.trim();
    handleInputChanges();
    showToast("检测到外部分享，正在智能解析...");
    triggerParse();
};

// ==========================================================================
// 3. Parse Logic & Skeleton Shimmer Feedback
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
        parseText.textContent = "智能提取";
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
    if (previewCard) previewCard.classList.add('hidden');
    if (skeletonCard) skeletonCard.classList.remove('hidden');

    if (window.AndroidBridge && window.AndroidBridge.parseUrl) {
        window.AndroidBridge.parseUrl(input);
    } else {
        setTimeout(() => {
            setParseLoading(false);
            if (skeletonCard) skeletonCard.classList.add('hidden');
            showToast("原生接口调试预览模式");
        }, 800);
    }
}

btnParse.addEventListener('click', triggerParse);

// Called by Android WebAppBridge
window.onParseResult = function(rawJson) {
    setParseLoading(false);
    if (skeletonCard) skeletonCard.classList.add('hidden');

    try {
        const res = typeof rawJson === 'string' ? JSON.parse(rawJson) : rawJson;
        if (!res.success) {
            showToast(res.message || "未能解析出视频直链");
            return;
        }
        renderPreview(res);
        showToast("解析成功！已自动优选最高画质");
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

// ==========================================================================
// 4. Preview & Custom Tactile Stream Cards Rendering
// ==========================================================================
function renderPreview(data) {
    currentParsedData = data;
    selectedStreamPayload = null;

    if (previewCard) previewCard.classList.remove('hidden');
    previewTitle.textContent = data.title || "未知标题";
    previewAuthor.textContent = data.author || "媒体创作者";
    previewCover.src = data.pic || "";
    previewDuration.textContent = formatDuration(data.duration);

    const platform = data.platform || 'bilibili';
    previewPlatform.textContent = platform === 'bilibili' ? 'Bilibili' : (platform === 'douyin' ? '抖音' : '快手');
    document.body.className = `theme-${platform}`;

    // Multi-Part (分P) Selector Rendering
    if (platform === 'bilibili' && data.pages && data.pages.length > 1) {
        renderParts(data.pages, data.current_page || 1, data.bvid);
    } else {
        if (partsSection) partsSection.classList.add('hidden');
        if (partsContainer) partsContainer.innerHTML = '';
    }

    // Build Stream Cards Spec
    const streamItems = [];

    if (platform === 'bilibili' && data.play_data) {
        const playData = data.play_data;
        const dash = playData.dash;

        if (dash) {
            const videos = dash.video || [];
            const audios = dash.audio || [];
            const bestAudio = audios.length > 0 ? (audios[0].baseUrl || audios[0].base_url) : null;

            const qnMap = {
                127: "8K 超高清",
                126: "杜比视界 Dolby Vision",
                125: "HDR 真彩",
                120: "4K 超清",
                116: "1080P 60帧",
                112: "1080P 高码率",
                80: "1080P 高清",
                64: "720P 高清",
                32: "480P 清晰",
                16: "360P 极速"
            };

            const addedKeys = new Set();
            videos.forEach(v => {
                const qn = v.id;
                const codec = (v.codecs && v.codecs.startsWith('hev')) ? 'HEVC' : ((v.codecs && v.codecs.startsWith('avc')) ? 'AVC' : 'AV01');
                const key = `${qn}_${codec}`;
                if (!addedKeys.has(key)) {
                    addedKeys.add(key);
                    const qnLabel = qnMap[qn] || `画质 ${qn}`;
                    const isVip = qn >= 112;
                    streamItems.push({
                        label: qnLabel,
                        isVip: isVip,
                        subTag: `[${codec}] · 高清独立音视频`,
                        payload: {
                            videoUrl: v.baseUrl || v.base_url,
                            audioUrl: bestAudio,
                            referer: "https://www.bilibili.com/",
                            userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            isAudio: false
                        }
                    });
                }
            });

            // Audio-only extraction card
            if (bestAudio) {
                streamItems.push({
                    label: "提取独立音频流",
                    isVip: false,
                    subTag: "高音质 M4A · 自动存入系统音乐库",
                    payload: {
                        videoUrl: bestAudio,
                        audioUrl: null,
                        referer: "https://www.bilibili.com/",
                        userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        isAudio: true
                    }
                });
            }
        } else if (playData.durl && playData.durl.length > 0) {
            const first = playData.durl[0];
            streamItems.push({
                label: "高清标准流",
                isVip: false,
                subTag: "MP4 单文件 · 原生音画一体",
                payload: {
                    videoUrl: first.url,
                    audioUrl: null,
                    referer: "https://www.bilibili.com/",
                    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    isAudio: false
                }
            });
        }

    } else if (platform === 'douyin') {
        const streams = data.streams || [];
        if (streams.length > 0) {
            streams.forEach(s => {
                const brText = s.bit_rate ? `${Math.round(s.bit_rate / 1024)} kbps · ` : '';
                streamItems.push({
                    label: s.quality || '超清画质',
                    isVip: false,
                    subTag: `${brText}无水印 MP4`,
                    payload: {
                        videoUrl: s.url,
                        audioUrl: null,
                        referer: "https://www.douyin.com/",
                        userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15",
                        isAudio: false
                    }
                });
            });

            // Audio extraction for Douyin
            streamItems.push({
                label: "提取背景音频",
                isVip: false,
                subTag: "无损音轨 · 自动存入系统音乐库",
                payload: {
                    videoUrl: streams[0].url,
                    audioUrl: null,
                    referer: "https://www.douyin.com/",
                    userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15",
                    isAudio: true
                }
            });

        } else if (data.direct_video_url) {
            streamItems.push({
                label: "原画超清",
                isVip: false,
                subTag: "无水印 MP4 · 原生直链",
                payload: {
                    videoUrl: data.direct_video_url,
                    audioUrl: null,
                    referer: "https://www.douyin.com/",
                    userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15",
                    isAudio: false
                }
            });
            streamItems.push({
                label: "提取背景音频",
                isVip: false,
                subTag: "无损音轨 · 自动存入系统音乐库",
                payload: {
                    videoUrl: data.direct_video_url,
                    audioUrl: null,
                    referer: "https://www.douyin.com/",
                    userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15",
                    isAudio: true
                }
            });
        }

    } else if (data.direct_video_url) {
        streamItems.push({
            label: "原画超清",
            isVip: false,
            subTag: "无水印 MP4 · 快手高速流",
            payload: {
                videoUrl: data.direct_video_url,
                audioUrl: null,
                referer: "https://v.kuaishou.com/",
                userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15",
                isAudio: false
            }
        });
        streamItems.push({
            label: "提取背景音频",
            isVip: false,
            subTag: "无损音轨 · 自动存入系统音乐库",
            payload: {
                videoUrl: data.direct_video_url,
                audioUrl: null,
                referer: "https://v.kuaishou.com/",
                userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15",
                isAudio: true
            }
        });
    }

    renderStreamCards(streamItems);

    // Scroll smoothly to preview
    previewCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

// Render Multi-Part (分P) Selector Pills
function renderParts(pages, activePage, bvid) {
    if (!partsSection || !partsContainer) return;
    partsSection.classList.remove('hidden');
    partsHint.textContent = `共 ${pages.length} 集 · 当前第 ${activePage} 集`;
    partsContainer.innerHTML = '';

    pages.forEach(p => {
        const chip = document.createElement('button');
        const isCurrent = (p.page === activePage);
        chip.className = 'part-chip' + (isCurrent ? ' active' : '');
        chip.title = p.part || `P${p.page}`;
        chip.textContent = `P${p.page} ${p.part || ''}`;

        chip.addEventListener('click', () => {
            if (isCurrent) return;
            urlInput.value = `https://www.bilibili.com/video/${bvid}?p=${p.page}`;
            handleInputChanges();
            triggerParse();
        });
        partsContainer.appendChild(chip);
    });
}

// Render Tactile Custom Stream Cards
function renderStreamCards(items) {
    if (!streamCardsContainer) return;
    streamCardsContainer.innerHTML = '';
    if (qualitySelect) qualitySelect.innerHTML = '';

    if (items.length === 0) {
        if (qualityHint) qualityHint.textContent = "未能获取到可用画质";
        return;
    }

    if (qualityHint) {
        qualityHint.textContent = `已自动优选最高画质 · 共 ${items.length} 档规格`;
    }

    items.forEach((item, idx) => {
        // Also populate hidden native select for fallback compatibility
        if (qualitySelect) {
            const opt = document.createElement('option');
            opt.value = JSON.stringify(item.payload);
            opt.textContent = `${item.label} (${item.subTag})`;
            if (idx === 0) opt.selected = true;
            qualitySelect.appendChild(opt);
        }

        const card = document.createElement('div');
        const isSelected = (idx === 0);
        if (isSelected) {
            selectedStreamPayload = item.payload;
            if (btnDownloadText) {
                btnDownloadText.textContent = item.payload.isAudio ? "下载并保存至手机音乐库" : "下载并保存至手机相册";
            }
        }

        card.className = 'stream-card' + (isSelected ? ' selected' : '');
        card.innerHTML = `
            <div class="stream-card-left">
                <div class="stream-title-row">
                    <span class="stream-label">${item.label}</span>
                    ${item.isVip ? '<span class="stream-vip-tag">大会员</span>' : ''}
                </div>
                <div class="stream-badge-row">
                    <span class="stream-sub-tag">${item.subTag}</span>
                </div>
            </div>
            <div class="stream-check-mark">
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="20 6 9 17 4 12" />
                </svg>
            </div>
        `;

        card.addEventListener('click', () => {
            selectStreamCard(item, card);
        });

        streamCardsContainer.appendChild(card);
    });
}

function selectStreamCard(item, cardElement) {
    selectedStreamPayload = item.payload;
    document.querySelectorAll('.stream-card').forEach(c => c.classList.remove('selected'));
    cardElement.classList.add('selected');

    if (qualitySelect) {
        qualitySelect.value = JSON.stringify(item.payload);
    }
    if (btnDownloadText) {
        btnDownloadText.textContent = item.payload.isAudio ? "下载并保存至手机音乐库" : "下载并保存至手机相册";
    }
}

// ==========================================================================
// 5. Download Execution
// ==========================================================================
btnDownload.addEventListener('click', () => {
    if (!currentParsedData || !selectedStreamPayload) {
        showToast("请先选择清晰度或提取规格");
        return;
    }

    try {
        let taskTitle = currentParsedData.title || "媒体下载";
        if (currentParsedData.pages && currentParsedData.pages.length > 1 && currentParsedData.part_title) {
            taskTitle = `${currentParsedData.title} - P${currentParsedData.current_page || 1} ${currentParsedData.part_title}`;
        }
        if (selectedStreamPayload.isAudio) {
            taskTitle += " [音频]";
        }

        const taskPayload = {
            title: taskTitle,
            videoUrl: selectedStreamPayload.videoUrl,
            audioUrl: selectedStreamPayload.audioUrl,
            referer: selectedStreamPayload.referer,
            userAgent: selectedStreamPayload.userAgent,
            isAudio: !!selectedStreamPayload.isAudio
        };

        if (window.AndroidBridge && window.AndroidBridge.startDownload) {
            window.AndroidBridge.startDownload(JSON.stringify(taskPayload));
        } else {
            showToast("原生下载服务未连接 (测试模式)");
        }
    } catch (e) {
        showToast("创建下载异常: " + e.message);
    }
});

// ==========================================================================
// 6. Tasks Queue Progress Handling
// ==========================================================================
window.onTaskAdded = function(id, title) {
    tasksMap[id] = { id, title, progress: 0, status: 'downloading', speed: '等待连接...' };
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
    if (taskCount) taskCount.textContent = list.length;
    if (list.length === 0) {
        taskList.innerHTML = `
            <div class="empty-state">
                <div class="empty-icon">
                    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
                        <line x1="8" y1="21" x2="16" y2="21" />
                        <line x1="12" y1="17" x2="12" y2="21" />
                    </svg>
                </div>
                <p class="empty-title">队列空闲</p>
                <p class="empty-desc">解析视频后选择清晰度，下载即可后台极速写入手机相册</p>
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
            statusLabel = "无损音视频混流...";
            statusClass = "status-merging";
        } else if (t.status === 'saving') {
            statusLabel = "写入手机媒体库...";
            statusClass = "status-merging";
        } else if (t.status === 'completed') {
            statusLabel = "已存入系统相册/音乐库";
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
                    <span style="color: var(--danger)">${t.error || ''}</span>
                </div>
            </div>
        `;
    }).join('');
}

// ==========================================================================
// 6. Dual-Mode Bilibili Login Bottom Sheet
// ==========================================================================
btnLogin.addEventListener('click', () => {
    loginModal.classList.remove('hidden');
    // Default to Web Login tab
    switchTab('web');
});

btnCloseModal.addEventListener('click', () => {
    loginModal.classList.add('hidden');
    stopQrPolling();
});

// Close modal when clicking backdrop
loginModal.addEventListener('click', (e) => {
    if (e.target === loginModal) {
        loginModal.classList.add('hidden');
        stopQrPolling();
    }
});

function switchTab(mode) {
    if (mode === 'web') {
        tabWebLogin.classList.add('active');
        tabQrLogin.classList.remove('active');
        viewWebLogin.classList.remove('hidden');
        viewQrLogin.classList.add('hidden');
        stopQrPolling();
    } else {
        tabQrLogin.classList.add('active');
        tabWebLogin.classList.remove('active');
        viewQrLogin.classList.remove('hidden');
        viewWebLogin.classList.add('hidden');
        requestQrCode();
    }
}

tabWebLogin.addEventListener('click', () => switchTab('web'));
tabQrLogin.addEventListener('click', () => switchTab('qr'));

// Mode 1: In-App Web Login
btnStartWebLogin.addEventListener('click', () => {
    loginModal.classList.add('hidden');
    if (window.AndroidBridge && window.AndroidBridge.openWebLogin) {
        window.AndroidBridge.openWebLogin();
    } else {
        showToast("正在启动内置安全登录窗口...");
    }
});

// Called when in-app web login finishes
window.onLoginSuccess = function(cookies) {
    updateLoginStatusUI(true);
    showToast("B站登录成功！已解锁 4K/大会员 画质");
};

// Mode 2: Enhanced QR Code Login
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
            currentQrUrl = `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(res.data.url)}`;
            qrImage.src = currentQrUrl;
            qrImage.onload = () => {
                qrLoading.classList.add('hidden');
                qrImage.classList.remove('hidden');
                qrStatus.textContent = "请使用 哔哩哔哩手机客户端 扫码确认";
                startQrPolling(currentQrKey);
            };
        } else {
            qrStatus.textContent = "获取二维码失败，请刷新重试";
        }
    } catch (e) {
        qrStatus.textContent = "生成异常: " + e.message;
    }
};

function startQrPolling(key) {
    stopQrPolling();
    qrPollInterval = setInterval(() => {
        if (window.AndroidBridge && window.AndroidBridge.pollQrCode) {
            window.AndroidBridge.pollQrCode(key);
        }
    }, 2500);
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
            qrStatus.textContent = "授权成功！已同步大会员凭证";
            showToast("B站登录成功，已解锁 4K/大会员 画质！");
            updateLoginStatusUI(true);
            setTimeout(() => {
                loginModal.classList.add('hidden');
            }, 1200);
        } else if (code === 86038) {
            // Expired
            stopQrPolling();
            qrStatus.textContent = "二维码已过期，请点击刷新";
        } else if (code === 86090) {
            qrStatus.textContent = "已扫码，请在手机端点击【确认登录】";
        }
    } catch (e) {
        console.error(e);
    }
};

btnRefreshQr.addEventListener('click', requestQrCode);

btnSaveQr.addEventListener('click', () => {
    if (!currentQrUrl) {
        showToast("二维码尚未就绪");
        return;
    }
    if (window.AndroidBridge && window.AndroidBridge.saveQrImage) {
        window.AndroidBridge.saveQrImage(currentQrUrl);
    } else {
        showToast("已触发保存二维码到相册");
    }
});

btnOpenBili.addEventListener('click', () => {
    if (window.AndroidBridge && window.AndroidBridge.openBilibiliApp) {
        window.AndroidBridge.openBilibiliApp();
    } else {
        showToast("正在尝试唤起哔哩哔哩APP...");
    }
});

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
// 8. Dynamic Island Toast System
// ==========================================================================
function showToast(msg) {
    if (toastTimeout) {
        clearTimeout(toastTimeout);
        toastTimeout = null;
    }
    toastMsg.textContent = msg;
    toastEl.classList.remove('hidden');

    toastTimeout = setTimeout(() => {
        toastEl.classList.add('hidden');
    }, 2600);
}

// Check initial stored cookies on startup
window.addEventListener('DOMContentLoaded', () => {
    if (window.AndroidBridge && window.AndroidBridge.getStoredCookies) {
        const c = window.AndroidBridge.getStoredCookies();
        if (c && c.length > 10) {
            updateLoginStatusUI(true);
        }
    }
});
