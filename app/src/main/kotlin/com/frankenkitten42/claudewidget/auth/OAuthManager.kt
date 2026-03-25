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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Handles the full OAuth 2.0 + PKCE flow for Claude authentication.
 *
 * Uses RFC 8252 loopback redirect: starts a temporary local HTTP server on a
 * random OS-assigned port, then redirects the browser there after auth.
 * This matches exactly how the Claude Code CLI handles authentication.
 *
 * Flow:
 * 1. Generate PKCE pair + state nonce
 * 2. Start local HTTP server on random port
 * 3. Open Chrome Custom Tab → claude.ai/oauth/authorize
 * 4. User authenticates; browser redirects to http://127.0.0.1:PORT/?code=...
 * 5. Local server captures code, validates state, sends success page
 * 6. Exchange code for tokens, store in Android Keystore
 */
class OAuthManager(
    private val context: Context,
    private val tokenStore: TokenStore,
    private val httpClient: OkHttpClient
) {

    /**
     * Launches the full OAuth flow and returns when it completes (success or failure).
     * Opens Chrome Custom Tab — the coroutine suspends until the browser callback arrives
     * or the 5-minute server timeout expires.
     */
    suspend fun launchAuthFlow(): Result<Unit> {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        // OS assigns a random available port (port 0 → OS picks)
        val serverSocket = withContext(Dispatchers.IO) {
            ServerSocket(0).also { it.soTimeout = 300_000 } // 5 min timeout
        }
        val port = serverSocket.localPort
        val redirectUri = "http://127.0.0.1:$port"

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        // Chrome Custom Tab must be launched from the main thread
        withContext(Dispatchers.Main) {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, authUri)
        }

        // Suspend on IO thread waiting for browser to redirect to our local server
        val (code, returnedState) = withContext(Dispatchers.IO) {
            try {
                val socket = serverSocket.accept()
                val requestLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                    ?: return@withContext Pair<String?, String?>(null, null)

                // requestLine: "GET /?code=XXX&state=YYY HTTP/1.1"
                val path = requestLine.split(" ").getOrNull(1)
                    ?: return@withContext Pair<String?, String?>(null, null)
                val callbackUri = Uri.parse("http://x$path")

                val html = """
                    <html><body style="font-family:sans-serif;text-align:center;padding-top:60px;background:#16213e;color:white">
                    <h2 style="color:#d97706">Login successful!</h2>
                    <p>You can return to the Claude Widget app.</p>
                    </body></html>
                """.trimIndent()
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
                socket.getOutputStream().write(response.toByteArray())
                socket.close()

                Pair(callbackUri.getQueryParameter("code"), callbackUri.getQueryParameter("state"))
            } catch (e: Exception) {
                Pair<String?, String?>(null, null)
            } finally {
                serverSocket.runCatching { close() }
            }
        }

        if (code == null) return Result.failure(Exception("No auth code received (timeout or cancelled)"))
        if (returnedState != state) return Result.failure(Exception("State mismatch — possible CSRF attack"))

        return exchangeCodeForTokens(code, codeVerifier, redirectUri)
    }

    // -----------------------------------------------------------------------
    // Token exchange — POST to token endpoint
    // -----------------------------------------------------------------------
    private suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): Result<Unit> {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", CLIENT_ID)
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

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
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
        const val CLIENT_ID  = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val OAUTH_BETA = "oauth-2025-04-20"
        const val AUTH_URL   = "https://claude.ai/oauth/authorize"
        const val TOKEN_URL  = "https://platform.claude.com/v1/oauth/token"
        const val SCOPES     = "user:inference user:profile"
    }
}
