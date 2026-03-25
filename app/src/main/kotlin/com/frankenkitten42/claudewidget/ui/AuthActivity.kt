package com.frankenkitten42.claudewidget.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
 * Entry point. Handles sign-in via Chrome Custom Tab + custom scheme callback.
 *
 * 1. User taps Sign In → Chrome Custom Tab opens claude.ai OAuth page
 * 2. User authenticates (supports Google sign-in, unlike WebView)
 * 3. Claude redirects to claude-widget://oauth/callback?code=...
 * 4. Android delivers the URI to onNewIntent (launchMode=singleTop)
 * 5. We exchange the code for tokens and activate the widget
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var tokenStore: TokenStore
    private lateinit var oauthManager: OAuthManager
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        val httpClient = OkHttpClient()
        tokenStore = TokenStore(this)
        oauthManager = OAuthManager(this, tokenStore, httpClient)

        statusText    = findViewById(R.id.tv_status)
        signInButton  = findViewById(R.id.btn_sign_in)
        signOutButton = findViewById(R.id.btn_sign_out)

        signInButton.setOnClickListener {
            setStatus("Opening Claude sign-in…")
            signInButton.isEnabled = false
            oauthManager.launchAuthFlow()
        }

        signOutButton.setOnClickListener {
            tokenStore.clear()
            updateUi()
        }

        // Handle callback if app was cold-started via the redirect URI
        intent?.data?.let { handleCallbackUri(it) }

        updateUi()
    }

    /** Called when Chrome redirects to claude-widget://oauth/callback */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { handleCallbackUri(it) }
    }

    override fun onResume() {
        super.onResume()
        // If user returned from Chrome without completing auth, re-enable button
        if (!tokenStore.isLoggedIn && !signInButton.isEnabled) {
            signInButton.isEnabled = true
            if (statusText.text == "Opening Claude sign-in…") {
                updateUi()
            }
        }
    }

    private fun handleCallbackUri(uri: android.net.Uri) {
        if (uri.scheme != "claude-widget") return

        setStatus("Completing sign-in…")
        signInButton.isEnabled = false

        lifecycleScope.launch {
            val result = oauthManager.handleCallback(uri)
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
}
