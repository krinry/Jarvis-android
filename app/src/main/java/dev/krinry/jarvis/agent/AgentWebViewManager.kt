package dev.krinry.jarvis.agent

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AgentWebViewManager — Internal browser for Jarvis to execute Javascript on any site.
 * 
 * Creates an overlay WebView so the agent can interact with websites (like Blogger)
 * securely via JS injection. Being an overlay, it also allows AccessibilityService
 * to fallback to manual UI clicks if JS fails.
 */
class AgentWebViewManager(private val context: Context, private val windowManager: WindowManager) {

    companion object {
        private const val TAG = "AgentWebViewManager"
        @Volatile
        var instance: AgentWebViewManager? = null
            private set
    }

    private var webViewContainer: LinearLayout? = null
    private var webView: WebView? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    
    // For synchronous JS execution returning results
    private var jsDeferred: CompletableDeferred<String>? = null

    init {
        instance = this
    }

    /**
     * Shows a floating WebView loading the given URL.
     */
    fun openBrowser(url: String, heightPercent: Float = 0.5f) {
        scope.launch {
            if (webViewContainer == null) {
                createWebViewOverlay(heightPercent)
            }
            webViewContainer?.visibility = View.VISIBLE
            
            val finalUrl = if (url.startsWith("http")) url else "https://$url"
            webView?.loadUrl(finalUrl)
            Log.d(TAG, "Opened URL in Agent Browser: $finalUrl")
        }
    }

    /**
     * Injects and executes JS in the current WebView, returning the result.
     */
    suspend fun executeJavascript(script: String): String {
        if (webView == null || webViewContainer?.visibility != View.VISIBLE) {
            return "❌ Agent Browser is not open. Use open_browser first."
        }

        val deferred = CompletableDeferred<String>()
        jsDeferred = deferred

        scope.launch {
            // JS runs asynchronously
            webView?.evaluateJavascript(script) { result ->
                // evaluateJavascript returns "null" if the script doesn't return anything
                if (result == null || result == "null") {
                    deferred.complete("✅ Javascript executed successfully")
                } else {
                    // Try to unescape string if it's quoted
                    val cleanResult = if (result.startsWith("\"") && result.endsWith("\"")) {
                        result.substring(1, result.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
                    } else {
                        result
                    }
                    deferred.complete("✅ Result: $cleanResult")
                }
            }
        }

        return try {
            // Wait max 10 seconds for script
            kotlinx.coroutines.withTimeout(10_000) {
                deferred.await()
            }
        } catch (e: Exception) {
            "❌ JS Execution Timeout: ${e.message}"
        }
    }

    fun closeBrowser() {
        scope.launch {
            webViewContainer?.visibility = View.GONE
            webView?.loadUrl("about:blank")
        }
    }

    private fun createWebViewOverlay(heightPercent: Float) {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val dm = context.resources.displayMetrics
        val h = (dm.heightPixels * heightPercent).toInt()

        webViewContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF0F0F0.toInt())
            elevation = 16f * dm.density
        }

        // Header with close button
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF6C5CE7.toInt())
            setPadding(doToPx(12), doToPx(8), doToPx(12), doToPx(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "🌐 Agent Browser"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(doToPx(8), 0, 0, 0)
            setOnClickListener { closeBrowser() }
        }
        header.addView(closeBtn)
        webViewContainer?.addView(header)

        // The WebView
        webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Mobile Safari/537.36 KrinryAgent"
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page loaded: $url")
                    title.text = "🌐 ${view?.title?.take(20) ?: "Agent Browser"}"
                }
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "JS Console: ${consoleMessage?.message()}")
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }
        webViewContainer?.addView(webView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(webViewContainer, params)
    }

    private fun doToPx(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    fun destroy() {
        scope.launch {
            webViewContainer?.let { 
                try { windowManager.removeView(it) } catch (e: Exception) {} 
            }
            webView?.destroy()
            webView = null
            webViewContainer = null
            instance = null
        }
    }
}
