package com.frankenkitten42.claudewidget.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
 * Entry point. Handles sign-in via Chrome Custom Tab + manual code paste.
 *
 * 1. User taps Sign In → Chrome Custom Tab opens claude.ai OAuth page
 * 2. User authenticates (supports Google sign-in, unlike WebView)
 * 3. Claude redirects to platform.claude.com/oauth/code/callback
 * 4. Platform page displays the authorization code
 * 5. User copies code, returns to app, pastes it, taps Submit
 * 6. We exchange the code for tokens and activate the widget
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var tokenStore: TokenStore
    private lateinit var oauthManager: OAuthManager
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var codeInputLayout: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var submitCodeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        val httpClient = OkHttpClient()
        tokenStore = TokenStore(this)
        oauthManager = OAuthManager(this, tokenStore, httpClient)

        statusText      = findViewById(R.id.tv_status)
        signInButton    = findViewById(R.id.btn_sign_in)
        signOutButton   = findViewById(R.id.btn_sign_out)
        codeInputLayout = findViewById(R.id.layout_code_input)
        codeInput       = findViewById(R.id.et_auth_code)
        submitCodeButton = findViewById(R.id.btn_submit_code)

        signInButton.setOnClickListener {
            setStatus("Opening Claude sign-in…\nAfter authorizing, copy the code shown and paste it below.")
            signInButton.isEnabled = false
            oauthManager.launchAuthFlow()
            // Show code input area when user returns from browser
            codeInputLayout.visibility = View.VISIBLE
            codeInput.text.clear()
        }

        submitCodeButton.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.isEmpty()) {
                setStatus("Please paste the authorization code first.")
                return@setOnClickListener
            }
            submitCodeButton.isEnabled = false
            setStatus("Exchanging code for tokens…")
            lifecycleScope.launch {
                val result = oauthManager.exchangeManualCode(code)
                if (result.isSuccess) {
                    setStatus("Signed in successfully!")
                    codeInputLayout.visibility = View.GONE
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
                submitCodeButton.isEnabled = true
                signInButton.isEnabled = true
                updateUi()
            }
        }

        signOutButton.setOnClickListener {
            tokenStore.clear()
            updateUi()
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        // If user returned from Chrome without completing auth, re-enable button
        if (!tokenStore.isLoggedIn && !signInButton.isEnabled) {
            signInButton.isEnabled = true
        }
    }

    private fun updateUi() {
        if (tokenStore.isLoggedIn) {
            statusText.text = "Signed in ✓\nWidget is active."
            signInButton.visibility = View.GONE
            signOutButton.visibility = View.VISIBLE
            codeInputLayout.visibility = View.GONE
        } else {
            if (codeInputLayout.visibility != View.VISIBLE) {
                statusText.text = "Sign in to activate the Claude usage widget."
            }
            signInButton.visibility = View.VISIBLE
            signOutButton.visibility = View.GONE
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }
}
