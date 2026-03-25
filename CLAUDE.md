# Claude Widget Android — Project Notes

Native Android home screen widget showing Claude API usage (5-hour and 7-day rolling
windows) with color-coded progress bars. OAuth login via Claude's own OAuth system.

**Repo:** `frankenkitten42/claude-widget-android`
**Build:** GitHub Actions only (AAPT2 is x86_64 only; dev device is ARM64 Termux)

---

## Project Status

Phase 1 (Termux:Widget bash PoC) — **complete**, working in `~/projects/claude-widget/`
Phase 2 (Native Android app) — **in progress**, OAuth flow not yet working

Current blocker: `claude-widget://oauth/callback` custom scheme redirect may not be
registered as a valid redirect URI for the OAuth client. Still investigating.

---

## What We Know About Claude's OAuth System

All values extracted directly from `@anthropic-ai/claude-code/cli.js`:

### Client Details
| Field | Value |
|-------|-------|
| **Client ID (UUID)** | `9d1c250a-e61b-44d9-88ed-5944d1962f5e` |
| **Authorization URL** | `https://claude.ai/oauth/authorize` |
| **Token URL** | `https://platform.claude.com/v1/oauth/token` |
| **Beta header** | `anthropic-beta: oauth-2025-04-20` |
| **Scopes** | `org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload` |

### Authorization URL Parameters (exact, from CLI source)
```
code=true                          ← REQUIRED first param (server rejects without it)
client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e
response_type=code
redirect_uri=http://localhost:PORT/callback   ← CLI uses random port loopback
scope=org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload
code_challenge=<base64url(sha256(verifier))>
code_challenge_method=S256
state=<base64url(random 32 bytes)>
```

### Token Exchange (from CLI source)
- **Method:** POST to `https://platform.claude.com/v1/oauth/token`
- **Content-Type: application/json** (NOT form-encoded — this is critical)
- **Body:**
  ```json
  {
    "grant_type": "authorization_code",
    "code": "<auth code>",
    "redirect_uri": "<same as used in auth request>",
    "client_id": "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
    "code_verifier": "<original PKCE verifier>"
  }
  ```

### PKCE Generation (from CLI source)
```javascript
// CLI code (WC1 = base64url encoder):
function nk4() { return WC1(crypto.randomBytes(32)) }   // code_verifier
function rk4(A) { return WC1(crypto.createHash("sha256").update(A).digest()) }  // challenge
function ak4() { return WC1(crypto.randomBytes(32)) }   // state
```
Equivalent Kotlin:
```kotlin
val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
```

### Registered Redirect URIs (from metadata document)
The metadata at `https://claude.ai/oauth/claude-code-client-metadata` is for a
**different client** (URL-format client_id). The UUID client `9d1c250a-...` has
its own registered redirect URIs which we don't fully know. The CLI uses:
- `http://localhost:PORT/callback` (random port, loopback server)
- `https://platform.claude.com/oauth/code/callback` (manual/fallback flow)

**Custom scheme `claude-widget://oauth/callback` is NOT in the CLI's registered URIs.**
The server may or may not accept it — this is what we are currently testing.

### CLI's Actual Auth Flow
The CLI starts a local HTTP server (`this.server.listen(0, "127.0.0.1")`), gets
the assigned port, and uses `http://localhost:PORT/callback` as redirect_uri.
After auth, the browser hits the local server, the CLI reads the code, closes the server.

---

## What We Tried (OAuth Iterations)

### Attempt 1 — Fetch client_id from metadata URL
- **Error:** `client_id: Input should be a valid UUID` — the metadata URL itself
  (`https://claude.ai/oauth/...`) was returned as the client_id value
- **Fix:** Use hardcoded UUID `9d1c250a-e61b-44d9-88ed-5944d1962f5e`

### Attempt 2 — Custom scheme with correct client_id
- **Error:** `Authorization failed, Invalid request format`
- **Cause:** Missing `code=true` parameter, wrong redirect_uri format, incomplete scopes
- **Fix:** Added `code=true`, used `http://localhost:PORT/callback`, full scope list

### Attempt 3 — Local HTTP server (ServerSocket) + Chrome Custom Tab
- **Error:** `Authorization failed, Invalid request format` after clicking Authorize
- **Suspected cause:** Chrome's Private Network Access (PNA) restrictions block
  HTTPS→localhost redirects in Chrome Custom Tab (Chrome 94+)
- **Also discovered:** Token exchange was sending `application/x-www-form-urlencoded`
  but server expects `application/json`

