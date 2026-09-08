package com.bilidownloader.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.bilidownloader.mobile.bridge.WebAppBridge
import com.bilidownloader.mobile.service.DownloadService

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
                    val js = "javascript:window.onTaskProgress && window.onTaskProgress('${task.id}', '${task.status}', ${task.progress}, '${task.speed}', '${task.errorMessage ?: ""}')"
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
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
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

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
