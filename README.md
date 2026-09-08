# BiliDownloader Mobile (Android 16 独立端侧视频下载器)

> 纯移动端 · 零服务器费用 · 零广告 · 原生极速 · 专为 Android 16 优化设计

---

## 🌟 项目特性

- **纯端侧运行**：所有解析、下载、音视频合成都在手机本地运行，**不需要任何电脑作为中转，零服务器与流量成本**。
- **Android 16 深度适配**：
  - **跨应用一键分享**：在 哔哩哔哩、抖音、快手、浏览器 中点击系统“分享” -> 选择 “BiliDownloader”，自动拉起解析并匹配最佳画质。
  - **系统级相册直存**：符合 Android 16 Scoped Storage 规范，通过 `MediaStore.Video.Media` 静默将视频保存至手机 `Movies/BiliDownloader` 目录，立刻在系统相册中可见。
  - **现代全屏体验**：沉浸式 Edge-to-Edge 边缘到边缘视觉呈现，暗黑主题与流畅触控交互。
- **全平台多画质支持**：
  - **哔哩哔哩 (Bilibili)**：内置 WBI 动态签名加密算子，支持解析 4K 超清、1080P60 高帧率、720P 及纯音频 MP3 分轨流；支持内置扫码登录解锁大会员画质。
  - **抖音 (Douyin)**：支持短链（`v.douyin.com`）自动重定向、TTWID 自动注册、原画无水印 MP4 直链秒下。
  - **快手 (Kuaishou)**：支持精选视频、无水印原画直链解析。
- **原生音视频混流 (Remux)**：
  - 利用 Android 系统级 `MediaExtractor` + `MediaMuxer` 硬件管道，在手机端将分离的视频流与音频流毫秒级封装为标准 MP4，无需重编码，不耗电、不发热。
- **多线程断点续传**：支持前台常驻服务与系统通知栏下载进度、实时速率（MB/s）显示。

---

## 📦 直接下载安装包 (APK)

最新已打包好的 APK 安装包已自动发布到 GitHub Releases：

👉 **[点击直接前往下载最新 APK (GitHub Releases)](https://github.com/3348940262zby-afk/bili-downloader-mobile/releases)**

手机浏览器打开上述链接，在最新版本的 **Assets** 展开列表中点击 **`BiliDownloaderMobile.apk`** 即可直接下载并安装到手机。

---

## 🚀 方式一：GitHub Actions 云端全自动编译与发布

你不需要在电脑上下载十几 GB 的 Android SDK / NDK，利用已配置好的 `.github/workflows/build_apk.yml`，即可让 GitHub 云端服务器为你自动编译 APK。

### 1. 初始化并推送到 GitHub
在本项目根目录打开终端（PowerShell 或 Bash），执行：

```bash
# 初始化 Git 仓库
git init

# 添加所有工程文件
git add .

# 提交代码
git commit -m "feat: initial commit for BiliDownloader Mobile"

# 设置主分支
git branch -M main

# 关联你在 GitHub 上创建的空仓库（替换为你自己的仓库地址）
git remote add origin https://github.com/你的用户名/bili-downloader-mobile.git

# 推送代码
git push -u origin main
```

### 2. 自动构建与下载 APK
1. 打开你的 GitHub 仓库页面，点击顶部的 **Actions** 标签；
2. 你会看到名为 **"Build Android APK"** 的工作流正在自动运行；
3. 构建完成后（通常耗时 2~3 分钟），点击进入该次运行记录，在 **Artifacts（产物）** 区域即可看到打包好的 **`BiliDownloaderMobile-APK`**；
4. 手机扫描下载链接或直接在手机浏览器中打开即可下载并安装 `.apk`！

---

## 🛠️ 方式二：本地 Android Studio 编译

如果你本地电脑安装了 **Android Studio**（Hedgehog / Iguana / Jellyfish / 2024+）：

1. 打开 Android Studio，点击 **File -> Open**，选择 `bili_downloader_mobile` 文件夹；
2. 等待 Gradle 自动完成依赖索引与同步；
3. 连接你的 Android 16 手机（或开启无线调试），点击右上角的绿色 **Run (运行)** 按钮即可直接安装进手机；
4. 或者在终端执行命令生成发布包：
   ```bash
   ./gradlew assembleRelease
   ```
   产物输出路径：`app/build/outputs/apk/release/app-release.apk`。

---

## 📱 使用指南

### 1. 复制链接解析
1. 打开 B站、抖音 或 快手，复制任意视频分享链接；
2. 打开 **BiliDownloader Mobile**，点击 **“读取剪贴板”**；
3. 点击 **“开始解析”**，解析成功后在下方画质列表中选择想要的清晰度（如 4K、1080P 或 仅音频）；
4. 点击 **“下载并保存到系统相册”**，通知栏将实时显示下载速率与进度。

### 2. 系统“分享”快捷直达（最丝滑方式）
1. 在 抖音/B站 看到喜欢的视频，点击视频右下角的 **“分享”**；
2. 在系统弹出的应用列表中选择 **“BiliDownloader”**；
3. App 自动唤醒并直接开始解析，一步直达下载。

### 3. B站大会员登录
1. 点击主界面右上角的 **“登录B站”** 按钮；
2. 弹窗将显示登录二维码；
3. 打开手机上的 **哔哩哔哩官方 App**，扫码并确认授权；
4. 登录成功后，即可解锁 4K、1080P60 等大会员专享超清画质。

---

## 📂 工程核心架构一览

```
bili_downloader_mobile/
├── .github/workflows/build_apk.yml       # GitHub Actions 自动化 CI 构建流
├── app/
│   ├── build.gradle.kts                  # 模块构建配置（SDK 35/36，OkHttp，Coroutines 等）
│   └── src/main/
│       ├── AndroidManifest.xml           # Android 16 清单配置与 ACTION_SEND 分享挂载
│       ├── assets/web/                   # 离线极速 WebUI
│       │   ├── index.html                # 界面结构
│       │   ├── css/style.css             # 响应式移动端暗黑样式
│       │   └── js/app.js                 # 交互控制与数据渲染
│       └── java/com/bilidownloader/mobile/
│           ├── MainActivity.kt           # 核心 Activity，接收系统分享
│           ├── bridge/WebAppBridge.kt    # 原生与 WebUI 之间的通信管道
│           ├── engine/
│           │   ├── BiliParser.kt         # B站 4K/DASH 提取与扫码登录
│           │   ├── DouyinParser.kt       # 抖音无水印原画提取
│           │   ├── KuaishouParser.kt     # 快手去水印提取
│           │   └── WbiSigner.kt          # B站 WBI 动态签名算子
│           ├── service/
│           │   ├── DownloadService.kt    # 前台常驻多线程下载管理器
│           │   └── MediaMuxerHelper.kt   # 系统级音视频快速混流器
│           └── util/GalleryHelper.kt     # Android 16 MediaStore 相册入库
└── README.md
```
