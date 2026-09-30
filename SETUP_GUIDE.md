# 📘 Comprehensive Technical Guide: Android Health Connect to Google Sheets (2026 Standard)

---

## 1. 2026 Architecture & Why Google Fit REST API is Obsolete

### Google Fit vs. Health Connect:
- **Google Fit APIs Deprecation**: Google officially announced the deprecation of all Google Fit APIs (including REST API, Android Sensors API, Recording API, and History API) with complete phase-out in favor of **Health Connect**.
- **The New Android Standard**: Health Connect is an on-device data store. On **Android 14+**, Health Connect is part of the core Android OS framework. On Android 9 through 13, it is backed by the Google Play Services Health Connect module.
- **Can Health Connect be accessed directly from Apps Script?**  
  **No.** Health Connect is an **on-device** encrypted SQLite repository residing on the Android phone. Google does not host your Health Connect data in a public cloud REST endpoint. Therefore, an on-device client (a lightweight Android app or background worker) is strictly necessary to read from Health Connect and transmit the data to your cloud target (Google Sheets).

### The Recommended 2026 Pipeline:
```
┌─────────────────────────────────┐
│     Android Phone Sensors       │
│               +                 │
│ Wearables (Pixel / Galaxy Watch)│
└────────────────┬────────────────┘
                 │ (Writes raw records)
                 ▼
┌─────────────────────────────────┐
│     Android Health Connect      │
│  (System Framework on Android)  │
└────────────────┬────────────────┘
                 │ (aggregate() with StepsRecord.COUNT_TOTAL)
                 ▼
┌─────────────────────────────────┐
│    Fitness Sync Android App     │
│   (WorkManager Background Sync) │
└────────────────┬────────────────┘
                 │ (HTTPS POST + Secret Token)
                 ▼
┌─────────────────────────────────┐
│  Google Apps Script (Web App)   │
│   (Serverless Upsert Gateway)   │
└────────────────┬────────────────┘
                 │ (SpreadsheetApp API)
                 ▼
┌─────────────────────────────────┐
│      Google Sheets Tracker      │
│     (Daily Log + Dashboard)     │
└─────────────────────────────────┘
```

---

## 2. Health Connect Permissions & Duplicate Step Prevention

### Required Permissions:
- Health Connect uses granular permissions declared in AndroidManifest:
  ```xml
  <uses-permission android:name="android.permission.health.READ_STEPS" />
  <uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />
  ```
- In code, it is requested via Jetpack Health Connect (`androidx.health.connect:connect-client:1.1.0` stable):
  ```kotlin
  val permissions = setOf(
      HealthPermission.getReadPermission(StepsRecord::class),
      HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
  )
  ```

### How `StepsRecord.COUNT_TOTAL` Prevents Double Counting:
If you walk with your phone in your pocket while also wearing a connected smartwatch, both devices will record step intervals to Health Connect.
- If you were to manually query raw records via `readRecords()`, you would get overlapping timestamps and potentially double count steps.
- **Google's aggregation engine**: When you execute `healthConnectClient.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), ...))`, Health Connect automatically inspects the recording data origins, calculates the intersection of intervals, and provides a single, unified, deduplicated total for that local calendar day.

---

## 3. Why Google Apps Script Web App is Superior to Direct Sheets API

| Feature | Direct Android Sheets API | Apps Script Web App (Our Choice) |
|---|---|---|
| **Google Cloud Setup** | Heavy (OAuth 2.0 Client ID, SHA-1, Scopes) | None (1-click script deployment) |
| **Token Expiry** | Unverified apps expire tokens every 7 days | **Never expires** (Runs under your script identity) |
| **Android Code Size** | Google Sign-in SDK + Play Services + OAuth | Lightweight HTTP Client (OkHttp) |
| **Sheet Formula Protection** | Complex API cell range overwrites | Upsert logic cleanly handled in JavaScript |

With an Apps Script Web App deployed as **"Execute as: Me"** and protected by your custom **`API_SECRET_TOKEN`**, your Android app only needs to send a simple JSON POST:
```json
{
  "token": "MyFitnessSecretToken_2026",
  "date": "2026-09-30",
  "steps": 10450
}
```
The script handles checking column A for `"2026-09-30"`, updating only the Steps column without overwriting your manual weight or notes, and inserting a new row if the date doesn't exist yet!

---

## 4. Google Sheets Structure & Mathematical Formulas

