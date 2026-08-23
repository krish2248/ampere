# SPEC 03 — "Ampere" : Battery, Charging & Thermal Diagnostics (Android, Kotlin, Compose)

> **THIS DOCUMENT IS THE SINGLE SOURCE OF TRUTH.**
> You are an autonomous coding agent. Build the entire app from this file.
> **DO NOT ASK THE USER ANY QUESTIONS.** Every decision is made below.
> **Read §1 (Reality Check) before writing a single line.** This app's hardest problem is that Android
> does not expose half of what users expect. Guessing is forbidden; degrading gracefully is mandatory.

---

## 0. AGENT OPERATING RULES

1. Kotlin + Compose + Room + DataStore. No Java. No third-party libraries beyond the pinned list.
2. **Never fabricate a number.** If a value cannot be read, the UI must show `—` and a tap-through explanation. It is far better to say "not available on this device" than to print a plausible-looking lie.
3. Every hardware read is wrapped in `runCatching { }` and returns a nullable. A single OEM quirk must never crash the app.
4. Every displayed value carries a **confidence level**: `MEASURED`, `ESTIMATED`, `UNAVAILABLE`. Render `ESTIMATED` values with a "~" prefix and a small info dot.
5. No network. No analytics. No accounts. The app must work in airplane mode forever.
6. No root required. Root paths are *optional enhancements* only, always behind a try/catch, never a requirement.
7. Follow the file tree in §5 exactly. Pin versions in §3 exactly.
8. Build must pass `./gradlew :app:assembleDebug` and run without crashing on API 29 and API 35 emulators before you report done.

---

## 1. REALITY CHECK — WHAT ANDROID ACTUALLY EXPOSES

This determines the whole design. Read it fully.

### 1.1 Reliably available on every device, no permission
| Value | Source | Unit | Notes |
|---|---|---|---|
| Battery percentage | `ACTION_BATTERY_CHANGED` → `EXTRA_LEVEL` / `EXTRA_SCALE` | % | integer, usually 0–100 |
| Battery voltage | `EXTRA_VOLTAGE` | **millivolts** | some OEMs report microvolts — sanity-check (§8.2) |
| Battery temperature | `EXTRA_TEMPERATURE` | **tenths of °C** | divide by 10. This is the *battery* sensor, not the SoC. |
| Charging status | `EXTRA_STATUS` | enum | CHARGING / DISCHARGING / FULL / NOT_CHARGING / UNKNOWN |
| Plug type | `EXTRA_PLUGGED` | enum | AC / USB / WIRELESS / DOCK(API 33+) |
| Health | `EXTRA_HEALTH` | enum | GOOD / OVERHEAT / DEAD / OVER_VOLTAGE / COLD / UNSPECIFIED_FAILURE. **This is the charging IC's crude flag, not a capacity health %.** |
| Technology | `EXTRA_TECHNOLOGY` | String | "Li-ion", "Li-poly" |
| Present | `EXTRA_PRESENT` | Boolean | |

