package com.frankenkitten42.claudewidget.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
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
 * Uses Chrome Custom Tab so Google sign-in works (Google blocks embedded WebViews).
 * Redirect URI is the platform's manual callback page, which displays the auth code
 * for the user to copy/paste back into the app.
 *
 * Flow:
 * 1. launchAuthFlow() → builds URL, stores PKCE state, opens Chrome Custom Tab
 * 2. User authenticates on claude.ai
 * 3. Claude redirects to platform.claude.com/oauth/code/callback?code=...
 * 4. Platform page displays the authorization code
 * 5. User copies the code, returns to the app, pastes it
 * 6. exchangeManualCode() validates state and exchanges code for tokens
 */
class OAuthManager(
    private val context: Context,
    private val tokenStore: TokenStore,
    private val httpClient: OkHttpClient
) {
    // In-memory PKCE state — valid for one auth attempt
    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    /** Builds the authorization URL, stores PKCE state, and opens a Chrome Custom Tab. */
    fun launchAuthFlow() {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        pendingCodeVerifier = codeVerifier
        pendingState = state

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        Log.d("OAuthManager", "Auth URL: $authUri")

        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, authUri)
    }

    /**
     * Called when the user pastes the authorization code from the platform callback page.
     * Exchanges the code for tokens.
     */
    suspend fun exchangeManualCode(code: String): Result<Unit> {
        val verifier = pendingCodeVerifier
            ?: return Result.failure(Exception("No pending auth flow — tap Sign In first"))

        // Clear pending state
        pendingState = null
        pendingCodeVerifier = null

        return exchangeCodeForTokens(code.trim(), verifier)
    }

    // -----------------------------------------------------------------------
    // Token exchange — JSON body, matching CLI behaviour
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

        Log.d("OAuthManager", "Token exchange: code=${code.take(10)}..., redirect=$REDIRECT_URI")

        return try {
            val responseJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    Log.d("OAuthManager", "Token response ${response.code}: $body")
                    check(response.isSuccessful) { "Token exchange failed ${response.code}: $body" }
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
    // Token refresh — JSON body
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
    // PKCE helpers — base64url(randomBytes(32)), sha256 challenge
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
        const val AUTH_URL     = "https://platform.claude.com/oauth/authorize"
        const val TOKEN_URL    = "https://platform.claude.com/v1/oauth/token"
        const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
        const val SCOPES       = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
    }
}
