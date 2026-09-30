# 🏃‍♂️ Android Health Connect → Google Sheets Automated Fitness Tracker (2026 Standard)

> **Modern Architecture**: Built using Android **Health Connect SDK**, **WorkManager**, and **Google Apps Script Webhook API**.  
> **No Deprecated Google Fit REST APIs**. Fully deduplicated daily step totals via `StepsRecord.COUNT_TOTAL`.

---

## 🎯 Profile & Goals
- **Height**: 176 cm
- **Starting Weight**: 103.4 kg
- **Daily Step Target**: 10,000 steps
- **Primary Goal**: Body fat reduction & progressive walking activity
- **Sync Architecture**: `Android Health Connect` ➔ `Custom Android App (WorkManager)` ➔ `Apps Script Web App` ➔ `Google Sheets`

---

## 📂 Project Directory Structure

```text
GOOGLE FIT/
├── google-apps-script/
│   ├── Code.gs             # Apps Script Webhook (Upsert logic + Sheet builder)
│   └── appsscript.json     # Apps Script Manifest
├── android/                # Complete Android Studio project (Kotlin + Jetpack Compose)
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           └── java/com/fit/tracker/
│               ├── MainActivity.kt               # Material 3 UI + Live step counter
│               ├── health/HealthConnectManager.kt # Jetpack Health Connect API wrapper
│               ├── network/SheetsSyncClient.kt    # HTTP POST to Google Apps Script
│               ├── sync/SyncWorker.kt             # WorkManager periodic background sync
│               ├── data/SyncPreferences.kt        # User settings & state persistence
│               └── ui/PrivacyPolicyActivity.kt    # Health Connect compliance screen
└── SETUP_GUIDE.md          # Step-by-step setup walkthrough
```

---

## 🚀 1. Google Sheets & Apps Script Setup (5 Minutes)