### 1.2 Usually available, must be validated per device
| Value | Source | Unit per contract | Reality |
|---|---|---|---|
| Instantaneous current | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` | µA | **Sign is inverted on many OEMs. Some report mA instead of µA. Some return `Integer.MIN_VALUE` or 0 always.** Must be calibrated — §8.3. |
| Average current | `BATTERY_PROPERTY_CURRENT_AVERAGE` | µA | Often equals CURRENT_NOW verbatim, or is always 0. |
| Remaining charge | `BATTERY_PROPERTY_CHARGE_COUNTER` | µAh | **The single most valuable property in this app.** Usually correct. Sometimes only updates in 1% steps. |
| Capacity % | `BATTERY_PROPERTY_CAPACITY` | % | Same as EXTRA_LEVEL normally |
| Energy counter | `BATTERY_PROPERTY_ENERGY_COUNTER` | nWh | Frequently `Long.MIN_VALUE` (unsupported) |
| Time-to-full | `BatteryManager.computeChargeTimeRemaining()` (API 28+) | ms | Returns **-1** on most devices. Treat -1 as "unavailable" and fall back to our own estimator. |
| State of health | `BATTERY_PROPERTY_STATE_OF_HEALTH` (API 34+) | % | Only some 2023+ devices implement it. Guard by SDK **and** by value sanity (1..100). |

### 1.3 Thermal
- `PowerManager.getCurrentThermalStatus()` — **API 29+**. Returns `THERMAL_STATUS_NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN`. This *is* Android's official throttling signal. Reliable where implemented; some devices report NONE forever.
- `PowerManager.addThermalStatusListener(executor, listener)` — API 29+, push updates.
- `PowerManager.getThermalHeadroom(forecastSeconds)` — **API 30+**. Returns a float where **1.0 = at the throttling threshold**, > 1.0 = over. Returns `NaN` if unsupported **or if called more often than once per second** — rate-limit to one call per 2 s or it silently gives you garbage.
- `/sys/class/thermal/thermal_zone*/type` + `/temp` — readable on many devices, blocked by SELinux on others. Best-effort only.
- **There is no public API for CPU frequency or per-core throttling.** `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` is unreadable on Android 10+ on most devices. Do not build a feature that depends on it; read it opportunistically and hide the card if it fails.

### 1.4 Design capacity — the hard one
There is **no public API** for the battery's original design capacity. Resolution order (implement all six, in this order, stop at the first success):

1. **`config_batteryCapacity` framework resource** (works on a majority of devices, no reflection into hidden APIs — it's a plain resource lookup):
   ```kotlin
   val res = Resources.getSystem()
   val id = res.getIdentifier("config_batteryCapacity", "integer", "android")
   val mAh = if (id != 0) res.getInteger(id) else 0     // usually design capacity in mAh
   ```
   Sanity gate: accept only if `mAh in 500..30000`.
2. **`/sys/class/power_supply/battery/charge_full_design`** (µAh) — try also `/sys/class/power_supply/bms/charge_full_design`. Readable on some devices, `EACCES` on most modern ones. Divide by 1000 for mAh.
3. **`PowerProfile` reflection** — `Class.forName("com.android.internal.os.PowerProfile")` → `getBatteryCapacity()`. **Expect this to be blocked** by non-SDK interface restrictions on Android 9+. Attempt inside `runCatching`, log, move on. Do not build anything on the assumption it works.
4. **`BATTERY_PROPERTY_STATE_OF_HEALTH` (API 34+) combined with a measured full capacity**: `design ≈ measuredFull / (soh / 100)`. Only usable once we have a measured full-capacity estimate.
5. **Bundled device database** — a small `assets/battery_db.json` mapping `Build.DEVICE` / `Build.MODEL` → design mAh, for the 100–200 most common devices. Ship it with maybe 40 entries and structure it so entries are easy to add:
   ```json
   { "entries": [ { "device": "raven", "model": "Pixel 6 Pro", "designMah": 5003 } ] }
   ```
6. **Manual entry.** A settings field "Design capacity (mAh)" the user fills from the spec sheet. Prompt for it once, non-blockingly, if 1–5 all failed. This is the honest fallback and it must exist.

Record which source won in `DesignCapacitySource` and show it in the UI ("from device database", "you entered this", "read from system").

### 1.5 Cycle count
`/sys/class/power_supply/battery/cycle_count` exists on many devices but is usually unreadable without root on Android 10+. **No public API exists.** Read best-effort; if unavailable, show `—` and offer an *app-tracked* alternative: accumulate `Δcharge / designCapacity` across sessions to produce an "equivalent full cycles since you installed Ampere" number, clearly labelled as app-tracked and starting from zero.

### 1.6 Things you CANNOT do — do not attempt, do not fake
- Read the **charger/adapter-side** watts. We only ever see the **battery-side** power (V × I at the cell). A "30 W charger" delivers roughly 22–27 W to the battery after conversion losses. State this in the UI.
- Read the USB PD negotiated contract (voltage/amperage profile) without root.
- Read true internal resistance, exact SoH from the fuel gauge (except via the API-34 property), manufacture date, or battery serial on most devices.
- Detect the *cable* quality directly. We can only infer it from voltage sag + achieved current.
- Get per-app battery usage — `BATTERY_STATS` is a `signature|privileged` permission. Not obtainable.
- Modify charging behaviour, cap charging at 80%, or slow charging. **Not possible without root.** Do not add such a feature.

---

## 2. PRODUCT DEFINITION

**Ampere** is an offline battery diagnostics app with four screens: **Now**, **Health**, **History**, **Settings**. It samples battery state continuously (optionally in the background), computes derived metrics, records charge/discharge sessions, and grades charging quality against clear thresholds.

### Feature list (maps to what the user asked for)
| User asked | Feature |
|---|---|
| "how much watts/volt" | Live power card: V, A, W (battery-side), with 60-second sparkline and session min/avg/max |
| "battery percentage" | Level %, plus fine-grained % derived from `charge_counter` when available |
| "battery capacity original vs current after years of use" | Health screen: design capacity (§1.4) vs measured full capacity (§9), health %, confidence, and how it was derived |
| "phone heat in celsius" | Battery temperature °C live + thermal zones (best-effort) + temperature graph over the session |
| "phone throttle" | Thermal status (NONE→SHUTDOWN) + thermal headroom gauge + a banner when throttling starts |
| "how long will it take to charge" | Time-to-full: system estimate if available, otherwise our own rate-based estimator with a stated confidence interval |
| "how much watt/volt is too bad" | Charging grade (A–F) + explicit thresholds table + live warnings (§11) |

### Non-goals
Charging control, root-only features as requirements, wakelock detection, per-app usage, ads, cloud, widgets (v1), Wear OS.

---

## 3. TECH STACK — PINNED

- compileSdk 35, targetSdk 35, **minSdk 29** (Android 10 — required for `getCurrentThermalStatus`; anything older is <2% of devices in 2026 and would double the guard count).
- Gradle 8.9, AGP 8.7.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29, Compose BOM 2025.01.00.
- Deps: `core-ktx 1.15.0`, `lifecycle-* 2.8.7`, `activity-compose 1.9.3`, Compose BOM (`ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `material-icons-extended`), `navigation-compose 2.8.5`, `room-runtime/ktx/compiler 2.6.1`, `datastore-preferences 1.1.1`, `work-runtime-ktx 2.10.0`, `kotlinx-coroutines-android 1.9.0`, `desugar_jdk_libs 2.1.4`.
- `namespace`/`applicationId` = `dev.ampere.battery`.
- Charts are **hand-drawn with Compose `Canvas`** — no MPAndroidChart, no Vico.

---

## 4. `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <!-- Only used to open the exemption settings screen; not auto-requested. -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:name=".AmpereApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:enableOnBackInvokedCallback="true"
        android:theme="@style/Theme.Ampere">

        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".monitor.MonitorService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="continuous_battery_and_thermal_telemetry_for_the_user" />
        </service>

        <receiver android:name=".monitor.PowerConnectionReceiver" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.ACTION_POWER_CONNECTED" />
                <action android:name="android.intent.action.ACTION_POWER_DISCONNECTED" />
                <action android:name="android.intent.action.BATTERY_LOW" />
                <action android:name="android.intent.action.BATTERY_OKAY" />
            </intent-filter>
        </receiver>

        <receiver android:name=".monitor.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

**Note on `ACTION_BATTERY_CHANGED`**: it **cannot** be declared in the manifest (it is registered-receiver-only). Always register it at runtime with `context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))`. On API 33+ pass an export flag: `ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`.

---

## 5. FILE TREE

