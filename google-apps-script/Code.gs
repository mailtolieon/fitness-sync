/**
 * ==============================================================================
 * GOOGLE FITNESS TRACKER - GOOGLE APPS SCRIPT WEB APP
 * ==============================================================================
 * Architecture:
 * Android Health Connect -> WorkManager -> HTTP POST -> Apps Script Web App -> Google Sheets
 *
 * IMPORTANT WEB APP ARCHITECTURE:
 * When executed as a Web App (doPost), Apps Script must NOT depend on
 * getActiveSpreadsheet() because bound context may be detached.
 * Instead, SPREADSHEET_ID is persisted in Script Properties, and
 * SpreadsheetApp.openById() is used.
 * ==============================================================================
 */

// ------------------------------------------------------------------------------
// SCRIPT PROPERTY KEYS
// ------------------------------------------------------------------------------
const PROP_API_TOKEN = "API_SECRET_TOKEN";
const PROP_SPREADSHEET_ID = "SPREADSHEET_ID";

// ------------------------------------------------------------------------------
// SHEET COLUMNS (1-INDEXED)
// ------------------------------------------------------------------------------
const COL_DATE = 1;             // Column A: Date (YYYY-MM-DD)
const COL_STEPS = 2;            // Column B: Steps
const COL_STEP_TARGET = 3;      // Column C: Step Target
const COL_TARGET_COMPLETED = 4; // Column D: Target Completed
const COL_WEIGHT = 5;           // Column E: Weight (kg) - Manual entry preserved
const COL_ACTIVITY = 6;         // Column F: Walking/Activity - Manual notes
const COL_NOTES = 7;            // Column G: Notes

const DEFAULT_STEP_TARGET = 10000;

// ------------------------------------------------------------------------------
// SCRIPT PROPERTIES GETTERS
// ------------------------------------------------------------------------------
function getApiSecretToken() {
  const token = PropertiesService.getScriptProperties().getProperty(PROP_API_TOKEN);
  return token ? token.trim() : null;
}

function getSpreadsheetId() {
  const id = PropertiesService.getScriptProperties().getProperty(PROP_SPREADSHEET_ID);
  return id ? id.trim() : null;
}

function getTargetSpreadsheet() {
  const spreadsheetId = getSpreadsheetId();
  if (!spreadsheetId) {
    throw new Error("SPREADSHEET_ID is not configured in Script Properties. Please run setupProject() once in the editor.");
  }
  return SpreadsheetApp.openById(spreadsheetId);
}

// ------------------------------------------------------------------------------
// WEB APP - HTTP POST HANDLER
// ------------------------------------------------------------------------------
function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000);
  } catch (err) {
    return createJsonResponse({ status: "error", message: "Server busy, lock timeout" });
  }

  try {
    // 1. Validate request body presence
    if (!e || !e.postData || !e.postData.contents) {
      return createJsonResponse({ status: "error", message: "Missing request body" });
    }

    let payload;
    try {
      payload = JSON.parse(e.postData.contents);
    } catch (err) {
      return createJsonResponse({ status: "error", message: "Invalid JSON request body" });
    }

    // 2. Validate API secret token against Script Properties
    const expectedToken = getApiSecretToken();
    if (!expectedToken) {
      return createJsonResponse({
        status: "error",
        message: "Server configuration error: API_SECRET_TOKEN is not configured in Script Properties."
      });
    }

    if (typeof payload.token !== "string" || payload.token.trim() !== expectedToken) {
      return createJsonResponse({ status: "error", message: "Unauthorized: Invalid API token" });
    }

    // 3. Strict ISO Date validation (YYYY-MM-DD and valid calendar date)
    const dateStr = typeof payload.date === "string" ? payload.date.trim() : "";
    if (!isValidIsoDate(dateStr)) {
      return createJsonResponse({ status: "error", message: "Invalid date format. Expected valid YYYY-MM-DD" });
    }

    // 4. Strict Steps validation (Non-negative safe integer)
    const steps = Number(payload.steps);
    if (!Number.isSafeInteger(steps) || steps < 0) {
      return createJsonResponse({ status: "error", message: "Invalid steps value. Must be a non-negative integer." });
    }

    // 5. Open target spreadsheet via SPREADSHEET_ID
    const ss = getTargetSpreadsheet();
    let sheet = ss.getSheetByName("Daily Log");
    if (!sheet) {
      sheet = setupInitialSheet(ss);
    }

    // 6. Upsert data (Update existing row or append new row)
    const result = upsertDailySteps(sheet, dateStr, steps);

    return createJsonResponse({
      status: "success",
      action: result.action,
      row: result.row,
      date: dateStr,
      steps: steps,
      message: `Successfully ${result.action} steps for ${dateStr}`
    });

  } catch (error) {
    return createJsonResponse({
      status: "error",
      message: error instanceof Error ? error.message : String(error)
    });
  } finally {
    lock.releaseLock();
  }
}

