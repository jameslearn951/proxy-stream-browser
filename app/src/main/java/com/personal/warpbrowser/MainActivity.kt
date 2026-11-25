package com.personal.warpbrowser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var statusBadge: TextView
    private lateinit var nextProxyBtn: TextView
    private lateinit var topBar: LinearLayout

    private var isProxyLocked = false
    private val workingProxies = mutableListOf<String>()
    private var currentIndex = 0
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val proxyApiUrl =
        "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&protocol=http&proxy_format=protocolipport&format=text&timeout=3000"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        statusBadge = findViewById(R.id.statusBadge)
        nextProxyBtn = findViewById(R.id.nextProxyBtn)
        topBar = findViewById(R.id.topBar)

        configureWebView()

        statusBadge.setOnClickListener { fetchAndLockFastestProxy() }
        nextProxyBtn.setOnClickListener { switchToNextProxy() }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadEnteredUrl(urlBar.text.toString())
                true
            } else {
                false
            }
        }

        fetchAndLockFastestProxy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (!isProxyLocked) {
                    Toast.makeText(this@MainActivity, "Blocked: Waiting for proxy lock", Toast.LENGTH_SHORT).show()
                    return true
                }
                val nextUrl = request?.url.toString()
                if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) {
                    urlBar.setText(nextUrl)
                    return false
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val allowed = request?.resources?.filter {
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                }?.toTypedArray()
                if (!allowed.isNullOrEmpty()) request.grant(allowed) else request?.deny()
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                customViewCallback = callback
                topBar.visibility = View.GONE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(-1, -1))
                setImmersiveFullscreen(true)
            }

            override fun onHideCustomView() {
                (window.decorView as FrameLayout).removeView(customVideoView)
                customVideoView = null
                customViewCallback?.onCustomViewHidden()
                topBar.visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                setImmersiveFullscreen(false)
            }
        }
    }

    private fun fetchAndLockFastestProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            statusBadge.text = "UNSUPPORTED"
            return
        }

        isProxyLocked = false
        statusBadge.text = "SCANNING..."
        statusBadge.setBackgroundColor(Color.parseColor("#E65100"))

        Executors.newSingleThreadExecutor().execute {
            try {
                val rawList = URL(proxyApiUrl).readText()
                    .lines()
                    .map { it.trim() }
                    .filter { it.startsWith("http://") }
                    .take(25)

                val pool = Executors.newFixedThreadPool(10)
                val tasks = rawList.map { proxyUrl ->
                    Callable {
                        val hostPort = proxyUrl.removePrefix("http://").split(":")
                        val start = System.currentTimeMillis()
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(hostPort[0], hostPort[1].toInt()), 2500)
                        }
                        Pair(proxyUrl, System.currentTimeMillis() - start)
                    }
                }

                val alive = pool.invokeAll(tasks)
                    .mapNotNull { runCatching { it.get() }.getOrNull() }
                    .sortedBy { it.second }
                    .map { it.first }
                pool.shutdown()

                runOnUiThread {
                    if (alive.isEmpty()) {
                        statusBadge.text = "RETRY"
                        statusBadge.setBackgroundColor(Color.parseColor("#B71C1C"))
                        Toast.makeText(this, "No fast proxies responded. Tap RETRY.", Toast.LENGTH_SHORT).show()
                    } else {
                        workingProxies.clear()
                        workingProxies.addAll(alive)
                        currentIndex = 0
                        applyProxy(workingProxies[0])
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusBadge.text = "NET ERROR"
                    statusBadge.setBackgroundColor(Color.parseColor("#B71C1C"))
                }
            }
        }
    }

    private fun switchToNextProxy() {
        if (workingProxies.isEmpty()) {
            fetchAndLockFastestProxy()
            return
        }
        currentIndex = (currentIndex + 1) % workingProxies.size
        applyProxy(workingProxies[currentIndex])
    }

    private fun applyProxy(proxyUrl: String) {
        isProxyLocked = false
        statusBadge.text = "LOCKING..."
        statusBadge.setBackgroundColor(Color.parseColor("#E65100"))

        val config = ProxyConfig.Builder().addProxyRule(proxyUrl).build()
        ProxyController.getInstance().setProxyOverride(config, Executors.newSingleThreadExecutor()) {
            runOnUiThread {
                isProxyLocked = true
                statusBadge.text = "PROXY #${currentIndex + 1}"
                statusBadge.setBackgroundColor(Color.parseColor("#1B5E20"))
                val target = urlBar.text.toString().ifBlank { "https://ifconfig.me" }
                loadEnteredUrl(target)
            }
        }
    }

    private fun loadEnteredUrl(rawInput: String) {
        if (!isProxyLocked) return
        val trimmed = rawInput.trim()
        val formatted = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=$trimmed"
        }
        urlBar.setText(formatted)
        webView.loadUrl(formatted)
    }

    private fun setImmersiveFullscreen(enable: Boolean) {
        val controller = window.insetsController ?: return
        if (enable) {
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customVideoView != null -> webView.webChromeClient?.onHideCustomView()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }
}