```
app/src/main/java/dev/ampere/battery/
├── AmpereApp.kt
├── MainActivity.kt
├── AppContainer.kt
│
├── hw/                                  // everything that touches hardware
│   ├── BatteryIntentSource.kt           // sticky + registered ACTION_BATTERY_CHANGED -> Flow<BatteryIntentData>
│   ├── BatteryPropertySource.kt         // BatteryManager.getIntProperty/getLongProperty wrappers
│   ├── ThermalSource.kt                 // PowerManager thermal status/headroom + listener
│   ├── SysfsReader.kt                   // best-effort /sys reads, all nullable
│   ├── DesignCapacityResolver.kt        // the 6-step chain from §1.4
│   ├── DeviceDb.kt                      // assets/battery_db.json loader
│   └── HwModels.kt                      // raw data classes
│
├── domain/
│   ├── model/
│   │   ├── BatterySample.kt
│   │   ├── LiveStatus.kt
│   │   ├── ChargeSession.kt
│   │   ├── HealthEstimate.kt
│   │   ├── ChargeGrade.kt
│   │   ├── Confidence.kt
│   │   └── Thresholds.kt
│   ├── calc/
│   │   ├── UnitCalibrator.kt            // µA vs mA, sign convention
│   │   ├── PowerCalc.kt                 // V*I -> W, smoothing
│   │   ├── CapacityEstimator.kt         // measured full capacity from ΔCC/ΔSOC
│   │   ├── TimeToFullEstimator.kt
│   │   ├── TimeRemainingEstimator.kt    // discharge runtime
│   │   ├── ChargeGrader.kt              // A-F grade + reasons
│   │   ├── ThermalEvaluator.kt
│   │   └── SessionSegmenter.kt          // splits samples into charge/discharge sessions
│   └── repo/
│       ├── SampleRepository.kt
│       ├── SessionRepository.kt
│       ├── HealthRepository.kt
│       └── CalibrationRepository.kt
│
├── data/
│   ├── db/ { AmpereDatabase.kt, dao/…, entity/… }
│   └── prefs/ SettingsRepository.kt
│
├── monitor/
│   ├── MonitorService.kt                // foreground sampler
│   ├── Sampler.kt                       // the sampling loop, adaptive interval
│   ├── PowerConnectionReceiver.kt
│   ├── BootReceiver.kt
│   ├── PurgeWorker.kt                   // WorkManager daily cleanup + capacity re-estimation
│   └── Notifications.kt
│
└── ui/
    ├── theme/ { Color.kt, Theme.kt, Type.kt }
    ├── nav/ { Routes.kt, AmpereNavHost.kt }
    ├── component/ { StatCard.kt, BigGauge.kt, Sparkline.kt, LineChart.kt, GradeBadge.kt,
    │                ThermalBar.kt, ValueRow.kt, InfoDot.kt, EmptyState.kt, WarningBanner.kt }
    ├── now/ { NowScreen.kt, NowViewModel.kt, NowState.kt }
    ├── health/ { HealthScreen.kt, HealthViewModel.kt }
    ├── history/ { HistoryScreen.kt, HistoryViewModel.kt, SessionDetailScreen.kt }
    └── settings/ { SettingsScreen.kt, SettingsViewModel.kt, DiagnosticsScreen.kt }
```

---

## 6. DESIGN SYSTEM

Dark-first, instrument-panel feel. Numbers are the hero; everything else recedes.

```kotlin
// DARK (default)
val D_Bg        = Color(0xFF0C0D10)
val D_Card      = Color(0xFF15171C)
val D_CardAlt   = Color(0xFF1C1F26)
val D_Line      = Color(0xFF262A33)
val D_Text      = Color(0xFFEDEFF3)
val D_TextDim   = Color(0xFF8B909C)
val D_Good      = Color(0xFF35C77B)   // healthy / fast / cool
val D_Warn      = Color(0xFFE7A93C)   // caution
val D_Bad       = Color(0xFFE5484D)   // danger
val D_Info      = Color(0xFF4C8DF6)   // charging / neutral highlight
val D_Chart     = Color(0xFF6FD3FF)

// LIGHT
val L_Bg        = Color(0xFFF6F7F9)
val L_Card      = Color(0xFFFFFFFF)
val L_CardAlt   = Color(0xFFF0F2F5)
val L_Line      = Color(0xFFE1E4EA)
val L_Text      = Color(0xFF14161A)
val L_TextDim   = Color(0xFF636A76)
val L_Good      = Color(0xFF14884E)
val L_Warn      = Color(0xFFB0761A)
val L_Bad       = Color(0xFFC42A2F)
val L_Info      = Color(0xFF2C6BD4)
val L_Chart     = Color(0xFF1D8BB8)
```
- Theme modes: System / Light / Dark (setting), same crossfade approach as the other apps.
- Big numeric readouts: `FontFamily.Monospace`, tabular figures, 40sp Medium for the hero value, 13sp label above in `TextDim` uppercase letterSpacing 0.8sp.
- Cards: radius 18.dp, 1.dp `Line` border, no elevation, 16.dp inner padding, 12.dp gaps.
- Every value that is `ESTIMATED` gets a 12.dp `InfoDot` that opens a bottom sheet explaining exactly how it was derived and why it may be wrong.

---

## 7. DATA MODEL

### 7.1 `BatterySample` (one row per sample)

| field | type | unit |
|---|---|---|
| `id` | Long PK autoGenerate | |
| `tsWall` | Long | epoch ms |
| `tsElapsed` | Long | `SystemClock.elapsedRealtime()` |
| `levelPct` | Int | 0–100 |
| `voltageMv` | Int | mV (normalised) |
| `currentUa` | Int? | µA, **normalised sign: positive = into the battery** |
| `chargeCounterUah` | Int? | µAh |
| `tempTenthC` | Int | tenths °C |
| `status` | Int | BatteryManager status enum |
| `plugged` | Int | 0=none,1=AC,2=USB,4=WIRELESS,8=DOCK |
| `health` | Int | BatteryManager health enum |
| `thermalStatus` | Int | -1 if unknown |
| `thermalHeadroom` | Float? | |
| `screenOn` | Boolean | from `PowerManager.isInteractive` |
| `sessionId` | Long? | FK to sessions |

Indices on `tsWall`, `sessionId`.

### 7.2 `ChargeSession`

| field | type | notes |
|---|---|---|
| `id` | Long PK | |
| `kind` | String | `CHARGE` or `DISCHARGE` |
| `startTs` / `endTs` | Long / Long? | |
| `startPct` / `endPct` | Int / Int? | |
| `startCcUah` / `endCcUah` | Int? / Int? | |
| `plugType` | Int | dominant plug type |
| `peakPowerMw` / `avgPowerMw` | Int | battery-side |
| `peakTempTenthC` / `avgTempTenthC` | Int | |
| `minVoltageMv` / `maxVoltageMv` | Int | |
| `energyMwh` | Int | integrated ∫P dt |
| `durationMs` | Long | |
| `thermalMaxStatus` | Int | worst thermal status seen |
| `grade` | String | A/B/C/D/F |
| `gradeReasons` | String | JSON array of reason codes |
| `screenOnMs` | Long | |
| `capacityEstimateUah` | Int? | if this session produced a usable estimate |
| `capacityEstimateWeight` | Float | 0..1 quality of the estimate |

### 7.3 `HealthEstimate`
`id, ts, measuredFullUah, designUah, healthPct, method (DELTA_CC | SOH_API | ENERGY_COUNTER), confidence, sampleSpanPct, tempAvgTenthC`

### 7.4 Retention
- Samples: keep **raw** for 7 days, then downsample to 1-per-minute for 30 days, then 1-per-15-minutes for 365 days. Implement in `PurgeWorker` (daily, `WorkManager`, `Constraints.Builder().setRequiresBatteryNotLow(false)`, flex 6 h).
- Sessions and health estimates: keep forever (they are tiny).
- Hard cap the samples table at 500 000 rows; if exceeded, drop the oldest.

---

## 8. SAMPLING & NORMALISATION