// ------------------------------------------------------------------------------
// WEB APP - GET HEALTH CHECK
// ------------------------------------------------------------------------------
function doGet() {
  return createJsonResponse({
    status: "online",
    service: "Health Connect -> Google Sheets Sync Gateway",
    timestamp: new Date().toISOString()
  });
}

// ------------------------------------------------------------------------------
// STRICT VALIDATION HELPERS
// ------------------------------------------------------------------------------
function isValidIsoDate(dateStr) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
    return false;
  }
  const [year, month, day] = dateStr.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

// ------------------------------------------------------------------------------
// UPSERT LOGIC
// ------------------------------------------------------------------------------
function upsertDailySteps(sheet, dateStr, steps) {
  const lastRow = sheet.getLastRow();
  let foundRow = -1;

  if (lastRow >= 2) {
    const dateValues = sheet.getRange(2, COL_DATE, lastRow - 1, 1).getValues();

    for (let i = 0; i < dateValues.length; i++) {
      const cellDate = dateValues[i][0];
      let formattedCellDate = "";

      if (cellDate instanceof Date) {
        formattedCellDate = Utilities.formatDate(
          cellDate,
          sheet.getParent().getSpreadsheetTimeZone(),
          "yyyy-MM-dd"
        );
      } else if (typeof cellDate === "string") {
        formattedCellDate = cellDate.trim().substring(0, 10);
      }

      if (formattedCellDate === dateStr) {
        foundRow = i + 2;
        break;
      }
    }
  }

  // UPDATE existing row (Preserves Weight, Walking, Notes!)
  if (foundRow !== -1) {
    sheet.getRange(foundRow, COL_STEPS).setValue(steps);
    sheet.getRange(foundRow, COL_TARGET_COMPLETED).setFormula(
      `=IF(ISBLANK(B${foundRow}), "", IF(B${foundRow}>=C${foundRow}, "✅ Yes", "❌ No"))`
    );
    return { action: "updated", row: foundRow };
  }

  // INSERT new row
  const newRow = lastRow + 1;
  const spreadsheetTimeZone = sheet.getParent().getSpreadsheetTimeZone();
  // Using midday (12:00:00) avoids any DST or midnight timezone offset jitter
  const dateValue = Utilities.parseDate(
    `${dateStr} 12:00:00`,
    spreadsheetTimeZone,
    "yyyy-MM-dd HH:mm:ss"
  );

  sheet.getRange(newRow, COL_DATE).setValue(dateValue);
  sheet.getRange(newRow, COL_DATE).setNumberFormat("yyyy-mm-dd");
  sheet.getRange(newRow, COL_STEPS).setValue(steps);
  sheet.getRange(newRow, COL_STEP_TARGET).setValue(DEFAULT_STEP_TARGET);
  sheet.getRange(newRow, COL_TARGET_COMPLETED).setFormula(
    `=IF(ISBLANK(B${newRow}), "", IF(B${newRow}>=C${newRow}, "✅ Yes", "❌ No"))`
  );
  return { action: "inserted", row: newRow };
}

// ------------------------------------------------------------------------------
// JSON RESPONSE HELPER
// ------------------------------------------------------------------------------
function createJsonResponse(data) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}