### Step 1: Create a Google Sheet
1. Open [Google Sheets](https://sheets.new) in your browser.
2. Name your sheet: **"Fitness & Weight Loss Tracker"**.

### Step 2: Add Apps Script & Initialize Properties
1. In your Google Sheet, click **Extensions** ➔ **Apps Script**.
2. Replace all existing text in `Code.gs` with the contents of [`google-apps-script/Code.gs`](file:///c:/Users/L.Jebadurai/OneDrive%20-%20Astra%20Industrial%20Group/Dep_Share%20Online/project/GOOGLE%20FIT/google-apps-script/Code.gs).
3. In the toolbar, select `setupProject` and click **Run** (grant permissions when prompted).
   - This automatically creates the **"Daily Log"** and **"Dashboard"** sheets with all formatting and calendar-based formulas.
   - It saves your **`SPREADSHEET_ID`** in **Script Properties** (so `doPost` opens it reliably without `getActiveSpreadsheet()`).
   - It also automatically generates a secure, random **`API_SECRET_TOKEN`** inside **Script Properties**!
4. The execution log will immediately display your **`SPREADSHEET_ID`** and **`API_SECRET_TOKEN`**! (You can also select `showMySecretToken` and click **Run** anytime to view it again).

### Step 3: Deploy as Web App
1. Click **Deploy** ➔ **New deployment**.
2. Click the gear icon ⚙️ next to *Select type* and choose **Web app**.
3. Set the following fields:
   - **Description**: `Fitness Health Connect Sync`
   - **Execute as**: `Me (your_email@gmail.com)`
4. Click **Deploy**.
5. Copy the generated **Web app URL** (starts with `https://script.google.com/macros/s/.../exec`).

### Step 4: Verify Webhook (Before Touching Android)
1. **Option A (Inside Apps Script Editor)**:
   - Select the function **`testDoPostLocally`** and click **Run**.
   - Switch to your Google Sheet: You will see today's row created with `8,742` steps instantly!
2. **Option B (From Windows PowerShell)**:
   ```powershell
   $body = @{
       token = "YOUR_API_SECRET_TOKEN"
       date = (Get-Date).ToString("yyyy-MM-dd")
       steps = 9500
   } | ConvertTo-Json

   Invoke-RestMethod -Uri "https://script.google.com/macros/s/.../exec" -Method Post -Body $body -ContentType "application/json"
   ```
   If it returns `{"status":"success", ...}`, your Google Sheets Webhook is 100% verified and ready for Android!

---

## 📊 2. Spreadsheet Columns & Formulas Reference

The script initializes two tabs:

### Tab 1: `Daily Log`
| Col | Header | Type | Description / Formula |
|---|---|---|---|
| **A** | `Date` | Date (`YYYY-MM-DD`) | Auto-populated by sync (Unique key) |
| **B** | `Steps` | Number | Auto-populated from Health Connect |
| **C** | `Step Target` | Number | Default: `10000` |
| **D** | `Target Completed` | Formula | `=IF(ISBLANK(B2), "", IF(B2>=C2, "✅ Yes", "❌ No"))` |
| **E** | `Weight (kg)` | Manual Number | Enter your daily weight here (e.g. `103.4`) |
| **F** | `Walking/Activity` | Manual Text | Notes on walking session (e.g., "Evening 45 min brisk walk") |
| **G** | `Notes` | Manual Text | Fasting, calories, sleep notes |

### Key Sheet Formulas:
- **Weight Change from Starting Weight (103.4 kg)**:
  ```excel
  =IF(ISBLANK(E2), "", E2 - 103.4)
  ```
- **Weight Change from Previous Entry**:
  ```excel
  =IF(OR(ISBLANK(E2), ROW()=2), "", IF(ISBLANK(OFFSET(E2,-1,0)), "", E2 - OFFSET(E2,-1,0)))
  ```
- **Last 7 Days Rolling Average Steps**:
  ```excel
  =IFERROR(ROUND(AVERAGE(OFFSET('Daily Log'!B1, MAX(1, COUNTA('Daily Log'!B:B)-7), 0, MIN(7, COUNTA('Daily Log'!B:B)-1), 1))), 0)
  ```
- **Target Completion Rate**:
  ```excel
  =IFERROR(COUNTIF('Daily Log'!D2:D, "✅ Yes") / COUNTA('Daily Log'!B2:B), 0)
  ```

---

## 📱 3. Android Studio Project Setup

1. Open **Android Studio** (Koala / Ladybug or newer recommended).
2. Choose **Open** and select the [`android/`](file:///c:/Users/L.Jebadurai/OneDrive%20-%20Astra%20Industrial%20Group/Dep_Share%20Online/project/GOOGLE%20FIT/android) folder.
3. Connect your Android phone via USB (with **USB Debugging** enabled in Developer Options).
4. Run the app (`Shift + F10`).

### On Your Phone:
1. Tap **Grant** on the *Permission Required* card.
   - Android will display the official Health Connect permission dialog for `READ_STEPS`.
   - Toggle **Allow** and tap **Done**.
2. Tap the **Settings icon (⚙️)** at top right:
   - Paste your **Google Apps Script Web App URL**.
   - Ensure the **Secret Token** matches `Code.gs`.
   - Tap **Save Settings**.
3. Tap **Sync Now to Google Sheet**. Your today's steps are instantly upserted into the Sheet!
4. Enable the **Automatic Daily Sync** toggle for automated WorkManager synchronization.

---

## 🔋 4. Android Background Sync & Battery Optimization

On Android (Android 14+):
- **WorkManager** schedules execution every 3 hours when connected to network.
- To prevent manufacturer task killers (Samsung, Xiaomi, OnePlus) from halting background tasks:
  1. Go to **Settings** ➔ **Apps** ➔ **Fitness Sync**.
  2. Select **Battery** ➔ Choose **Unrestricted**.
- In addition to today's count, the background worker automatically verifies and re-syncs **yesterday's final count** to lock in any late-night steps!