### 8.1 Sampling intervals (adaptive — this is a battery app, it must not be a battery hog)
| Condition | Interval |
|---|---|
| App in foreground | **1 s** |
| Background + charging + screen off | 10 s |
| Background + discharging + screen on | 30 s |
| Background + discharging + screen off | 60 s |
| Thermal status ≥ SEVERE | 5 s (regardless) |
| Monitoring disabled in settings | no sampling; foreground screens still sample while visible |

The foreground `Flow` is driven by the registered `ACTION_BATTERY_CHANGED` receiver **plus** a 1 s poll (the broadcast only fires on level/status change, roughly once per percent — far too slow for a live watt readout).

### 8.2 Voltage normalisation
```
raw = EXTRA_VOLTAGE
mv = when {
    raw in 2000..6000       -> raw            // already mV, normal Li-ion single cell
    raw in 2_000_000..6_000_000 -> raw / 1000 // µV
    raw in 2..6             -> raw * 1000     // V (rare, seen on a few devices)
    else                    -> null           // unusable
}
```
Multi-cell packs (some gaming phones, dual-cell fast charging) report the *pack* voltage ≈ 8.4 V max. Widen the plausible band to `2000..9500` mV and set a `isDualCell` flag when steady-state voltage exceeds 5000 mV; the thresholds in §11 then use per-cell values (`mv / 2`).

### 8.3 Current normalisation & calibration (`UnitCalibrator`) — implement carefully

Two unknowns per device: **scale** (µA vs mA) and **sign** (positive = charging or discharging).

**Scale detection (preferred, physical method):**
Over a window of ≥ 60 s during charging where `chargeCounterUah` changed:
```
impliedUa = (ccEnd - ccStart) * 3_600_000 / (tEnd - tStart)   // µAh -> µA over ms
ratio     = impliedUa / medianAbs(reportedCurrent)
scale     = when {
    ratio in 0.5f..2.0f      -> 1        // reported already µA
    ratio in 500f..2000f     -> 1000     // reported in mA -> multiply by 1000
    else                     -> null     // inconclusive, retry next session
}
```
**Scale fallback (heuristic, used until the physical method succeeds):**
if `medianAbs(reportedCurrent) < 15_000` while charging on AC, assume mA. (15 000 µA = 15 mA, implausible for AC charging.)

**Sign detection:**
Collect the median reported current across ≥ 20 samples where `status == BATTERY_STATUS_CHARGING` **and** plug != 0. If that median is negative → `invertSign = true`. Also cross-check with `chargeCounter` direction (rising ⇒ charging). If they disagree, trust `chargeCounter`.

Persist `{scale, invertSign, calibratedAt, method}` in DataStore keyed by `Build.FINGERPRINT` (re-calibrate after an OS update). Expose a "Recalibrate" button in Settings → Diagnostics.

**Normalised output convention used everywhere else in the app: `currentUa > 0` means current flowing INTO the battery (charging).**

### 8.4 Smoothing
Raw current is noisy (±15% sample to sample). Display an **exponential moving average**: `ema = ema + α(x - ema)` with `α = 0.25` at 1 Hz. Keep the raw value available in Diagnostics. Never smooth the values you store in the DB — smooth only for display.

### 8.5 Power
```
powerMw = voltageMv * currentUa / 1_000_000     // mV * µA = nW*10^-... => see below
```
Careful with units: `V(volts) × I(amps) = W`. With `voltageMv` (mV) and `currentUa` (µA):
`W = (voltageMv / 1000) * (currentUa / 1_000_000)` = `voltageMv * currentUa / 1e9`.
So **`powerMw = voltageMv * currentUa / 1_000_000L`** (use `Long` arithmetic; `4400 * 3_000_000 = 1.32e10` overflows `Int`).
Display as `W` with 2 decimals when ≥ 1 W, else `mW` with 0 decimals.

---

## 9. CAPACITY & HEALTH ESTIMATION (`CapacityEstimator`)

### 9.1 Primary method — ΔChargeCounter / ΔSOC
Within a **single continuous session** (no reboot, no plug/unplug gap > 3 min, no missing samples > 2 min):

```
fullCapacityUah = (ccEnd - ccStart) * 100 / (socEnd - socStart)
```
Accept an estimate only if **all** of these hold:
- `abs(socEnd - socStart) >= 25` (percentage points) — below this the quantisation error dominates
- `chargeCounter` actually moved monotonically in the expected direction
- temperature stayed within `15..40 °C` and varied by `< 12 °C` across the window
- the session was not interrupted
- resulting value is within `0.4×..1.3×` the design capacity (else discard as garbage)

Quality weight `w = clamp((Δsoc - 25) / 55, 0.1, 1.0) * tempFactor` where `tempFactor = 1.0` for 20–30 °C, tapering to 0.5 at 15 °C or 40 °C.

**Final reported capacity = weighted median of the last 10 accepted estimates** (weighted median, not mean — one bad estimate should not move it). Require **at least 3** accepted estimates before showing a number; before that show "Learning — needs ~3 more charge cycles" with a progress indicator.

### 9.2 Secondary — `BATTERY_PROPERTY_STATE_OF_HEALTH` (API 34+)
If available and in `1..100`, show it directly as "Reported by system" with `Confidence.MEASURED`. If we also have a design capacity, cross-display: `measuredFull ≈ design * soh / 100`. If our own estimate and the system's differ by more than 8 percentage points, show both and say so — do not silently pick one.

### 9.3 Tertiary — energy counter
If `BATTERY_PROPERTY_ENERGY_COUNTER` is supported, the same ΔEnergy/ΔSOC math yields mWh; convert with the session's average voltage. Lowest priority; most devices don't support it.

### 9.4 Health percentage
```
healthPct = 100 * measuredFullUah / designUah   (clamped to 0..110)
```
Bands and copy:
| health | band | message |
|---|---|---|
| ≥ 90% | Excellent | "Essentially like new." |
| 80–89% | Good | "Normal wear. Most batteries hit 80% around 500–800 full cycles." |
| 70–79% | Fair | "Noticeably reduced runtime. Replacement is reasonable but not urgent." |
| 60–69% | Poor | "Consider replacing the battery." |
| < 60% | Very poor | "Replace the battery. Expect sudden shutdowns." |
| > 100% | Above rated | "Reported capacity exceeds the rated design value — common when the design figure is 'typical' vs 'minimum'. Not an error." |

Always print underneath: the method used, the number of estimates it's based on, and the spread (min–max) of those estimates.

