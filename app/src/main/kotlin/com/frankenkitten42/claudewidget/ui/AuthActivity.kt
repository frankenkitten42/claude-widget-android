package com.frankenkitten42.claudewidget.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.frankenkitten42.claudewidget.R
import com.frankenkitten42.claudewidget.auth.OAuthManager
import com.frankenkitten42.claudewidget.auth.TokenStore
import com.frankenkitten42.claudewidget.worker.UsageFetchWorker
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Entry point. Handles sign-in via an embedded WebView:
 * 1. WebView loads claude.ai OAuth consent page
 * 2. User authenticates; Claude redirects to http://localhost/callback?code=...
 * 3. WebViewClient intercepts that URL (no actual HTTP request made)
 * 4. Code is exchanged for tokens, widget is activated
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var tokenStore: TokenStore
    private lateinit var oauthManager: OAuthManager
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        val httpClient = OkHttpClient()
        tokenStore = TokenStore(this)
        oauthManager = OAuthManager(tokenStore, httpClient)

        statusText    = findViewById(R.id.tv_status)
        signInButton  = findViewById(R.id.btn_sign_in)
        signOutButton = findViewById(R.id.btn_sign_out)
        webView       = findViewById(R.id.web_view)

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = OAuthWebViewClient()

        signInButton.setOnClickListener { startOAuthFlow() }

        signOutButton.setOnClickListener {
            tokenStore.clear()
            updateUi()
        }

        updateUi()
    }

    private fun startOAuthFlow() {
        val authUrl = oauthManager.prepareAuthUrl()
        setStatus("Loading Claude sign-in…")
        signInButton.isEnabled = false
        webView.visibility = View.VISIBLE
        webView.loadUrl(authUrl)
    }

    private fun onOAuthCallback(callbackUri: Uri) {
        webView.visibility = View.GONE
        setStatus("Completing sign-in…")

        lifecycleScope.launch {
            val result = oauthManager.handleCallback(callbackUri)
            if (result.isSuccess) {
                setStatus("Signed in successfully!")
                UsageFetchWorker.schedule(this@AuthActivity)
                androidx.work.OneTimeWorkRequestBuilder<UsageFetchWorker>()
                    .build()
                    .also { req ->
                        androidx.work.WorkManager.getInstance(this@AuthActivity).enqueue(req)
                    }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                setStatus("Sign-in failed: $msg")
            }
            signInButton.isEnabled = true
            updateUi()
        }
    }

    private fun onOAuthError(error: String) {
        webView.visibility = View.GONE
        setStatus("Sign-in failed: $error")
        signInButton.isEnabled = true
        updateUi()
    }

    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE) {
            // Cancel the in-progress OAuth flow
            webView.stopLoading()
            webView.visibility = View.GONE
            setStatus("Sign-in cancelled.")
            signInButton.isEnabled = true
            updateUi()
        } else {
            super.onBackPressed()
        }
    }

    private fun updateUi() {
        if (tokenStore.isLoggedIn) {
            statusText.text = "Signed in ✓\nWidget is active."
            signInButton.visibility = View.GONE
            signOutButton.visibility = View.VISIBLE
        } else {
            statusText.text = "Sign in to activate the Claude usage widget."
            signInButton.visibility = View.VISIBLE
            signOutButton.visibility = View.GONE
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    // -----------------------------------------------------------------------
    // WebView client — intercepts the OAuth redirect
    // -----------------------------------------------------------------------
    private inner class OAuthWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val url = uri.toString()

            // Intercept the redirect to our registered callback URI
            if (url.startsWith(OAuthManager.REDIRECT_URI)) {
                val error = uri.getQueryParameter("error")
                if (error != null) {
                    val desc = uri.getQueryParameter("error_description") ?: error
                    onOAuthError(desc)
                } else {
                    onOAuthCallback(uri)
                }
                return true // cancel WebView navigation
            }
            return false // let WebView handle all other URLs normally
        }
    }
}
