# Claude Widget Android

Native Android home screen widget displaying Claude Code usage (5-hour and 7-day
rolling windows) with color-coded progress bars.

**Repo:** `frankenkitten42/claude-widget-android`
**Status:** Complete and working
**Build:** GitHub Actions only (AAPT2 requires x86_64; dev device is ARM64 Termux)

---

## Architecture

```
[Claude Code credentials]     [Bash fetch script]        [Android widget]
~/.claude/.credentials.json → claude-usage-fetch.sh  →  reads latest.json
                               writes to shared storage   WorkManager (15 min)
                               every 5 min via cron       updates RemoteViews
```

The Android app makes **no network calls**. A bash script running in Termux/proot-distro
fetches usage data using Claude Code's own OAuth token and writes JSON to shared storage.
The widget reads that file.

### Data Flow
1. Logging into proot auto-starts cron and runs an immediate fetch
2. Cron runs `claude-usage-fetch.sh` every 5 minutes
3. Script reads Claude Code's token from `~/.claude/.credentials.json`
4. Script fetches `https://api.anthropic.com/api/oauth/usage`
5. Script writes JSON to `/storage/emulated/0/Documents/claude-usage/latest.json`
6. Android WorkManager reads the file every 15 minutes
7. Widget displays usage with progress bars and reset countdowns

### Shared Storage Files
```
/storage/emulated/0/Documents/claude-usage/
  latest.json   — raw API response
  last_fetch    — epoch seconds of last successful fetch
  status        — "ok", "error_no_credentials", "error_auth_expired", "error_network"
```

---

## Setup

### Android
1. Install APK (sideload from GitHub Actions artifacts)
2. Grant **"All files access"** when prompted (required on Android 11+ to read Documents/)
3. Add widget to home screen

### Termux
Requires: **Termux**, **proot-distro** with Debian, **Claude Code** (authenticated)

Data fetching starts automatically when you log into proot — cron is auto-started
via `.bashrc` and an immediate fetch runs in the background.

Optional: install **Termux:Boot** + **Termux:API** and run `scripts/setup-termux.sh`
for automatic fetching at device boot (without opening a terminal).

---

## API Details

```bash
curl -s --max-time 15 \
    -H "User-Agent: claude-code/2.0.31" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "anthropic-beta: oauth-2025-04-20" \
    "https://api.anthropic.com/api/oauth/usage"
```

- **User-Agent MUST be `claude-code/*`** — returns 403 otherwise
- **`anthropic-beta: oauth-2025-04-20` is required**
- Token: `~/.claude/.credentials.json` → `claudeAiOauth.accessToken`
- Rate limited — max once every 5–10 minutes

---

## Project Structure

```
app/src/main/kotlin/com/frankenkitten42/claudewidget/
├── data/UsageFileReader.kt      # Reads JSON from shared storage
├── widget/ClaudeUsageWidget.kt  # AppWidgetProvider + RemoteViews
├── worker/UsageFetchWorker.kt   # WorkManager: reads file, updates widget
└── ui/MainActivity.kt           # Status display + setup instructions

scripts/
├── setup-termux.sh              # One-time Termux:Boot setup
└── claude-usage-boot.sh         # Background fetch scheduler for Termux:Boot
```

### Dependencies
```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.0")
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
```

### Build
- Gradle 8.4, AGP 8.2.2, Kotlin 1.9.22, JDK 17
- Release signing via GitHub Actions secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

---

## Color Thresholds
| Utilization | Color  | Hex       |
|-------------|--------|-----------|
| 0–50%       | Green  | `#4CAF50` |
| 51–75%      | Yellow | `#FFC107` |
| 76–90%      | Red    | `#F44336` |
| 91–100%     | Deep Red | `#B71C1C` |

---

## Known Limitations
- Data freshness depends on having a proot session open (cron runs inside proot)
- Widget reads file every 15 min (Android WorkManager minimum for periodic work)
- Progress bar colors are static green (dynamic coloring not implemented)
- Android 11+ requires MANAGE_EXTERNAL_STORAGE for Documents/ access
- Using Claude Code's OAuth token via the usage API is undocumented/unsupported