### 9.5 App-tracked cycle equivalent
`cycles += max(0, ΔchargeCounterUah) / designUah` accumulated across every charging session since install. Show as "≈ 42.3 equivalent full cycles tracked by Ampere (since 3 Feb 2026)". Never claim it is the manufacturer cycle count.

---

## 10. TIME ESTIMATES

### 10.1 Time to full (`TimeToFullEstimator`)
1. Ask the system first: `batteryManager.computeChargeTimeRemaining()` (API 28+). If `> 0`, show it, labelled "system estimate", `Confidence.MEASURED`.
2. Otherwise compute ourselves, using the **charge-rate over the trailing 3 minutes**:
   ```
   ratePctPerMs = (socNow - socThen) / (tNow - tThen)
   naiveMs      = (100 - socNow) / ratePctPerMs
   ```
   If `chargeCounter` is available prefer coulombs (much smoother):
   ```
   remainingUah = fullCapacityUah - ccNow
   avgUa        = trailing 3-min EMA of current
   naiveMs      = remainingUah * 3_600_000 / avgUa
   ```
3. **Apply a CV/CC correction.** Li-ion charging is constant-current until ~70–80% SOC, then constant-voltage with exponentially decaying current. A naive linear estimate is badly optimistic near the top. Use a piecewise multiplier on the remaining time:

   | SOC now | multiplier applied to remaining-time estimate |
   |---|---|
   | < 50% | 1.15 |
   | 50–70% | 1.30 |
   | 70–80% | 1.55 |
   | 80–90% | 2.10 |
   | 90–95% | 2.80 |
   | ≥ 95% | 3.60 |

   Rationale to show in the info sheet: the last 10% typically takes as long as the middle 40%.
4. Clamp to `1 min .. 24 h`. Show as a range: `±20%` when SOC < 80%, `±35%` above (the CV phase is device-specific).
5. Also show **"time to 80%"** separately — that is the number people actually plan around.
6. Update at most once every 10 s to stop the number dancing.

### 10.2 Time remaining on battery (`TimeRemainingEstimator`)
- Preferred: `BatteryManager` has no public discharge-time API on all versions — compute from the trailing 15-minute average drain in µA and the remaining charge counter.
- Show **two** figures: "at the current rate" (last 15 min) and "at your typical rate" (median drain across the last 7 days of discharge sessions, split by screen-on vs screen-off proportion). The second one is far more useful and should be the headline.
- Formula for the typical estimate:
  ```
  remainingUah  = ccNow  (or fullCapacity * soc/100 if cc unavailable)
  avgScreenOnUa / avgScreenOffUa from history
  expectedRatio = user's trailing 7-day screen-on fraction
  blendedUa     = ratio*avgScreenOnUa + (1-ratio)*avgScreenOffUa
  ms            = remainingUah * 3_600_000 / blendedUa
  ```
- Show `—` until at least 3 discharge sessions are recorded.

---

## 11. THRESHOLDS — "what is too bad" (this is a headline feature; hardcode these exact numbers)

All thresholds live in `domain/model/Thresholds.kt` as constants so they are auditable in one place.

### 11.1 Battery temperature (the `EXTRA_TEMPERATURE` sensor)
| Range °C | State | UI | Copy |
|---|---|---|---|
| < 0 | `COLD` | blue | "Too cold to charge safely. Charging may be blocked by the phone." |
| 0–9 | `CHILLY` | blue | "Cold. Charging will be slow; this is normal and protective." |
| 10–34 | `NORMAL` | green | "Normal." |
| 35–39 | `WARM` | green/amber | "Warm but fine. Common while fast charging." |
| 40–42 | `HOT` | amber | "Hot. Sustained time here accelerates capacity loss." |
| 43–45 | `VERY_HOT` | orange | "Very hot. Take the case off, stop gaming while charging." |
| > 45 | `CRITICAL` | red | "Critical. The phone will throttle or stop charging. Unplug and let it cool." |

Also track **time spent above 40 °C** per session and lifetime, and show it on the Health screen — heat is the single biggest driver of capacity loss and this is genuinely actionable.

### 11.2 Battery voltage (per cell; divide the reading by 2 if `isDualCell`)
| mV | State | Copy |
|---|---|---|
| < 3200 | `CRITICAL_LOW` | "Deeply discharged. Charge now; sitting here damages the cell." |
| 3200–3499 | `LOW` | "Low." |
| 3500–4199 | `NORMAL` | "Normal operating range." |
| 4200–4399 | `HIGH_NORMAL` | "Near full — normal for a fully charged battery." |
| 4400–4480 | `HIGH` | "High. Normal on phones rated to 4.45 V, but a full battery held here ages faster." |
| > 4480 | `OVER` | "Above typical limits. If this is sustained, treat the reading as suspect or the charger as faulty." |
Additionally flag **voltage sag**: if voltage drops more than 250 mV within 5 s while discharging under load at > 1 A, mark "high internal resistance — an aging-battery symptom".

### 11.3 Charging power (battery-side watts — remind the user this is not the adapter rating)
| W | Class | Copy |
|---|---|---|
| < 2.5 | `TRICKLE` | "Barely charging. Usually a bad cable, dirty port, or a low-power USB source." |
| 2.5–4.9 | `SLOW` | "Standard USB speed (~5 V 0.5–1 A). Fine overnight, slow otherwise." |
| 5–9.9 | `NORMAL` | "Normal charging." |
| 10–17.9 | `FAST` | "Fast charging." |
| 18–29.9 | `VERY_FAST` | "Very fast charging." |
| ≥ 30 | `ULTRA` | "Ultra-fast charging. Expect noticeable heat." |
Reference for the info sheet: USB SDP 2.5 W, CDP 7.5 W, DCP 10 W, QC/PD tiers from 15 W upward. **State plainly: a 30 W adapter usually delivers ~22–27 W to the cell; the rest is conversion loss. Wireless charging typically lands at 55–75% of the pad's rating.**

### 11.4 Warning conditions (each raises a `WarningBanner` and is stored on the session)
| code | condition | severity |
|---|---|---|
| `HIGH_TEMP` | temp ≥ 43 °C for ≥ 60 s | high |
| `EXTREME_TEMP` | temp ≥ 46 °C | critical |
| `THROTTLING` | thermal status ≥ MODERATE | medium |
| `SEVERE_THROTTLING` | thermal status ≥ SEVERE | high |
| `SLOW_CHARGE` | plugged on AC, power < 4 W for ≥ 3 min, SOC < 80% | medium |
| `UNSTABLE_CURRENT` | current std-dev > 45% of mean over 60 s while charging | medium — "cable or port may be loose" |
| `VOLTAGE_SAG` | see §11.2 | medium |
| `HEALTH_LOW` | health < 80% | info |
| `HEALTH_CRITICAL` | health < 65% | high |
| `OVERCHARGE_HOLD` | held at 100% and plugged for > 6 h, repeatedly | info — "Long periods at 100% accelerate wear." |
| `DEEP_DISCHARGE` | dropped below 5% more than 3 times in 7 days | info |
| `BAD_HEALTH_FLAG` | `EXTRA_HEALTH` is DEAD / OVER_VOLTAGE / UNSPECIFIED_FAILURE | critical |

