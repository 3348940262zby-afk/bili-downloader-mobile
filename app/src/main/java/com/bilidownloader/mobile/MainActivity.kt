package com.bilidownloader.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.bilidownloader.mobile.bridge.WebAppBridge
import com.bilidownloader.mobile.engine.BiliParser
import com.bilidownloader.mobile.service.DownloadService
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var downloadService: DownloadService? = null
    private var isServiceBound = false
    private var pendingSharedText: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DownloadService.LocalBinder
            downloadService = binder.getService()
            isServiceBound = true

            downloadService?.setProgressListener { taskId, task ->
                runOnUiThread {
                    val safeError = (task.errorMessage ?: "").replace("'", "\\'")
                    val js = "javascript:window.onTaskProgress && window.onTaskProgress('${task.id}', '${task.status}', ${task.progress}, '${task.speed}', '$safeError')"
                    webView.evaluateJavascript(js, null)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            isServiceBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission callback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        handleIncomingIntent(intent)
        checkPermissions()

        val serviceIntent = Intent(this, DownloadService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                pendingSharedText = sharedText
                if (::webView.isInitialized) {
                    val escaped = sharedText.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
                    webView.evaluateJavascript("javascript:window.onShareReceived && window.onShareReceived('$escaped')", null)
                }
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.let {
                    val res = assetLoader.shouldInterceptRequest(it)
                    if (res != null) return res
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pendingSharedText?.let { text ->
                    val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
                    webView.evaluateJavascript("javascript:window.onShareReceived && window.onShareReceived('$escaped')", null)
                    pendingSharedText = null
                }
            }
        }

        val bridge = WebAppBridge(this, webView) { downloadService }
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // Load local asset web UI securely
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun openBilibiliWebLogin() {
        runOnUiThread {
            try {
                val dialog = BottomSheetDialog(this)
                val displayMetrics = resources.displayMetrics
                val targetHeight = (displayMetrics.heightPixels * 0.88).toInt()

                val rootLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight)
                    setBackgroundColor(Color.parseColor("#12151c"))
                }

                // Drag pill
                val pillContainer = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 10)
                }
                val pillView = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(90, 8)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#334155"))
                        cornerRadius = 4f
                    }
                }
                pillContainer.addView(pillView)
                rootLayout.addView(pillContainer)

                // Header bar
                val headerBar = RelativeLayout(this).apply {
                    setPadding(24, 8, 24, 16)
                }
                val titleTextView = TextView(this).apply {
                    text = "哔哩哔哩安全登录"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                val subtitleTextView = TextView(this).apply {
                    text = "登录后自动同步会员画质凭证，无需扫码"
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 12f
                }
                val titleBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleTextView)
                    addView(subtitleTextView)
                }
                headerBar.addView(titleBox)

                val closeBtn = Button(this).apply {
                    text = "✕"
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 16f
                    background = null
                    setOnClickListener { dialog.dismiss() }
                    layoutParams = RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_END)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                    }
                }
                headerBar.addView(closeBtn)
                rootLayout.addView(headerBar)

                // Horizontal Progress Bar
                val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6)
                    max = 100
                    visibility = View.VISIBLE
                }
                rootLayout.addView(progressBar)

                // Dedicated login WebView
                val loginWebView = WebView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = BiliParser.USER_AGENT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(loginWebView, true)

                loginWebView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    }
                }

                loginWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val cookies = cookieManager.getCookie("https://passport.bilibili.com")
                            ?: cookieManager.getCookie(".bilibili.com")
                            ?: ""

                        if (cookies.contains("SESSDATA=") && (cookies.contains("DedeUserID=") || cookies.contains("bili_jct="))) {
                            // Extract and persist cookies
                            getSharedPreferences("bili_downloader_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("bili_cookies", cookies)
                                .apply()

                            Toast.makeText(this@MainActivity, "B站账号登录成功！已同步会员画质权限", Toast.LENGTH_SHORT).show()

                            val escaped = cookies.replace("\\", "\\\\").replace("'", "\\'")
                            webView.evaluateJavascript("javascript:window.onLoginSuccess && window.onLoginSuccess('$escaped')", null)

                            dialog.dismiss()
                        }
                    }
                }

                rootLayout.addView(loginWebView)
                dialog.setContentView(rootLayout)

                dialog.setOnDismissListener {
                    loginWebView.stopLoading()
                    loginWebView.destroy()
                }

                dialog.show()
                loginWebView.loadUrl("https://passport.bilibili.com/login")

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "打开登录界面失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
