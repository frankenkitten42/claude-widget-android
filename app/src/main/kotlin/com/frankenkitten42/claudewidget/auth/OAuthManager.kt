package com.frankenkitten42.claudewidget.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Handles the full OAuth 2.0 + PKCE flow for Claude authentication.
 *
 * Flow:
 * 1. Fetch client_id from Claude's metadata endpoint
 * 2. Generate PKCE pair + state nonce
 * 3. Open Chrome Custom Tab → claude.ai/oauth/authorize
 * 4. Receive callback at claude-widget://oauth/callback
 * 5. Validate state, exchange code for tokens
 * 6. Store tokens in TokenStore (Android Keystore)
 */
class OAuthManager(
    private val context: Context,
    private val tokenStore: TokenStore,
    private val httpClient: OkHttpClient
) {

    // In-memory PKCE state — only valid for the duration of one auth attempt
    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    // -----------------------------------------------------------------------
    // Step 1 — Fetch client metadata to get client_id
    // -----------------------------------------------------------------------
    suspend fun fetchClientId(): String {
        val request = Request.Builder()
            .url(METADATA_URL)
            .header("anthropic-beta", OAUTH_BETA)
            .build()
        val body = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Metadata fetch failed: ${response.code}" }
                response.body!!.string()
            }
        }
        return JSONObject(body).getString("client_id")
    }

    // -----------------------------------------------------------------------
    // Step 2+3 — Build auth URL and open Chrome Custom Tab
    // -----------------------------------------------------------------------
    suspend fun launchAuthFlow() {
        val clientId = fetchClientId()

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        // Store for validation when callback arrives
        pendingCodeVerifier = codeVerifier
        pendingState = state

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, authUri)
    }

    // -----------------------------------------------------------------------
    // Step 4+5 — Handle the redirect callback
    // -----------------------------------------------------------------------
    suspend fun handleCallback(callbackUri: Uri): Result<Unit> {
        val code = callbackUri.getQueryParameter("code")
            ?: return Result.failure(Exception("No auth code in callback"))

        val returnedState = callbackUri.getQueryParameter("state")
        if (returnedState != pendingState) {
            pendingCodeVerifier = null
            pendingState = null
            return Result.failure(Exception("State mismatch — possible CSRF attack"))
        }

        val verifier = pendingCodeVerifier
            ?: return Result.failure(Exception("No pending code verifier"))

        return exchangeCodeForTokens(code, verifier)
    }

    // -----------------------------------------------------------------------
    // Token exchange — POST to token endpoint
    // -----------------------------------------------------------------------
    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): Result<Unit> {
        val clientId = fetchClientId()

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("anthropic-beta", OAUTH_BETA)
            .build()

        return try {
            val responseJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Token exchange failed: ${response.code}" }
                    JSONObject(response.body!!.string())
                }
            }
            tokenStore.save(
                accessToken  = responseJson.getString("access_token"),
                refreshToken = responseJson.getString("refresh_token"),
                expiresAt    = responseJson.getLong("expires_in") * 1000L + System.currentTimeMillis()
            )
            pendingCodeVerifier = null
            pendingState = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -----------------------------------------------------------------------
    // Token refresh
    // -----------------------------------------------------------------------
    suspend fun refreshTokens(): Result<Unit> {
        val refreshToken = tokenStore.refreshToken
            ?: return Result.failure(Exception("No refresh token"))

        val clientId = fetchClientId()

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("anthropic-beta", OAUTH_BETA)
            .build()

        return try {
            val responseJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        tokenStore.clear()
                        throw Exception("Refresh token expired — re-auth required")
                    }
                    check(response.isSuccessful) { "Token refresh failed: ${response.code}" }
                    JSONObject(response.body!!.string())
                }
            }
            tokenStore.save(
                accessToken  = responseJson.getString("access_token"),
                refreshToken = responseJson.getString("refresh_token"),
                expiresAt    = responseJson.getLong("expires_in") * 1000L + System.currentTimeMillis()
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -----------------------------------------------------------------------
    // PKCE helpers
    // -----------------------------------------------------------------------
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val REDIRECT_URI  = "claude-widget://oauth/callback"
        const val OAUTH_BETA    = "oauth-2025-04-20"
        const val METADATA_URL  = "https://claude.ai/oauth/claude-code-client-metadata"
        const val AUTH_URL      = "https://claude.ai/oauth/authorize"
        const val TOKEN_URL     = "https://platform.claude.com/v1/oauth/token"
        const val SCOPES        = "user:inference user:profile user:sessions:claude_code user:mcp_servers user:file_upload"
    }
}