### 11.5 Charge grade (`ChargeGrader`) — A–F, computed at session end
Start at 100 points, subtract:
- peak temp > 40 °C: −(peakTemp − 40) × 4 points
- minutes spent above 43 °C: −1.5 per minute (cap −25)
- worst thermal status: LIGHT −2, MODERATE −6, SEVERE −15, CRITICAL −25
- avg power < 4 W while on AC: −12
- `UNSTABLE_CURRENT` fired: −10
- session included > 30 min held at 100% while plugged: −8
- started below 10% SOC: −5
Grade: `A ≥ 90, B ≥ 78, C ≥ 65, D ≥ 50, F < 50`. Always list the deductions verbatim in the session detail so the grade is explainable, never a black box.

---

## 12. SCREENS

### 12.1 Now (home)
Vertical scroll, cards in this order:

1. **Hero ring.** A 220.dp circular gauge: the arc shows SOC; the center shows `47%` at 44sp mono; under it, one line: "Charging · 18.4 W · 4h 12m to full" or "On battery · ~7h 20m left". The ring animates its sweep with `animateFloatAsState(tween(600, easing = FastOutSlowInEasing))`. Ring color: green ≥ 50, amber 20–49, red < 20; blue-tinted while charging. Draw with `Canvas` + `drawArc(useCenter = false, style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round))`.
2. **Power row** — three `StatCard`s side by side: **Voltage** (`4.312 V`), **Current** (`+2.84 A`), **Power** (`12.25 W`). Each has a 40.dp sparkline of the last 60 s underneath. Current shows sign explicitly with `+` (charging) / `−` (discharging).
3. **Temperature card** — big °C value, state chip from §11.1, and a 3-hour line chart. Add a second line for the hottest available `/sys/class/thermal` zone if any are readable, labelled with its `type` string.
4. **Thermal / throttle card** — a 6-segment bar for `NONE→SHUTDOWN` with the current segment lit; below it, the headroom value as a horizontal bar with a marker at 1.0 labelled "throttling threshold"; text explains what the current status means for performance. If the device always reports NONE, show a small note after 24 h of observation: "This device does not report thermal status."
5. **Charging details card** (only when plugged) — plug type, charge class from §11.3, current-vs-SOC curve for this session, estimated time to 80% and to 100% with the ± range, energy delivered so far (mWh), and the live grade preview.
6. **Battery info card** — level, charge counter (`3,841 mAh of ~4,982 mAh`), technology, `EXTRA_HEALTH` flag translated to plain English, status, screen-on since unplug.
7. **Warnings** — any active warning from §11.4 renders as a `WarningBanner` pinned directly under the hero ring, colored by severity, dismissible for the session.

Sampling on this screen is 1 Hz. Use `collectAsStateWithLifecycle` so it stops when backgrounded.

### 12.2 Health
1. **Capacity comparison** — a horizontal double-bar: design capacity (dim, full width) vs measured full capacity (colored, proportional), with both mAh values and the health % as a big number.
2. **Confidence block** — method used, estimate count, spread, and "how this was calculated" expandable text. If design capacity came from manual entry or the device DB, say so with an edit affordance.
3. **Health over time** — line chart of `HealthEstimate` history (x = date, y = health %), with a linear-regression trend line and, if the trend is meaningful (≥ 6 points spanning ≥ 30 days), a projection: "at this rate you'll reach 80% around March 2027". Label the projection as a rough extrapolation.
4. **Cycle equivalent** (§9.5) and total energy cycled.
5. **Heat exposure** — total hours above 40 °C and above 43 °C, per week bar chart. This is the most actionable health screen element.
6. **Habits** — % of charging sessions that ended above 95%, average unplug SOC, number of sub-10% discharges, average charge power. Under each, one line of neutral guidance (e.g. "Keeping the charge between roughly 20% and 85% reduces wear; whether the convenience trade-off is worth it is your call.") — informative, not nagging.
7. **Design capacity source** row with an **Edit** button that opens a numeric input.

### 12.3 History
- Segmented toggle: **Charges** / **Discharges**.
- List of sessions, newest first, grouped by day. Each row: grade badge (A–F, colored), start–end time, SOC range with a mini bar (`12% → 100%`), duration, avg/peak power, peak temp.
- Tap → **Session detail**: full-width multi-series chart (SOC, power, temperature on a shared time axis with two Y axes — SOC/temp left, power right), the grade breakdown with every deduction listed, warnings that fired, thermal timeline strip, and totals (energy mWh, avg W, time above 40 °C).
- Long-press → delete session (with UNDO).
- A summary header: "Last 30 days: 41 charges, avg grade B, avg peak temp 39.4 °C".
- Export button → writes a CSV of samples/sessions via `ACTION_CREATE_DOCUMENT`.

### 12.4 Settings
- **Theme** (System/Light/Dark), **Units** (°C/°F — store Celsius always, convert at render; user asked for Celsius so it is the default), **Show mW below 1 W** toggle.
- **Background monitoring** — master switch. When on, `MonitorService` runs with a persistent low-importance notification. Explain the trade-off honestly: "Sampling every 10–60 s costs roughly 0.5–1% battery per day."
- **Persistent notification content** — choose: percentage only / percentage + watts / percentage + watts + temperature. Implemented as a custom notification with `setOnlyAlertOnce(true)`, updated at most every 15 s.
- **Alerts** — toggles + thresholds for: full-charge alert (at N%, default 85), high-temperature alert (default 43 °C), low-battery alert (default 15%), throttling alert, slow-charge alert. Each writes to a separate notification channel.
- **Design capacity** — current value, source, manual override field.
- **Calibration** — shows detected current scale and sign, when it was calibrated, and a Recalibrate button.
- **Data** — retention settings, DB size, "Export CSV", "Delete all data".
- **Diagnostics screen** (§12.5).
- **About** — version, and a short honest paragraph: what the app can and cannot measure (a condensed §1.6).