### Attempt 4 — Embedded WebView with URL interception
- **Error:** Gray screen, spinner, then nothing
- **Cause:** Google blocks OAuth sign-in inside Android WebViews entirely
- **Reverted**

### Current (Attempt 5) — Chrome Custom Tab + `claude-widget://` custom scheme
- Uses correct client_id, `code=true`, full scopes, JSON token exchange
- Redirect URI: `claude-widget://oauth/callback`
- Status: **testing** — unknown if server accepts this redirect_uri

---

## Things Still To Figure Out

1. **Does `claude-widget://oauth/callback` work as a redirect URI?**
   If not → need to use `https://platform.claude.com/oauth/code/callback` somehow

2. **Alternative: use the exact same redirect_uri the CLI uses**
   - `http://localhost:PORT/callback` with a local ServerSocket
   - Problem: Chrome PNA may block HTTPS→localhost redirect in Custom Tab
   - Possible workaround: use a `CustomTabsSession` that can pre-warm and listen for redirects

3. **Alternative: use platform.claude.com/oauth/code/callback + deep link**
   - Claude redirects to `https://platform.claude.com/oauth/code/callback?code=...`
   - Anthropic's page could redirect to our custom scheme
   - We have no control over what that page does

4. **Alternative: intercept navigation in CustomTabsCallback**
   - `CustomTabsSession.CustomTabsCallback.onNavigationEvent()` fires on navigation
   - But it doesn't expose the URL, so we can't extract the code

---

## Android App Architecture

### Package Structure
```
com.frankenkitten42.claudewidget/
├── auth/
│   ├── OAuthManager.kt          # PKCE flow, token exchange (JSON body)
│   └── TokenStore.kt            # EncryptedSharedPreferences wrapper
├── api/
│   └── UsageApi.kt              # Usage endpoint, token refresh
├── widget/
│   └── ClaudeUsageWidget.kt     # AppWidgetProvider + RemoteViews
├── worker/
│   └── UsageFetchWorker.kt      # WorkManager periodic fetch (10 min)
└── ui/
    └── AuthActivity.kt          # Chrome Custom Tab launch + callback
```

### Key Files
| File | Purpose |
|------|---------|
| `app/src/main/AndroidManifest.xml` | Permissions, activities, intent filters |
| `app/src/main/res/xml/appwidget_info.xml` | Widget size (3×2 cells, 250×110dp) |
| `app/src/main/res/xml/network_security_config.xml` | Cleartext disabled, HTTPS only |
| `app/src/main/res/layout/widget_claude_usage.xml` | Widget RemoteViews layout |
| `app/build.gradle.kts` | Dependencies, compileSdk 34, minSdk 26 |
| `.github/workflows/build.yml` | GitHub Actions build (ubuntu-latest) |

### Current Dependencies
```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")  // Keystore tokens
implementation("androidx.work:work-runtime-ktx:2.9.0")              // WorkManager
implementation("androidx.browser:browser:1.8.0")                    // Chrome Custom Tab
implementation("com.squareup.okhttp3:okhttp:4.12.0")                // HTTP client
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
```

### Build Notes
- **Gradle 8.4**, AGP 8.2.2, Kotlin 1.9.22, JDK 17
- `gradle-wrapper.jar` was manually assembled from 3 jars in the distribution:
  `gradle-wrapper-8.4.jar` (plugin) + `gradle-wrapper-shared-8.4.jar` + `gradle-cli-8.4.jar`
- Can only build on x86_64 (GitHub Actions) — AAPT2 doesn't run on ARM64

---

## Usage API

```
GET https://api.anthropic.com/api/oauth/usage
Authorization: Bearer <access_token>
anthropic-beta: oauth-2025-04-20
```

Response:
```json
{
  "five_hour":  { "utilization": 6.0,  "resets_at": "2025-11-04T04:59:59Z" },
  "seven_day":  { "utilization": 35.0, "resets_at": "2025-11-06T03:59:59Z" }
}
```

Rate limited aggressively — poll max once every 10 minutes, always cache.

---

## Color Thresholds (Widget + PoC)
| Utilization | Color    | Android color |
|-------------|----------|---------------|
| 0–50%       | Green    | `#4CAF50`     |
| 51–75%      | Yellow   | `#FFC107`     |
| 76–90%      | Red      | `#F44336`     |
| 91–100%     | Bold Red | `#B71C1C`     |

---

## Security
- Tokens stored in `EncryptedSharedPreferences` backed by Android Keystore
- `network_security_config.xml`: cleartext disabled, system trust anchors for Anthropic domains
- PKCE S256 on every auth attempt
- State nonce validated before token exchange
- Token exchange uses JSON body (NOT form-encoded)
