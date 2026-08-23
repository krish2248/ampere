# Ampere — Battery, Charging & Thermal Diagnostics

Offline Android battery diagnostics app built for the **Samsung Galaxy S24** (works on any Android 10+ device).

- **Live power**: volts, amps, watts (battery-side), 60 s sparklines
- **Battery health**: design capacity vs measured full capacity, health %, how it was derived
- **Temperature**: battery °C live + thermal zones + session graphs
- **Throttling**: thermal status (NONE→SHUTDOWN) + headroom gauge + banners
- **Time estimates**: time-to-full (with CV-correction), time remaining on battery
- **Charging quality**: A–F grade per charge session with explainable deductions and explicit thresholds
- **History**: charge/discharge sessions, charts, CSV export

## Hard rules (from the spec)

1. No network. No analytics. No accounts. Works in airplane mode forever.
2. Never fabricate a number — unavailable values render as `—` with an explanation.
3. Every displayed value is `MEASURED`, `ESTIMATED` (`~` prefix), or `UNAVAILABLE`.
4. No root required; root paths are optional enhancements only.
5. Charts are hand-drawn Compose `Canvas`. No third-party UI/chart libraries.

## Tech stack (pinned)

| Thing | Version |
|---|---|
| compileSdk / targetSdk | 35 |
| minSdk | 29 (Android 10) |
| Gradle | 8.9 |
| AGP | 8.7.3 |
| Kotlin | 2.1.0 |
| KSP | 2.1.0-1.0.29 |
| Compose BOM | 2025.01.00 |
| Package | `dev.ampere.battery` |

Room 2.6.1 · DataStore 1.1.1 · WorkManager 2.10.0 · Navigation-Compose 2.8.5 · Coroutines 1.9.0

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Install on device:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Project docs

- [`03-battery-app-SPEC.md`](03-battery-app-SPEC.md) — **single source of truth**, full product/engineering spec
- [`SESSION.md`](SESSION.md) — cross-session state: progress tracker, session log, where to resume