### 12.5 Diagnostics screen (developer/debug, but ship it — it is what makes bug reports possible)
A raw dump table, refreshed at 1 Hz, of every source with its raw value, normalised value, and availability:
```
EXTRA_LEVEL                47            ok
EXTRA_VOLTAGE              4312 mV       ok (raw 4312)
CURRENT_NOW                -2840000      ok (raw), normalised +2840000 µA  [sign inverted, scale ×1]
CURRENT_AVERAGE            Integer.MIN   unavailable
CHARGE_COUNTER             2341000 µAh   ok
ENERGY_COUNTER             Long.MIN      unavailable
STATE_OF_HEALTH (API34)    —             not supported (SDK 33)
computeChargeTimeRemaining -1            unavailable
Thermal status             MODERATE (2)  ok
Thermal headroom           0.83          ok
/sys/.../charge_full_design  EACCES      unavailable
config_batteryCapacity     4982 mAh      ok  ← design capacity source
```
Plus a "Copy diagnostics" button that puts this text on the clipboard.

---

## 13. MONITOR SERVICE

- Started only when background monitoring is enabled. `startForeground()` within 5 s with channel `monitor` (`IMPORTANCE_MIN`, silent, ongoing).
- The loop lives in `Sampler`: a coroutine on `Dispatchers.Default` that takes a sample, writes it, updates the current session, evaluates warnings, and delays by the adaptive interval from §8.1.
- Batch DB writes: buffer samples in memory and flush every 10 samples or 30 s, whichever comes first (`@Insert` a `List<BatterySampleEntity>` in one transaction).
- `PowerConnectionReceiver` closes the open session and opens a new one on `ACTION_POWER_CONNECTED` / `DISCONNECTED`, and immediately raises the sample rate for 60 s so the start of a charge is captured densely.
- `SessionSegmenter` rules: a session ends when plug state changes, when status flips CHARGING↔DISCHARGING for ≥ 30 s, when a gap ≥ 10 min appears in samples, or on reboot (`tsElapsed` goes backwards).
- On boot: restart the service if enabled; mark any session left open as ended at the last sample's timestamp with `endedByReboot = true`.
- **Handle the foreground-service start restrictions**: from a `BOOT_COMPLETED` receiver you may start a `specialUse` FGS, but from a background broadcast on API 31+ you generally may not. Use `WorkManager` with an expedited `OneTimeWorkRequest` as the restart path and start the service from the worker; catch `ForegroundServiceStartNotAllowedException` and fall back to a `PeriodicWorkRequest` sampler at 15-minute intervals with a clear note in Settings that resolution is reduced.
- Never hold a wakelock. Sampling piggybacks on the system waking for other reasons; a missed sample is acceptable.

### 13.1 Notification channels
| id | name | importance |
|---|---|---|
| `monitor` | Background monitoring | MIN |
| `alerts_charge` | Charge level alerts | DEFAULT |
| `alerts_temp` | Temperature alerts | HIGH |
| `alerts_health` | Health & charging quality | LOW |

---

## 14. EDGE CASES

| # | Case | Behaviour |
|---|---|---|
| 1 | `CURRENT_NOW` returns `Integer.MIN_VALUE` or 0 always | Mark current & power `UNAVAILABLE`; hide the power card and show one line: "This device doesn't report charging current." Everything else keeps working. |
| 2 | `CHARGE_COUNTER` unavailable | Fall back to SOC-based capacity/time math with `Confidence.ESTIMATED` and a wider ± range. |
| 3 | Design capacity unresolvable | Show a one-time non-blocking prompt to enter it; until then show health as "—" and everything else normally. Never guess a number. |
| 4 | Device reports SOC in steps of 5% | Detect (all observed deltas divisible by 5) and require `Δsoc ≥ 40` for capacity estimates; note it in Diagnostics. |
| 5 | Reboot mid-session | `tsElapsed` decreases ⇒ close the session, start a new one, exclude the boundary from capacity estimates. |
| 6 | Doze / app standby | Sampling stops; gaps are expected. `SessionSegmenter` handles gaps ≥ 10 min. Charts render gaps as breaks, never as straight interpolated lines. |
| 7 | User revokes notification permission | Monitoring still runs (FGS notification is exempt from POST_NOTIFICATIONS on some versions, but assume it may not show); alerts silently no-op; Settings shows the state. |
| 8 | Wireless charging | `EXTRA_PLUGGED == BATTERY_PLUGGED_WIRELESS`. Grade thresholds shift: `SLOW_CHARGE` warning uses < 3 W instead of < 4 W, and add a note that wireless is inherently hotter and less efficient. |
| 9 | Dual-cell / multi-cell packs | §8.2. Voltage thresholds use per-cell values. |
| 10 | Battery replaced by the user | Health estimates suddenly jump. Detect a step change > 15% between consecutive estimates and offer "Did you replace the battery? Reset health history." |
| 11 | Emulator | Almost every property is fake or missing. The app must still run and show `—` everywhere. Test on an emulator explicitly. |
| 12 | Extremely cold or hot readings (< −20 °C or > 80 °C) | Treat as sensor error; discard the sample, don't alarm the user. |
| 13 | Clock changed by the user | Sessions keyed on `tsElapsed` for durations; `tsWall` only for display. |
| 14 | 100 000+ samples in a chart range | Downsample for rendering with LTTB (largest-triangle-three-buckets) to ~400 points before drawing. Do it off the main thread. |
| 15 | Dark/light switch | Charts re-render with theme colors; no cached bitmaps of charts. |
| 16 | Talkback | Every gauge/chart has a `contentDescription` summarising its value in words ("Battery 47 percent, charging at 18.4 watts, 4 hours 12 minutes to full"). |
| 17 | Landscape | All screens are vertical scroll lists; charts get taller/wider. No separate landscape layouts. |
| 18 | RTL | Charts always render left-to-right (time axis), everything else mirrors. |

---

## 15. ACCEPTANCE CRITERIA