### Tab 1: `Daily Log`
Headers in Row 1:
- **A**: `Date` (Format: `yyyy-mm-dd`)
- **B**: `Steps` (Format: `#,##0`)
- **C**: `Step Target` (Format: `#,##0`, default: `10000`)
- **D**: `Target Completed`:
  ```excel
  =IF(ISBLANK(B2), "", IF(B2>=C2, "✅ Yes", "❌ No"))
  ```
- **E**: `Weight (kg)` (Manual entry, starting baseline: `103.4`)
- **F**: `Walking/Activity` (e.g. "Morning 4 km walk, 55 mins")
- **G**: `Notes` (e.g. "Fasting day, deficit on track")

---

### Tab 2: `Dashboard` (Summary KPI Calculations)

| Row | KPI | Formula / Value | Description |
|---|---|---|---|
| **4** | **Starting Weight** | `103.4` | Hardcoded initial baseline (Day 1) |
| **5** | **Current Weight** | `=IFERROR(INDEX(SORT(FILTER({'Daily Log'!A2:A, 'Daily Log'!E2:E}, 'Daily Log'!E2:E<>""), 1, FALSE), 1, 2), "Not entered")` | Chronologically latest date with entered weight |
| **6** | **Total Weight Lost** | `=IF(ISNUMBER(B5), B4-B5, 0)` | Starting Weight (B4) - Current Weight (B5) |
| **7** | **Daily Step Target** | `10000` | Target goal per day |
| **8** | **All-Time Daily Avg Steps** | `=IFERROR(ROUND(AVERAGE('Daily Log'!B2:B)), 0)` | Average across all recorded rows |
| **9** | **Last 7 Calendar Days Avg Steps** | `=IFERROR(ROUND(AVERAGE(FILTER('Daily Log'!B2:B, 'Daily Log'!A2:A>=TODAY()-6, 'Daily Log'!A2:A<=TODAY()))), 0)` | Past 7 actual calendar days (rolling) |
| **10** | **Last 30 Calendar Days Avg Steps** | `=IFERROR(ROUND(AVERAGE(FILTER('Daily Log'!B2:B, 'Daily Log'!A2:A>=TODAY()-29, 'Daily Log'!A2:A<=TODAY()))), 0)` | Past 30 actual calendar days (rolling) |
| **11** | **Current Month Total Steps** | `=IFERROR(SUM(FILTER('Daily Log'!B2:B, YEAR('Daily Log'!A2:A)=YEAR(TODAY()), MONTH('Daily Log'!A2:A)=MONTH(TODAY()))), 0)` | Accumulated steps this calendar month |
| **12** | **Target Completion Rate** | `=IFERROR(COUNTIF('Daily Log'!D2:D, "✅ Yes") / COUNTA('Daily Log'!B2:B), 0)` | % of recorded days reaching 10,000 steps |
| **13** | **Last 7 Days Rolling Weight Avg** | `=IFERROR(ROUND(AVERAGE(FILTER('Daily Log'!E2:E, 'Daily Log'!A2:A>=TODAY()-6, 'Daily Log'!A2:A<=TODAY(), ISNUMBER('Daily Log'!E2:E))), 1), "N/A")` | Smooths out daily water weight fluctuations |

---

## 5. Background Execution & Android Battery Constraints

### The Reality of Modern Android Background Work:
1. **Exact Alarms & Doze Mode**: Android applies aggressive battery optimization. Apps cannot reliably fire exact alarms every 15 minutes unless running an active foreground notification service.
2. **WorkManager Periodic Work**:
   - Minimum periodicity allowed by Android OS is **15 minutes**.
   - In our app, `SyncWorker` is scheduled every **3 hours** with `NetworkType.CONNECTED` constraints.
   - When the phone is charging or on Wi-Fi, Android automatically runs the worker without draining battery.

### The "Dual-Day Sync" Mechanism in `SyncWorker`:
To guarantee that steps taken late at night (e.g., between 23:00 and 23:59) are not missed when a new calendar day starts:
- The worker reads and syncs **Today's count** (`LocalDate.now()`).
- The worker **also re-evaluates and syncs Yesterday's final count** (`today.minusDays(1)`).
- This ensures that if the phone went into Doze mode overnight, yesterday's row is updated with the exact, finalized 24-hour total on the first sync of the morning!

### Battery Optimization Whitelist:
On your Android phone:
1. Open **Android Settings** ➔ **Apps** ➔ **Fitness Sync**.
2. Tap **App battery usage** / **Battery Saver**.
3. Choose **Unrestricted** (No restrictions).
