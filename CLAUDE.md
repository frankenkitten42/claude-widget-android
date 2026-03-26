# Claude Widget Android — Project Notes

Native Android home screen widget showing Claude API usage (5-hour and 7-day rolling
windows) with color-coded progress bars. OAuth login via Claude's own OAuth system.

**Repo:** `frankenkitten42/claude-widget-android`
**Build:** GitHub Actions only (AAPT2 is x86_64 only; dev device is ARM64 Termux)

---

## Project Status

Phase 1 (Termux:Widget bash PoC) — **complete**, working in `~/projects/claude-widget/`
Phase 2 (Native Android app) — **in progress**, OAuth working, widget displays but no data

**OAuth is fully working.** Sign-in, token exchange, and token storage all confirmed.
**Widget placement is working.** "Can't add widget" blocker resolved — was caused by
`setProgressTintList` crash (takes `ColorStateList`, not `int`) and R8 stripping classes.

Current blocker: Usage API was returning **HTTP 403** — diagnosed as User-Agent validation.
Fix pushed (User-Agent changed to `claude-code/2.1.83`). Awaiting test by user.
Also fixed: token being cleared before API attempt, misleading "Tap to sign in" on widget.
New "Test Fetch" button in app for manual diagnostics.

---

## What We Know About Claude's OAuth System

All values extracted directly from `@anthropic-ai/claude-code/cli.js` (v2.1.83):

### Client Details
| Field | Value |
|-------|-------|
| **Client ID (UUID)** | `9d1c250a-e61b-44d9-88ed-5944d1962f5e` |
| **Authorization URL (console)** | `https://platform.claude.com/oauth/authorize` |
| **Authorization URL (claude.ai)** | `https://claude.ai/oauth/authorize` |
| **Token URL** | `https://platform.claude.com/v1/oauth/token` |
| **Scopes** | `org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload` |

### Authorization URL Parameters (exact, from CLI source)
```
code=true                          ← REQUIRED first param (server rejects without it)
client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e
response_type=code
redirect_uri=https://platform.claude.com/oauth/code/callback   ← manual flow
scope=org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload
code_challenge=<base64url(sha256(verifier))>
code_challenge_method=S256
state=<base64url(random 32 bytes)>
```

### Token Exchange (from CLI source — CRITICAL DETAILS)
- **Method:** POST to `https://platform.claude.com/v1/oauth/token`
- **Headers:** `Content-Type: application/json` ONLY — do NOT send `anthropic-beta` header
- **Body:**
  ```json
  {
    "grant_type": "authorization_code",
    "code": "<auth code — just the code part, NOT the #state suffix>",
    "redirect_uri": "<same as used in auth request>",
    "client_id": "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
    "code_verifier": "<original PKCE verifier>",
    "state": "<original state nonce>"
  }
  ```

### Manual Callback Page Format
The platform callback page (`https://platform.claude.com/oauth/code/callback`) displays
the authorization code as `AUTH_CODE#STATE` (separated by `#`). The CLI splits on `#`:
```javascript
let [code, state] = input.split("#");
handleManualAuthCodeInput({authorizationCode: code, state: state});
```
Our app must also split on `#` and use only the first part as the auth code.

### PKCE Generation (from CLI source)
```javascript
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

### CLI Auth Flow (Two URLs)
The CLI generates TWO auth URLs with the same PKCE challenge/state:
1. **Manual URL** (`isManual:true`): redirect_uri = `platform.claude.com/oauth/code/callback`
2. **Automatic URL** (`isManual:false`): redirect_uri = `http://localhost:PORT/callback`

It opens the automatic URL in the browser and shows the manual URL as text fallback.
Our Android app uses the manual flow since we can't reliably intercept localhost redirects.

### Registered Redirect URIs
- `http://localhost:PORT/callback` (CLI automatic — random port, loopback server)
- `https://platform.claude.com/oauth/code/callback` (CLI manual fallback — **this is what we use**)
- `claude-widget://oauth/callback` — **NOT accepted** by the server

---

## OAuth Iteration History

### Attempt 1 — Fetch client_id from metadata URL
- **Error:** `client_id: Input should be a valid UUID`
- **Cause:** Metadata URL returned URL-format client_id, not UUID
- **Fix:** Hardcoded UUID `9d1c250a-e61b-44d9-88ed-5944d1962f5e`

### Attempt 2 — Custom scheme with correct client_id
- **Error:** `Authorization failed, Invalid request format`
- **Cause:** Missing `code=true`, wrong redirect_uri, incomplete scopes
- **Fix:** Added `code=true`, full scope list

### Attempt 3 — Local HTTP server (ServerSocket) + Chrome Custom Tab
- **Error:** `Authorization failed, Invalid request format` after clicking Authorize
- **Cause:** Unknown — possibly Chrome PNA or server-side redirect_uri validation
- **Also discovered:** Token exchange was using form-encoded body (must be JSON)

### Attempt 4 — Embedded WebView with URL interception
- **Error:** Gray screen, spinner, nothing
- **Cause:** Google blocks OAuth sign-in inside Android WebViews
- **Reverted**