- [ ] Builds debug + release; runs on API 29 and API 35 without crashing.
- [ ] On a real device, plugged in: voltage, current, and watts update at ~1 Hz and match a hardware USB meter within ±10% (battery-side vs adapter-side difference explained in-app).
- [ ] Current sign is correct: **positive while charging** on the test device, verified after auto-calibration.
- [ ] Unit calibration correctly identifies µA vs mA (test by forcing the opposite in Diagnostics and confirming the physical method fixes it).
- [ ] Temperature in °C matches the system's own battery screen.
- [ ] Thermal status changes are observed at least once (induce it: run a heavy 3D benchmark while fast charging) and the banner appears.
- [ ] Time-to-full is within 15% of actual on a full 20%→100% charge, and the CV correction visibly prevents the "1 minute remaining for 20 minutes" failure mode.
- [ ] After 3 qualifying charge cycles, a health % appears with its method and confidence stated.
- [ ] Design capacity resolves via at least one automatic source on the test device, or the manual prompt appears.
- [ ] Every number in the UI is either measured, marked estimated with an explanation, or shown as `—`. **Zero fabricated values.**
- [ ] Background monitoring for 24 h costs < 1.5% of battery (compare with the system battery usage screen) and produces a continuous session history.
- [ ] Sessions segment correctly across plug/unplug and reboot.
- [ ] Charts render 24 h of data in < 100 ms after downsampling; no main-thread jank when scrolling History.
- [ ] Export CSV opens correctly in a spreadsheet.
- [ ] Diagnostics screen shows raw + normalised values for every source listed in §1.
- [ ] No crash in a 10-minute monkey run.
- [ ] App requests only `POST_NOTIFICATIONS` at runtime, and only when the user enables alerts.

---

## 16. TEST PLAN

**Unit tests (pure Kotlin, no Android):**
1. `UnitCalibratorTest` — feed synthetic sample streams: (a) µA positive-charging, (b) µA inverted, (c) mA inverted, (d) all zeros. Assert scale and sign detection, and that (d) yields "inconclusive" rather than a wrong answer.
2. `PowerCalcTest` — 4400 mV × 3 000 000 µA = 13 200 mW; assert `Long` math with no overflow; assert mW/W formatting boundaries.
3. `CapacityEstimatorTest` — synthetic sessions: valid 20→90% run, an invalid 10-point run, a temperature-excursion run, a garbage run producing 2× design. Assert acceptance/rejection and the weighted median across 10 estimates.
4. `TimeToFullEstimatorTest` — assert the CV multiplier is applied per band; assert a 95% SOC estimate is at least 3× the naive value; assert clamping.
5. `ChargeGraderTest` — construct sessions hitting each deduction; assert exact scores and grade boundaries at 90/78/65/50.
6. `SessionSegmenterTest` — plug changes, 12-minute gap, reboot (elapsed goes backwards), status flapping for 10 s (must NOT split) vs 40 s (must split).
7. `ThresholdsTest` — every boundary value in §11.1–11.3 maps to the right state (test 34/35, 39/40, 42/43, 45/46 etc.).
8. `LttbTest` — downsampling 100 000 → 400 points preserves the min and max of the series.

**Instrumented:**
9. Room DAO tests: insert 50 000 samples, query a 24 h window under 50 ms with the index in place.
10. `BatteryIntentSourceTest` on an emulator with `adb shell dumpsys battery set level 42` / `set ac 1` / `set temp 420` / `unplug` / `reset` — assert the flow emits the changed values. **Use these adb commands throughout manual testing; they are the fastest way to exercise every branch.**

Useful adb commands to script:
```
adb shell dumpsys battery set level 15
adb shell dumpsys battery set status 2      # 2=charging 3=discharging 5=full
adb shell dumpsys battery set ac 1
adb shell dumpsys battery set temp 435      # 43.5 °C
adb shell dumpsys battery unplug
adb shell dumpsys battery reset
adb shell cmd thermalservice override-status 3   # force SEVERE (API 30+)
adb shell cmd thermalservice reset
```

---

## 17. BUILD ORDER

**Phase 0** — Skeleton: Gradle, manifest, theme, nav with 4 stub screens, `AppContainer`.

**Phase 1** — `hw/` layer complete: `BatteryIntentSource`, `BatteryPropertySource`, `ThermalSource`, `SysfsReader`, `DesignCapacityResolver`, `DeviceDb`. **Build the Diagnostics screen (§12.5) FIRST** — before any pretty UI. You cannot debug this app without it, and it validates every source on the real device.

**Phase 2** — `UnitCalibrator` + `PowerCalc` + `CalibrationRepository`. Verify signs and units on a real device.

**Phase 3** — Room schema, `Sampler`, `MonitorService`, `SessionSegmenter`, boot/power receivers, retention worker.

**Phase 4** — Now screen: hero ring, power row + sparklines, temperature card, thermal card, charging card, battery info card.

**Phase 5** — `CapacityEstimator`, `HealthRepository`, Health screen with all charts.

**Phase 6** — `TimeToFullEstimator`, `TimeRemainingEstimator`, wired into the Now screen with ranges and confidence.

**Phase 7** — `ChargeGrader`, warnings, alert notifications, History + Session detail screens.

**Phase 8** — Settings, CSV export, manual design-capacity entry, accessibility, ProGuard, icon (adaptive: `#0C0D10` background, a green lightning-bolt-in-a-battery foreground).

**Phase 9** — §15 checklist, top to bottom.

---

## 18. PRE-ANSWERED QUESTIONS

1. **Do I need root?** No. Root paths are optional bonuses only; the app must be fully useful without it.
2. **Can I show the charger's rated watts?** No — only battery-side power. Say so in the UI.
3. **Can I cap charging at 80%?** No. Impossible without root/OEM APIs. Do not add the feature; instead add the "full charge alert at N%" notification.
4. **Which permission do I need to read the battery?** None. Only `POST_NOTIFICATIONS` (for alerts) and the foreground-service permissions.
5. **Should the service run always?** Off by default. The user enables it. Explain the cost honestly.
6. **Which charting library?** None. Hand-drawn `Canvas`. `Sparkline` and `LineChart` are ~150 lines each.
7. **What if the device reports nothing useful?** The app degrades to level/voltage/temperature/status only and says so plainly. That is a valid, shipping outcome.
8. **Room or files for samples?** Room. Batch inserts, indices on `tsWall` and `sessionId`.
9. **Should I use `WorkManager` or a foreground service for sampling?** Foreground service for continuous sampling; `WorkManager` for the daily purge/re-estimate and as the FGS-restart fallback path.
10. **Celsius or Fahrenheit?** Store Celsius, default to Celsius (the user asked for °C), offer °F as a display setting.
11. **What's the minimum API?** 29. Do not lower it (thermal APIs) and do not raise it.
12. **Do I need a device database of every phone?** No — ~40 entries is enough as a fallback tier; the manual entry field is the real safety net.
13. **How accurate should health be?** Honest, not precise. Show the method, the sample count and the spread. A range like "88% ± 4 (5 estimates)" is better than a fake "88.3%".
14. **Package name?** `dev.ampere.battery`.
15. **Should I show anything I can't verify?** No. §0 rule 2 overrides every other consideration in this document.
