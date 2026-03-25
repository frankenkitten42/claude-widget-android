package com.frankenkitten42.claudewidget.ui

import android.content.Intent
import android.net.Uri
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
 * Entry point for the app. Handles:
 * - Showing sign-in button when not authenticated
 * - Launching the Chrome Custom Tab OAuth flow
 * - Receiving the OAuth callback redirect
 * - Triggering the first widget update after sign-in
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

        statusText   = findViewById(R.id.tv_status)
        signInButton = findViewById(R.id.btn_sign_in)
        signOutButton = findViewById(R.id.btn_sign_out)

        signInButton.setOnClickListener {
            lifecycleScope.launch { startOAuthFlow() }
        }

        signOutButton.setOnClickListener {
            tokenStore.clear()
            updateUi()
        }

        // Handle OAuth callback if launched via redirect URI
        handleOAuthCallbackIfPresent(intent)

        updateUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallbackIfPresent(intent)
    }

    private fun handleOAuthCallbackIfPresent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "claude-widget" || uri.host != "oauth") return

        setStatus("Completing sign-in…")
        lifecycleScope.launch {
            val result = oauthManager.handleCallback(uri)
            if (result.isSuccess) {
                setStatus("Signed in successfully!")
                // Schedule periodic updates and do an immediate fetch
                UsageFetchWorker.schedule(this@AuthActivity)
                UsageFetchWorker::class.java.also {
                    androidx.work.OneTimeWorkRequestBuilder<UsageFetchWorker>()
                        .build()
                        .also { req ->
                            androidx.work.WorkManager.getInstance(this@AuthActivity).enqueue(req)
                        }
                }
            } else {
                setStatus("Sign-in failed: ${result.exceptionOrNull()?.message}")
            }
            updateUi()
        }
    }

    private suspend fun startOAuthFlow() {
        setStatus("Opening Claude sign-in…")
        try {
            oauthManager.launchAuthFlow()
        } catch (e: Exception) {
            setStatus("Error: ${e.message}")
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
