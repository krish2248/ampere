# SESSION.md — Ampere Project State

> **Read this file FIRST at the start of every session. Update it at the END of every session, then commit & push to GitHub.**
> This is the continuity file. The spec (`03-battery-app-SPEC.md`) is the engineering truth; this file is the *where are we* truth.

---

## 1. What this project is

**Ampere** (`dev.ampere.battery`) - an offline battery / charging / thermal diagnostics app for Android.
Kotlin + Compose + Room + DataStore. No network, no analytics, no accounts, no root required.

Four screens: **Now**, **Health**, **History**, **Settings** (+ Diagnostics debug screen).
Headline features: live V/A/W readouts, battery health % (design vs measured capacity), battery
temperature in Celsius, throttle status + headroom, time-to-full with CV correction, A-F charging
grades with explainable deductions.

Full spec: **[`03-battery-app-SPEC.md`](03-battery-app-SPEC.md)** (769 lines - single source of truth
for all technical decisions; see §0 agent rules, §1 reality check, §11 thresholds, §17 build order).

## 2. Target device

| | |
|---|---|
| Phone | Samsung Galaxy S24 (user's daily phone) |
| Models | SM-S921F/DS (global, Exynos 2400) or SM-S921U (US, Snapdragon 8 Gen 3) - confirm via Diagnostics screen |
| Design capacity | 4000 mAh (rated ~3900 mAh typical) |
| Charging | 25 W wired USB-PD 3.0 PPS, 15 W wireless, adaptive fast charging |
| OS | Shipped with Android 14 / One UI 6.1; user may be on One UI 7/8 by Aug 2026 |
| App minSdk | 29 - S24 (API 34+) comfortably supported |

**Samsung/One UI quirks to verify in Diagnostics screen (expected, not confirmed):**
- `CURRENT_NOW` unit (uA vs mA) and sign unknown until calibrated (spec §8.3 handles it)
- `charge_full_design` sysfs usually blocked by SELinux/Knox -> design capacity likely from
  `config_batteryCapacity` (~4000) or the bundled device DB
- `cycle_count` unreadable without root -> app-tracked equivalent cycles instead
- One UI aggressively kills background services -> battery-optimization exemption matters (spec §13)

## 3. Environment (this dev machine)

| | |
|---|---|
| OS | Windows (win32, PowerShell 5.1) |
| Working dir | `C:\Users\sonik\Desktop\battery` |
| JDK | Temurin OpenJDK 17.0.20 (OK) |
| Android SDK | `C:\Users\sonik\AppData\Local\Android\Sdk` (`ANDROID_HOME` set) |
| Platforms installed | android-21, **android-35 (auto-installed by AGP, Session 1)**, android-34, android-36, android-36.1 |
| Build tools | 34.0.0, 36.0.0, 36.1.0, 37.0.0 |
| Gradle | wrapper dist download is BLOCKED for java.exe on this machine - use the manually extracted install: `& "$env:USERPROFILE\.gradle\install\gradle-8.9\bin\gradle.bat" ...` (see §11) |
| Git / gh | git 2.52.0, gh CLI 2.83.2 logged in as **krish2248** |

## 4. Workflow protocol (every session)

1. Read `SESSION.md` -> resume at "Next steps" (§9).
2. Do the work per spec §17 phase order. Do not skip ahead of the current phase.
3. Verify: `.\gradlew.bat :app:assembleDebug` must pass before claiming progress.
4. Update §7 checklist and append to §8 session log (date, done, decisions, blockers).
5. Commit everything and push to GitHub. A session is not complete until pushed.

## 5. GitHub

- Repo: **https://github.com/krish2248/ampere** (created in Session 1)
- Branch: `main`. Commit style: short imperative ("Phase 0 skeleton").
- End-of-session push: `git add -A; git commit -m "..."; git push`

## 6. Key pinned versions (do not drift)

Gradle 8.9 - AGP 8.7.3 - Kotlin 2.1.0 - KSP 2.1.0-1.0.29 - Compose BOM 2025.01.00 -
core-ktx 1.15.0 - lifecycle 2.8.7 - activity-compose 1.9.3 - navigation-compose 2.8.5 -
room 2.6.1 (Phase 3+) - datastore 1.1.1 (Phase 3+) - work 2.10.0 (Phase 3+) - coroutines 1.9.0 -
compile/targetSdk 35 - minSdk 29 - desugar_jdk_libs 2.1.4

---

## 7. Progress tracker

Legend: `[x]` done, `[~]` partial, `[ ]` not started

### Phase 0 - Skeleton
- [x] Git repo initialized, .gitignore, README.md
- [x] SESSION.md continuity file created
- [x] GitHub repo created and pushed (https://github.com/krish2248/ampere)
- [x] Gradle files (settings, root build, app build, version catalog, gradle.properties)
- [~] Gradle wrapper set up - scripts + properties committed, but wrapper dist download is blocked for java.exe on this machine; regenerate wrapper jar from the official dist next session (§9 step 2)
- [x] Manifest per spec §4 + stub classes it references (AmpereApp, MainActivity, MonitorService, receivers)
- [x] Theme (Color/Theme/Type/Palette from §6) + adaptive launcher icon (no PNGs needed at minSdk 29)
- [x] Nav graph (Routes, AmpereNavHost) + 4 stub screens + AppContainer placeholder
- [x] First successful `:app:assembleDebug` - GREEN (Session 2). Palette.kt Color import was the only
  compile error. Debug APK at `app\build\outputs\apk\debug\app-debug.apk` (~57 MB), not yet on a device.
- [~] Wrapper still not canonical - `gradlew.bat` cannot download the dist (firewall blocks java.exe);
  keep using the direct gradle.bat path (§11) until wrapper is regenerated or firewall fixed.
  Attempted `wrapper --gradle-version 8.9` in Session 2 - fails on same network block.

### Phase 1 - hw/ layer + Diagnostics screen FIRST
- [ ] BatteryIntentSource, BatteryPropertySource, ThermalSource, SysfsReader
- [ ] DesignCapacityResolver (6-step chain, spec §1.4), DeviceDb (assets/battery_db.json)
- [ ] Diagnostics screen (spec §12.5) built BEFORE pretty UI
- [ ] Verify every hardware source on the real S24 via adb

### Phase 2 - Calibration
- [ ] UnitCalibrator (uA-vs-mA scale + sign detection, spec §8.3), PowerCalc (Long math!), CalibrationRepository
- [ ] Verify sign/scale on device

### Phase 3 - Data layer + service
- [ ] Room schema (spec §7), Sampler (adaptive intervals, spec §8.1), MonitorService (FGS specialUse)
- [ ] SessionSegmenter, PowerConnectionReceiver, BootReceiver, PurgeWorker (retention spec §7.4)

### Phase 4 - Now screen
- [ ] Hero ring, power row + sparklines, temperature card, thermal card, charging card, battery info card

### Phase 5 - Capacity & Health
- [ ] CapacityEstimator (dCC/dSOC, spec §9.1), HealthRepository, Health screen

### Phase 6 - Time estimates
- [ ] TimeToFullEstimator (CV correction table, spec §10.1), TimeRemainingEstimator

### Phase 7 - Grading + History
- [ ] ChargeGrader (point deductions, spec §11.5), warnings (§11.4), alert notifications, History + Session detail

### Phase 8 - Polish
- [ ] Settings, CSV export, manual design-capacity entry, accessibility, ProGuard, icon polish

### Phase 9 - Acceptance
- [ ] Spec §15 checklist top-to-bottom on the S24, spec §16 test plan

---

## 8. Session log

### Session 2 - Tue Aug 25 2026
**Done:**
- Rebuilt `:app:assembleDebug` with the direct gradle-8.9 binary: **BUILD SUCCESSFUL in 59s**
  (35 tasks). Palette.kt Color import was the last remaining compile error - Phase 0 is code-complete.
- Verified APK exists: `app\build\outputs\apk\debug\app-debug.apk` (56.9 MB, built Aug 24).
- Updated this file; committed and pushed.

**Decisions:**
- Stopped after wrap-up per user: no Phase 1 work today ("we do everything tomorrow").
- Wrapper regeneration deferred again - not blocking while the direct binary path works.

**Blockers / notes:**
- None new. Same standing gotchas in §11. Device install + wrapper regen are first up next session.

### Session 1 - Sun Aug 23 2026
**Done:**
- Read full spec (769 lines). Understood scope, phases, hard rules.
- Initialized local git repo (`main` branch); wrote `.gitignore`, `README.md`, this `SESSION.md`.
- Created GitHub repo **krish2248/ampere** (public), pushed initial commit (35 files).
- Built Phase 0 skeleton: version catalog + Gradle files, manifest per spec §4, theme (§6 colors,
  mono hero typography), bottom-nav with 4 stub screens, AppContainer, adaptive launcher icon
  (battery + bolt vector on #0C0D10).
- Fought through a broken network path for the first build:
  - Wrapper could not download the Gradle dist: java.exe cannot reach services.gradle.org
    (PowerShell can; no proxy configured - likely firewall blocking java.exe).
  - Workaround: downloaded gradle-8.9-bin.zip via PowerShell, extracted to
    `%USERPROFILE%\.gradle\install\gradle-8.9` and invoke that binary directly. Works.
- First direct build run: AGP auto-installed **Android SDK Platform 35**, all AndroidX deps
  resolved from Google/Maven (so Java reaches those hosts fine - only services.gradle.org fails).
- Fixed missing `android.useAndroidX=true` (added `gradle.properties`).
- Second run got all the way to Kotlin compile; failed on one missing import
  (`androidx.compose.ui.graphics.Color` in Palette.kt) - fixed after the build, not re-verified yet.

**Decisions:**
- Repo named `ampere` (matches app id `dev.ampere.battery`); public visibility (user's choice);
  local folder stays `battery`.
- Launcher icon = adaptive-icon XML only (minSdk 29 >= 26, no legacy PNGs required).
- Room/DataStore/Work deps deferred to Phase 3 to keep Phase 0 lean; KSP plugin added then too.
- compileSdk stays 35 per spec even though only platforms 34/36 existed locally - AGP installed 35 automatically.

**Blockers / notes:**
- Build is NOT green yet: Palette.kt import fixed but unverified; expect possibly another small error or two.
- Wrapper jar currently in repo came from the Gradle repo tag (newer refactor, different dist-dir scheme).
  Regenerate canonical 8.9 wrapper files next session (see §9 step 2) so `gradlew` works normally.
- TODO: confirm S24 model variant (Exynos vs Snapdragon) via Diagnostics screen once app runs.

---

## 9. Next steps (resume here)

1. (Optional, quick) Regenerate the canonical wrapper so future sessions can use `.\gradlew.bat`:
   run `& "$env:USERPROFILE\.gradle\install\gradle-8.9\bin\gradle.bat" wrapper --gradle-version 8.9`
   in the project, commit the regenerated files. If `gradlew` still tries to download the dist and
   fails, keep using the direct path (§11) - or allow java.exe through the firewall.
2. Enable USB debugging on the S24 -> `adb devices` -> `adb install -r app\build\outputs\apk\debug\app-debug.apk`
   -> confirm it launches with 4 tabs + bottom nav.
3. Start **Phase 1**: hw/ layer + Diagnostics screen first (spec §17 says build Diagnostics before any UI).
   - BatteryIntentSource, BatteryPropertySource, ThermalSource, SysfsReader
   - DesignCapacityResolver (6-step chain, spec §1.4), DeviceDb (assets/battery_db.json)
   - Diagnostics screen per spec §12.5; verify every hardware source on the real S24 via adb.
4. Add S24 entry (`device: "s24"`, `model: "SM-S921*"`, designMah 4000) to `assets/battery_db.json`.
5. Phase 2 after that: UnitCalibrator + PowerCalc + CalibrationRepository (spec §8.3).

## 10. Build & test commands

```powershell
# Build
.\gradlew.bat :app:assembleDebug
# Install on S24
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Fake battery states for testing (spec §16 - fastest way to exercise branches)
adb shell dumpsys battery set level 15
adb shell dumpsys battery set status 2      # 2=charging 3=discharging 5=full
adb shell dumpsys battery set ac 1
adb shell dumpsys battery set temp 435      # 43.5 C
adb shell dumpsys battery unplug
adb shell dumpsys battery reset

# Force thermal status (API 30+)
adb shell cmd thermalservice override-status 3   # SEVERE
adb shell cmd thermalservice reset

# Real S24 readings (sanity reference while testing)
adb shell dumpsys battery
```

## 11. Known issues / gotchas

- **java.exe on this machine cannot reach services.gradle.org** (PowerShell can; no proxy set;
  likely Windows Firewall blocking java.exe). Google Maven + Maven Central work fine from Java.
  Workaround: invoke Gradle directly:
  `& "$env:USERPROFILE\.gradle\install\gradle-8.9\bin\gradle.bat" <tasks>` (zip already downloaded).
- Harmless build warning: "This version only understands SDK XML versions up to 3..." - cmdline-tools
  newer than AGP expects; ignore.
- `ACTION_BATTERY_CHANGED` cannot go in the manifest - runtime registration only (spec §4 note).
- Power math MUST use Long: `powerMw = voltageMv * currentUa / 1_000_000L` (Int overflows).
- Thermal headroom: rate-limit to one call per 2 s or it silently returns NaN (spec §1.3).
- Never smooth stored values - EMA is display-only (spec §8.4).
- PowerShell 5.1: no `&&`; use `if ($?) { }` chaining.
- Kotlin compile errors print as noisy PowerShell "NativeCommandError" walls - the real message
  is the `e: file:///...` lines.

## 12. Conventions

- Package `dev.ampere.battery`. File tree exactly as spec §5.
- Kotlin only (no Java). Compose only (no XML layouts). Hand-drawn Canvas charts.
- Every hardware read wrapped in `runCatching`, returns nullable, never crashes on OEM quirks.
- Confidence levels everywhere: MEASURED / ESTIMATED (~ prefix) / UNAVAILABLE (- dash). Never fabricate numbers.