// ------------------------------------------------------------------------------
// INITIAL SHEET SETUP & STRUCTURE
// ------------------------------------------------------------------------------
function setupInitialSheet(ss) {
  // Save Spreadsheet ID in Script Properties for web app executions
  PropertiesService.getScriptProperties().setProperty(PROP_SPREADSHEET_ID, ss.getId());

  let sheet = ss.getSheetByName("Daily Log");
  if (!sheet) {
    sheet = ss.insertSheet("Daily Log");
  }

  // Headers
  const headers = [[
    "Date", "Steps", "Step Target", "Target Completed", "Weight (kg)", "Walking/Activity", "Notes"
  ]];
  sheet.getRange(1, 1, 1, 7).setValues(headers).setFontWeight("bold").setBackground("#E8F0FE");
  sheet.setFrozenRows(1);

  sheet.getRange("A2:A").setNumberFormat("yyyy-mm-dd");
  sheet.getRange("B2:C").setNumberFormat("#,##0");
  sheet.getRange("E2:E").setNumberFormat("0.0");

  setupDashboardSheet(ss);
  ensureApiSecretToken();

  return sheet;
}

// ------------------------------------------------------------------------------
// DASHBOARD WITH PRECISE ROW REFERENCES & CALENDAR FORMULAS
// ------------------------------------------------------------------------------
function setupDashboardSheet(ss) {
  let dash = ss.getSheetByName("Dashboard");
  if (!dash) {
    dash = ss.insertSheet("Dashboard", 0);
  }

  dash.clear();

  // Title
  dash.getRange("A1").setValue("🏃‍♂️ FITNESS & WEIGHT LOSS DASHBOARD (HEALTH CONNECT)")
    .setFontSize(14).setFontWeight("bold").setFontColor("#1A73E8");

  // Metrics Table (Header is at Row 3, data begins at Row 4)
  // Row 4: Starting Weight
  // Row 5: Current Weight
  // Row 6: Total Weight Lost (Formula references B4 and B5)
  // Row 7: Daily Step Target
  // Row 8: Average Daily Steps
  // Row 9: Last 7 Calendar Days Rolling Avg Steps
  // Row 10: Last 30 Calendar Days Rolling Avg Steps
  // Row 11: Current Month Total Steps
  // Row 12: Target Completion Rate (%)
  // Row 13: Last 7 Calendar Days Rolling Avg Weight
  const metrics = [
    ["Metric", "Value", "Notes / Calculation Basis"],
    ["Starting Weight (kg)", 103.4, "Baseline on Day 1"],
    ["Current Weight (kg)", '=IFERROR(INDEX(SORT(FILTER({\'Daily Log\'!A2:A, \'Daily Log\'!E2:E}, \'Daily Log\'!E2:E<>""), 1, FALSE), 1, 2), "Not entered")', "Latest chronological date with weight"],
    ["Total Weight Lost (kg)", '=IF(ISNUMBER(B5), B4-B5, 0)', "Starting Weight (B4) - Current Weight (B5)"],
    ["Daily Step Target", DEFAULT_STEP_TARGET, "Goal: 10,000 steps/day"],
    ["All-Time Daily Avg Steps", '=IFERROR(ROUND(AVERAGE(\'Daily Log\'!B2:B)), 0)', "Average across all recorded rows"],
    ["Last 7 Calendar Days Avg Steps", '=IFERROR(ROUND(AVERAGE(FILTER(\'Daily Log\'!B2:B, \'Daily Log\'!A2:A>=TODAY()-6, \'Daily Log\'!A2:A<=TODAY()))), 0)', "Past 7 actual calendar days (rolling)"],
    ["Last 30 Calendar Days Avg Steps", '=IFERROR(ROUND(AVERAGE(FILTER(\'Daily Log\'!B2:B, \'Daily Log\'!A2:A>=TODAY()-29, \'Daily Log\'!A2:A<=TODAY()))), 0)', "Past 30 actual calendar days (rolling)"],
    ["Current Month Total Steps", '=IFERROR(SUM(FILTER(\'Daily Log\'!B2:B, YEAR(\'Daily Log\'!A2:A)=YEAR(TODAY()), MONTH(\'Daily Log\'!A2:A)=MONTH(TODAY()))), 0)', "Total steps accumulated this calendar month"],
    ["Target Completion Rate", '=IFERROR(COUNTIF(\'Daily Log\'!D2:D, "✅ Yes") / COUNTA(\'Daily Log\'!B2:B), 0)', "% of recorded days target was achieved"],
    ["Last 7 Days Rolling Weight Avg", '=IFERROR(ROUND(AVERAGE(FILTER(\'Daily Log\'!E2:E, \'Daily Log\'!A2:A>=TODAY()-6, \'Daily Log\'!A2:A<=TODAY(), ISNUMBER(\'Daily Log\'!E2:E))), 1), "N/A")', "Rolling 7-day weight (smooths water weight)"]
  ];

  dash.getRange(3, 1, metrics.length, 3).setValues(metrics);
  dash.getRange("A3:C3").setFontWeight("bold").setBackground("#F1F3F4");

  // Number Formatting:
  dash.getRange("B4:B6").setNumberFormat("0.0");    // Weights & Total loss
  dash.getRange("B7:B11").setNumberFormat("#,##0");  // Step counts & totals
  dash.getRange("B12").setNumberFormat("0.0%");      // Target completion rate (%)
  dash.getRange("B13").setNumberFormat("0.0");      // 7-day average weight

  dash.autoResizeColumns(1, 3);
}

