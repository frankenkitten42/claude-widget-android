# Claude Widget Android — Project Notes

Native Android home screen widget showing Claude usage (5-hour and 7-day rolling
windows) with color-coded progress bars. Data sourced from a Termux bash script
that reads Claude Code's own credentials.

**Repo:** `frankenkitten42/claude-widget-android`
**Build:** GitHub Actions only (AAPT2 is x86_64 only; dev device is ARM64 Termux)

---

## Project Status

Phase 1 (Termux:Widget bash PoC) — **complete**, working in `~/projects/claude-widget/`
Phase 2 (Native Android app v1 — OAuth) — **abandoned**, OAuth violates ToS
Phase 3 (Native Android app v2 — file bridge) — **working**

**Widget is live on home screen showing real usage data.**

---

## Architecture

```
[Claude Code credentials]     [Bash fetch script]       [Android widget]
~/.claude/.credentials.json → claude-usage-fetch.sh →  reads latest.json
                               ↓                        ↓
                               Writes to shared storage  WorkManager (10 min)
                               /storage/emulated/0/      reads file, updates
                               Documents/claude-usage/   RemoteViews
                               latest.json
```

The Android app makes **NO network calls**. All API communication happens in the
bash script running in Termux/proot-distro, using Claude Code's own OAuth token.

### Data Flow
1. Termux:Boot starts the fetch loop at device boot (no terminal window needed)
2. Every 10 minutes, proot-distro runs `claude-usage-fetch.sh`
3. Script reads Claude Code's token from `~/.claude/.credentials.json`
4. Script fetches `https://api.anthropic.com/api/oauth/usage` with proper headers
5. Script writes JSON to `/storage/emulated/0/Documents/claude-usage/latest.json`
6. Android WorkManager reads that file every 10 minutes
7. Widget displays usage data with color-coded progress bars

### Shared Storage Files
```
/storage/emulated/0/Documents/claude-usage/
  latest.json   — raw API response
  last_fetch    — epoch seconds of last successful fetch
  status        — "ok", "error_no_credentials", "error_auth_expired", "error_network"
```

---

## Android App Structure

### Package Structure
```
com.frankenkitten42.claudewidget/
├── data/
│   └── UsageFileReader.kt     # Reads JSON from shared storage
├── widget/
│   └── ClaudeUsageWidget.kt   # AppWidgetProvider + RemoteViews
├── worker/
│   └── UsageFetchWorker.kt    # WorkManager: reads file, updates widget
└── ui/
    └── MainActivity.kt         # Status display + setup instructions
```

### Key Files
| File | Purpose |
|------|---------|
| `app/src/main/AndroidManifest.xml` | Permissions (MANAGE_EXTERNAL_STORAGE), widget receiver, no INTERNET |
| `app/src/main/res/xml/appwidget_info.xml` | Widget size (3×2 cells, 250×110dp) |
| `app/src/main/res/layout/widget_claude_usage.xml` | Widget RemoteViews layout |
| `app/src/main/res/layout/activity_main.xml` | Status/setup screen |
| `app/build.gradle.kts` | Dependencies (no OkHttp, no security-crypto) |
| `.github/workflows/build.yml` | GitHub Actions build |
| `scripts/setup-termux.sh` | One-time Termux setup script |
| `scripts/claude-usage-boot.sh` | Termux:Boot background scheduler |

### Dependencies (v2 — minimal)
```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.0")  // WorkManager
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
```

Removed in v2: OkHttp, security-crypto (EncryptedSharedPreferences), browser (Chrome Custom Tab), coroutines.

### Build Notes
- **Gradle 8.4**, AGP 8.2.2, Kotlin 1.9.22, JDK 17
- Can only build on x86_64 (GitHub Actions) — AAPT2 doesn't run on ARM64
- Version bumped to 2.0 (versionCode 2)

---

## Bash Script

**Location:** `/root/.local/bin/claude-usage-fetch.sh` (inside proot-distro Debian)

### API Call
```bash
curl -s -w "\n%{http_code}" --max-time 15 \
    -H "Accept: application/json" \
    -H "Content-Type: application/json" \
    -H "User-Agent: claude-code/2.0.31" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "anthropic-beta: oauth-2025-04-20" \
    "https://api.anthropic.com/api/oauth/usage"
```

### Critical API Details
- **User-Agent MUST be `claude-code/*`** — API returns 403 for other user-agents
- **`anthropic-beta: oauth-2025-04-20` is required** — still a beta endpoint
- Token is read from `~/.claude/.credentials.json` → `claudeAiOauth.accessToken`
- Rate limited aggressively — poll max once every 10 minutes

### Response Format
```json
{
  "five_hour":  { "utilization": 47.0, "resets_at": "2026-03-27T02:00:00.297783+00:00" },
  "seven_day":  { "utilization": 47.0, "resets_at": "2026-03-30T05:00:00.297803+00:00" },
  "extra_usage": { ... }
}
```
App only reads `five_hour` and `seven_day`, ignores other fields.

---

## Setup Requirements

### Android App
- Install APK (sideload from GitHub Actions artifacts)
- Grant **"All files access"** permission when prompted (Android 11+ requires this
  to read from `/storage/emulated/0/Documents/`). One-time, persists across updates.

### Termux
- **Termux** — terminal emulator
- **Termux:Boot** — runs scripts at device boot without a visible terminal
- **Termux:API** — provides `termux-wake-lock` to prevent Doze from killing the fetch loop
- **proot-distro** with Debian — the fetch script runs inside the Debian environment
- **Claude Code** — must be authenticated (provides the OAuth token the script reads)

### Setup Script
Run `scripts/setup-termux.sh` from bare Termux to:
1. Verify prerequisites (proot-distro, Debian, fetch script, credentials)
2. Install the boot script to `~/.termux/boot/`
3. Do a test fetch
4. Verify the data file was created

---

## Color Thresholds
| Utilization | Color    | Android color |
|-------------|----------|---------------|
| 0–50%       | Green    | `#4CAF50`     |
| 51–75%      | Yellow   | `#FFC107`     |
| 76–90%      | Red      | `#F44336`     |
| 91–100%     | Bold Red | `#B71C1C`     |

---

## History

### v1 (OAuth-based) — abandoned
Full OAuth flow using Claude Code's client_id. Worked technically but violates ToS.
6 OAuth iteration attempts documented; final approach used manual code paste flow.
Kept in git history for reference.

### v2 (File bridge) — current, working
Removed all OAuth/API code. App reads a JSON file written by a Termux bash script.
No network permissions, no token storage, minimal dependencies.
Requires `MANAGE_EXTERNAL_STORAGE` on Android 11+ to read Documents/.

---

## Polish / Future Work
- Data shown can lag ~10 minutes (both bash fetch and widget read are on 10-min cycles)
- Dynamic progress bar coloring not yet implemented (bars are static green from XML)
- Could reduce lag by having the widget read the file more frequently than 10 min
- Termux:Boot background loop may be killed by aggressive battery optimization —
  user should disable battery optimization for Termux in Android settings
- No tap-to-refresh on the widget itself (could add a PendingIntent)
- Widget could show data age ("3m ago") instead of absolute timestamp
- Setup flow could be more automated
