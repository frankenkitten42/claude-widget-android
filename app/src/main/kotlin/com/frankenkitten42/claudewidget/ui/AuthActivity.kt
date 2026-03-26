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
import com.frankenkitten42.claudewidget.api.UsageApi
import com.frankenkitten42.claudewidget.api.UsageResult
import com.frankenkitten42.claudewidget.auth.OAuthManager
import com.frankenkitten42.claudewidget.auth.TokenStore
import com.frankenkitten42.claudewidget.worker.UsageFetchWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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

    private lateinit var httpClient: OkHttpClient
    private lateinit var tokenStore: TokenStore
    private lateinit var oauthManager: OAuthManager
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var codeInputLayout: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var submitCodeButton: Button
    private lateinit var testFetchButton: Button
    private lateinit var debugText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        httpClient = OkHttpClient()
        tokenStore = TokenStore(this)
        oauthManager = OAuthManager(this, tokenStore, httpClient)

        statusText      = findViewById(R.id.tv_status)
        signInButton    = findViewById(R.id.btn_sign_in)
        signOutButton   = findViewById(R.id.btn_sign_out)
        codeInputLayout = findViewById(R.id.layout_code_input)
        codeInput       = findViewById(R.id.et_auth_code)
        submitCodeButton = findViewById(R.id.btn_submit_code)
        testFetchButton = findViewById(R.id.btn_test_fetch)
        debugText       = findViewById(R.id.tv_debug)

        signInButton.setOnClickListener {
            setStatus("Opening Claude sign-in…\n\n1. Sign in and click Authorize\n2. Copy the code shown on the next page\n3. Come back here and paste it below")
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

        testFetchButton.setOnClickListener {
            testFetchButton.isEnabled = false
            debugText.visibility = View.VISIBLE
            debugText.text = "Testing...\n"
            lifecycleScope.launch {
                val diag = StringBuilder()
                diag.appendLine("=== Token State ===")
                diag.appendLine("isLoggedIn: ${tokenStore.isLoggedIn}")
                diag.appendLine("isExpired: ${tokenStore.isExpiredOrExpiring}")
                diag.appendLine("expiresAt: ${tokenStore.expiresAt}")
                diag.appendLine("now: ${System.currentTimeMillis()}")
                val tokenPreview = tokenStore.accessToken?.take(20) ?: "null"
                diag.appendLine("token: ${tokenPreview}...")
                diag.appendLine()

                diag.appendLine("=== Refresh Attempt ===")
                if (tokenStore.isExpiredOrExpiring) {
                    val refreshResult = oauthManager.refreshTokens()
                    if (refreshResult.isSuccess) {
                        diag.appendLine("Refresh: SUCCESS")
                        diag.appendLine("new token: ${tokenStore.accessToken?.take(20)}...")
                    } else {
                        diag.appendLine("Refresh: FAILED")
                        diag.appendLine("error: ${refreshResult.exceptionOrNull()?.message}")
                    }
                } else {
                    diag.appendLine("Token still valid, skipping refresh")
                }
                diag.appendLine()

                diag.appendLine("=== Raw API Call ===")
                val token = tokenStore.accessToken
                if (token != null) {
                    try {
                        val rawResult = withContext(Dispatchers.IO) {
                            val req = Request.Builder()
                                .url(UsageApi.USAGE_URL)
                                .header("Accept", "application/json")
                                .header("User-Agent", "claude-code/2.1.83")
                                .header("Authorization", "Bearer $token")
                                .header("anthropic-beta", OAuthManager.OAUTH_BETA)
                                .build()
                            httpClient.newCall(req).execute().use { resp ->
                                val body = resp.body?.string() ?: ""
                                "HTTP ${resp.code}\n$body"
                            }
                        }
                        diag.appendLine(rawResult)
                    } catch (e: Exception) {
                        diag.appendLine("EXCEPTION: ${e.message}")
                    }
                } else {
                    diag.appendLine("No token available")
                }

                debugText.text = diag.toString()
                testFetchButton.isEnabled = true
                updateUi()
            }
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
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
            testFetchButton.visibility = View.VISIBLE
            codeInputLayout.visibility = View.GONE
        } else {
            if (codeInputLayout.visibility != View.VISIBLE) {
                statusText.text = "Sign in to activate the Claude usage widget."
            }
            signInButton.visibility = View.VISIBLE
            signOutButton.visibility = View.GONE
            testFetchButton.visibility = View.GONE
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }
}