// ------------------------------------------------------------------------------
// API TOKEN MANAGEMENT
// ------------------------------------------------------------------------------
function ensureApiSecretToken() {
  const props = PropertiesService.getScriptProperties();
  let token = props.getProperty(PROP_API_TOKEN);
  if (!token) {
    token = "FitSync_" + Utilities.getUuid().replace(/-/g, "").substring(0, 24);
    props.setProperty(PROP_API_TOKEN, token);
    Logger.log("Generated and saved new API_SECRET_TOKEN: " + token);
  }
  return token;
}

// ------------------------------------------------------------------------------
// INITIAL SETUP ENTRY POINT
// ------------------------------------------------------------------------------
/**
 * Run this function once from the Google Apps Script editor.
 * It will:
 * 1. Initialize 'Daily Log' and 'Dashboard' sheets.
 * 2. Save SPREADSHEET_ID in Script Properties.
 * 3. Generate and save a private API_SECRET_TOKEN in Script Properties.
 */
function setupProject() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error("Run setupProject() from the Google Sheet's bound Apps Script editor.");
  }

  setupInitialSheet(ss);

  const token = getApiSecretToken();
  Logger.log("==================================================");
  Logger.log("PROJECT SETUP COMPLETE!");
  Logger.log("SPREADSHEET_ID = " + ss.getId());
  Logger.log("API_SECRET_TOKEN = " + token);
  Logger.log("==================================================");
}

/**
 * Utility function to view the currently configured API_SECRET_TOKEN anytime.
 */
function showMySecretToken() {
  const token = getApiSecretToken();
  const id = getSpreadsheetId();
  Logger.log("==================================================");
  Logger.log("SPREADSHEET_ID: " + (id || "NOT SET"));
  Logger.log("API_SECRET_TOKEN: " + (token || "NOT SET"));
  Logger.log("==================================================");
}

/**
 * Diagnostic test function:
 * Simulates an incoming Android HTTP POST directly inside the Apps Script editor.
 * Use this to verify that doPost() correctly writes rows to your Sheet BEFORE testing from Android.
 * In Apps Script editor: Select function 'testDoPostLocally' -> Click 'Run' -> Check your Google Sheet!
 */
function testDoPostLocally() {
  const token = getApiSecretToken();
  const id = getSpreadsheetId();

  if (!id || !token) {
    throw new Error("Run setupProject() first to initialize SPREADSHEET_ID and API_SECRET_TOKEN.");
  }

  const mockDate = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), "yyyy-MM-dd");
  const mockPayload = {
    token: token,
    date: mockDate,
    steps: 8742
  };

  const mockEvent = {
    postData: {
      contents: JSON.stringify(mockPayload)
    }
  };

  Logger.log("Running simulated doPost with payload: " + JSON.stringify(mockPayload));
  const response = doPost(mockEvent);
  Logger.log("Response from doPost: " + response.getContent());
}
