package com.easydocs.viewer

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

class ExcelActivity : BaseActivity() {

    private var webView: WebView? = null
    private var uri: Uri? = null
    private var name = "Spreadsheet"
    @Volatile private var accessFailed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_excel)
        setupChrome(findViewById(R.id.root), "Excel", showBack = true)
        toolbar.inflateMenu(R.menu.excel)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.zoom_in -> webView?.evaluateJavascript("window.zoomBy&&zoomBy(1.25)", null)
                R.id.zoom_out -> webView?.evaluateJavascript("window.zoomBy&&zoomBy(0.8)", null)
            }
            true
        }

        val u = Docs.uriFrom(intent)
        if (u == null) {
            showError(getString(R.string.err_no_file))
            return
        }
        uri = u
        val app = applicationContext
        Docs.bg.execute {
            val n = Docs.displayName(app, u)
            Docs.main.post { name = n; toolbar.title = n }
        }

        val wv = try {
            WebView(this)
        } catch (_: Throwable) {
            // WebView package missing, disabled or updating.
            showError(getString(R.string.err_no_webview))
            return
        }
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.setBackgroundColor(color(R.color.bg))
        wv.addJavascriptInterface(Bridge(), "EasyDocs")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serve(request.url)

            // Block all navigation (the String variant is the one called on Android 5–6).
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            // Android 8+: if the renderer dies (e.g. out of memory on a huge sheet), keep the app alive.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    webView = null
                    if (!isDestroyed) showError(getString(R.string.err_out_of_memory))
                }
                return true
            }
        }
        findViewById<FrameLayout>(R.id.content).addView(
            wv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        wv.loadUrl("https://$HOST/viewer.html?dark=${if (isDark) 1 else 0}")
    }

    /** Everything the WebView loads comes from here: bundled assets or the document itself. */
    private fun serve(url: Uri): WebResourceResponse {
        if (url.host != HOST) return empty(403)
        val path = url.path ?: return empty(404)
        return try {
            if (path == "/doc") {
                val stream = openDoc() ?: return empty(404)
                ok("application/octet-stream", stream)
            } else {
                val file = path.removePrefix("/")
                if (file.contains("..")) return empty(404)
                val mime = when {
                    file.endsWith(".html") -> "text/html"
                    file.endsWith(".js") -> "application/javascript"
                    file.endsWith(".css") -> "text/css"
                    else -> "application/octet-stream"
                }
                ok(mime, assets.open("excel/$file"))
            }
        } catch (_: Exception) {
            empty(404)
        }
    }

    private fun openDoc(): InputStream? {
        val u = uri ?: return null
        return try {
            contentResolver.openInputStream(u)
        } catch (e: Exception) {
            if (e is SecurityException || e is FileNotFoundException) accessFailed = true
            null
        }
    }

    private fun ok(mime: String, data: InputStream) =
        WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), data)

    private fun empty(code: Int) =
        WebResourceResponse("text/plain", "utf-8", code, "Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Called from viewer.js (on a WebView binder thread). */
    inner class Bridge {
        @JavascriptInterface
        fun onLoaded(sheets: Int) {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                uri?.let { Recents.add(applicationContext, it, name, DocKind.EXCEL) }
                hideOverlay()
            }
        }

        @JavascriptInterface
        fun onError(message: String?) {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (accessFailed) {
                    uri?.let { Recents.remove(applicationContext, it) }
                    showError(getString(R.string.err_access))
                } else {
                    showError(message?.takeIf { it.isNotBlank() } ?: getString(R.string.err_unsupported))
                }
                webView?.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val HOST = "easydocs.local"
    }
}