### Attempt 5 — Chrome Custom Tab + `claude-widget://` custom scheme
- **Error:** `Invalid request format`
- **Cause:** `claude-widget://oauth/callback` not registered as valid redirect URI

### Attempt 6 — Manual flow with `platform.claude.com/oauth/code/callback`
- Auth URL: `https://platform.claude.com/oauth/authorize` (console endpoint)
- Redirect URI: `https://platform.claude.com/oauth/code/callback`
- Three bugs fixed in sequence:
  1. **Missing `state` in token exchange body** → "invalid request format"
  2. **Extra `anthropic-beta` header on token endpoint** → "invalid request format"
     (Token endpoint only wants `Content-Type: application/json`, NO beta header)
  3. **Not splitting `AUTH_CODE#STATE` from callback page** → "Invalid code"
     (Page shows `code#state`, must split on `#` and use only the code part)
- **Status: WORKING** — full OAuth flow confirmed

---

## Android App Architecture

### Package Structure
```
com.frankenkitten42.claudewidget/
├── auth/
│   ├── OAuthManager.kt          # PKCE flow, manual code paste, token exchange
│   └── TokenStore.kt            # EncryptedSharedPreferences wrapper
├── api/
│   └── UsageApi.kt              # Usage endpoint, token refresh
├── widget/
│   └── ClaudeUsageWidget.kt     # AppWidgetProvider + RemoteViews
├── worker/
│   └── UsageFetchWorker.kt      # WorkManager periodic fetch (10 min)
└── ui/
    └── AuthActivity.kt          # Chrome Custom Tab launch + code paste UI
```

### Key Files
| File | Purpose |
|------|---------|
| `app/src/main/AndroidManifest.xml` | Permissions, activities, widget receiver |
| `app/src/main/res/xml/appwidget_info.xml` | Widget size (3×2 cells, 250×110dp) |
| `app/src/main/res/xml/network_security_config.xml` | Cleartext disabled, HTTPS only |
| `app/src/main/res/layout/widget_claude_usage.xml` | Widget RemoteViews layout |
| `app/src/main/res/layout/activity_auth.xml` | Auth screen with code paste field |
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
anthropic-beta: oauth-2025-04-20       ← may be outdated; app now tries without first
```

Response:
```json
{
  "five_hour":  { "utilization": 6.0,  "resets_at": "2025-11-04T04:59:59Z" },
  "seven_day":  { "utilization": 35.0, "resets_at": "2025-11-06T03:59:59Z" }
}
```

Rate limited aggressively — poll max once every 10 minutes, always cache.

### Usage API Issues (diagnosed, fix pushed)
- **HTTP 403 root cause: User-Agent header**. The usage API validates the User-Agent string.
  Our app was sending `User-Agent: claude-widget/1.0` → 403.
  The bash PoC sends `User-Agent: claude-code/2.0.31` → 200.
  **Fix:** Changed to `User-Agent: claude-code/2.1.83` to match the CLI.
  The `anthropic-beta: oauth-2025-04-20` header IS still required.
- **DNS resolution failure**: Intermittent "unable to resolve host api.anthropic.com" —
  WorkManager worker firing before network is fully available. Transient.
- **"Tap to sign in" when actually signed in**: Refresh token 401 was clearing stored
  tokens before the API call could be attempted. Fixed by saving token before refresh.
- Widget shows actual error message for diagnosis (instead of generic "Offline")
- **Test Fetch button** added to app for manual diagnostic output

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
- State nonce validated from `AUTH_CODE#STATE` callback format
- Token exchange uses JSON body (NOT form-encoded), NO beta header

---

## Resolved Issues

1. **"Can't add widget" on home screen** — FIXED
   - **Root cause:** `setInt(id, "setProgressTintList", color)` in `updateWidgets` crashed
     because `setProgressTintList` expects `ColorStateList`, not `int`. The crash happened
     when the worker updated the widget right after placement, killing it.
   - **Also fixed:** Disabled R8 minification (`isMinifyEnabled = false`) to prevent
     stripping of `AppWidgetProvider` and other classes
   - Progress bars now use static tint from XML layout (dynamic coloring TBD)

2. **"Can't add widget" — APK signing** — investigated but NOT the cause
   - Added release signing config via GitHub Actions secrets
   - Unsigned debug APKs can still host widgets; signing wasn't the blocker

## Known Issues / Next Steps

1. **Usage API returning HTTP 403** — fix pushed, awaiting test
   - Root cause: `User-Agent: claude-widget/1.0` rejected by API
   - Fix: changed to `User-Agent: claude-code/2.1.83` (matching CLI)
   - `anthropic-beta: oauth-2025-04-20` is still required
   - Also fixed misleading "Tap to sign in" (token cleared before API attempt)
   - User may need to **re-sign-in** if refresh token has expired
2. **Dynamic progress bar coloring removed** — bars are static green
   - Need RemoteViews-compatible approach (can't use `setProgressTintList` via `setInt`)
3. **Re-enable R8 with proper keep rules** — once everything works
4. **Token persistence across reinstalls** — tokens in EncryptedSharedPreferences survive
   uninstall/reinstall on the same package name. Debug (`.debug` suffix) and release are
   separate apps with separate token storage.
