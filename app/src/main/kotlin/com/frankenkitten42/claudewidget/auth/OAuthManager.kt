package com.frankenkitten42.claudewidget.auth

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Handles the OAuth 2.0 + PKCE flow for Claude authentication.
 *
 * Uses a WebView for the authorization step (so we can intercept the redirect
 * without a local server or Chrome Custom Tab PNA restrictions).
 *
 * Flow:
 * 1. Call prepareAuthUrl() to get the URL to load in the WebView
 * 2. WebView loads the URL; user authenticates on claude.ai
 * 3. Claude redirects to REDIRECT_URI with code + state query params
 * 4. AuthActivity intercepts the redirect via WebViewClient and calls handleCallback()
 * 5. handleCallback() validates state, exchanges code for tokens via JSON POST
 */
class OAuthManager(
    private val tokenStore: TokenStore,
    private val httpClient: OkHttpClient
) {
    // In-memory PKCE state — valid for one auth attempt
    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    /**
     * Builds the authorization URL and stores PKCE state for later validation.
     * The caller should load this URL in a WebView.
     */
    fun prepareAuthUrl(): String {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        pendingCodeVerifier = codeVerifier
        pendingState = state

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    /**
     * Called by AuthActivity when the WebView intercepts the redirect to REDIRECT_URI.
     * Validates state nonce, then exchanges the auth code for tokens.
     */
    suspend fun handleCallback(callbackUri: Uri): Result<Unit> {
        val code = callbackUri.getQueryParameter("code")
            ?: return Result.failure(Exception("No auth code in callback"))

        val returnedState = callbackUri.getQueryParameter("state")
        val expectedState = pendingState
        val verifier = pendingCodeVerifier

        pendingState = null
        pendingCodeVerifier = null

        if (returnedState != expectedState) {
            return Result.failure(Exception("State mismatch — possible CSRF attack"))
        }
        if (verifier == null) {
            return Result.failure(Exception("No pending code verifier"))
        }

        return exchangeCodeForTokens(code, verifier)
    }

    // -----------------------------------------------------------------------
    // Token exchange — JSON POST (matching CLI behaviour)
    // -----------------------------------------------------------------------
    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): Result<Unit> {
        val json = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", REDIRECT_URI)
            put("client_id", CLIENT_ID)
            put("code_verifier", codeVerifier)
        }

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .header("anthropic-beta", OAUTH_BETA)
            .build()

        return try {
            val responseJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    check(response.isSuccessful) {
                        "Token exchange failed ${response.code}: $body"
                    }
                    JSONObject(body)
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
    // Token refresh — JSON POST
    // -----------------------------------------------------------------------
    suspend fun refreshTokens(): Result<Unit> {
        val refreshToken = tokenStore.refreshToken
            ?: return Result.failure(Exception("No refresh token"))

        val json = JSONObject().apply {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("client_id", CLIENT_ID)
        }

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .header("anthropic-beta", OAUTH_BETA)
            .build()

        return try {
            val responseJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        tokenStore.clear()
                        throw Exception("Refresh token expired — re-auth required")
                    }
                    val body = response.body?.string() ?: ""
                    check(response.isSuccessful) { "Token refresh failed ${response.code}: $body" }
                    JSONObject(body)
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
    // PKCE helpers — matches CLI: base64url(randomBytes(32)) for verifier/state,
    // base64url(sha256(verifier)) for challenge
    // -----------------------------------------------------------------------
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val CLIENT_ID    = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val OAUTH_BETA   = "oauth-2025-04-20"
        const val AUTH_URL     = "https://claude.ai/oauth/authorize"
        const val TOKEN_URL    = "https://platform.claude.com/v1/oauth/token"
        const val REDIRECT_URI = "http://localhost/callback"
        const val SCOPES       = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
    }
}
