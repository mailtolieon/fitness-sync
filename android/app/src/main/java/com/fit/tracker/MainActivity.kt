package com.fit.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.fit.tracker.data.SyncPreferences
import com.fit.tracker.health.HealthConnectManager
import com.fit.tracker.network.SheetsSyncClient
import com.fit.tracker.network.SyncResult
import com.fit.tracker.sync.SyncWorker
import com.fit.tracker.ui.PrivacyPolicyActivity
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var prefs: SyncPreferences
    private var permissionLauncher: ActivityResultLauncher<Set<String>>? = null

    // Observable states for UI
    private var isHealthConnectAvailable by mutableStateOf(false)
    private var hasPermissions by mutableStateOf(false)
    private var todaySteps by mutableStateOf(0L)
    private var isSyncing by mutableStateOf(false)
    private var lastSyncStatus by mutableStateOf("Ready")
    private var lastSyncTime by mutableStateOf("Never")
    private var startupError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // First install uncaught crash handler to launch CrashReportActivity instead of system popup
        installGlobalCrashHandler()

        try {
            super.onCreate(savedInstanceState)

            healthConnectManager = HealthConnectManager(this)
            prefs = SyncPreferences(this)

            lastSyncStatus = prefs.lastSyncStatus
            lastSyncTime = prefs.lastSyncTime

            // Register permission contract safely
            try {
                val contract = healthConnectManager.createPermissionContract()
                permissionLauncher = registerForActivityResult(contract) { granted ->
                    try {
                        if (granted.containsAll(healthConnectManager.permissions)) {
                            hasPermissions = true
                            Toast.makeText(this, "Health Connect permissions granted!", Toast.LENGTH_SHORT).show()
                            refreshSteps()
                        } else {
                            hasPermissions = false
                            Toast.makeText(this, "READ_STEPS permission was not granted.", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e("FitnessSync", "Error handling permission result", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e("FitnessSync", "Failed to register permission contract", e)
            }

            checkInitialState()

            setContent {
                MaterialTheme {
                    MainScreen(
                        isAvailable = isHealthConnectAvailable,
                        hasPermission = hasPermissions,
                        steps = todaySteps,
                        isSyncing = isSyncing,
                        lastSyncStatus = lastSyncStatus,
                        lastSyncTime = lastSyncTime,
                        prefs = prefs,
                        onInstallHealthConnect = {
                            try {
                                startActivity(healthConnectManager.getInstallIntent())
                            } catch (e: Exception) {
                                Toast.makeText(this, "Could not open Google Play Store", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRequestPermission = { requestHealthPermissions() },
                        onRefreshSteps = { refreshSteps() },
                        onSyncNow = { triggerManualSync() },
                        onAutoSyncToggled = { enabled ->
                            try {
                                prefs.isAutoSyncEnabled = enabled
                                if (enabled) {
                                    SyncWorker.schedulePeriodicSync(this)
                                    Toast.makeText(this, "Auto background sync enabled", Toast.LENGTH_SHORT).show()
                                } else {
                                    SyncWorker.cancelPeriodicSync(this)
                                    Toast.makeText(this, "Auto sync disabled", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this, "WorkManager error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onOpenPrivacyPolicy = {
                            try {
                                startActivity(Intent(this, PrivacyPolicyActivity::class.java))
                            } catch (e: Exception) {
                                Toast.makeText(this, "Could not open Privacy Policy", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e("FitnessSync", "Startup failure in onCreate", t)
            showNativeFallbackScreen(t)
        }
    }

    private fun installGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FitnessSync", "Uncaught exception on thread ${thread.name}", throwable)
            try {
                SyncPreferences(applicationContext).lastSyncStatus = "Crash: ${throwable.message}"
            } catch (_: Exception) {}

            try {
                val intent = Intent(applicationContext, CrashReportActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("error_details", "${throwable.javaClass.name}: ${throwable.message}\n\n${throwable.stackTraceToString()}")
                }
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (e: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun showNativeFallbackScreen(throwable: Throwable) {
        val errorText = "${throwable.javaClass.name}: ${throwable.message}\n\nStack Trace:\n${throwable.stackTraceToString()}"

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        val title = android.widget.TextView(this).apply {
            text = "Fitness Sync - Diagnostic Notice"
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#D32F2F"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16)
        }

        val desc = android.widget.TextView(this).apply {
            text = "An exception prevented normal screen loading. The diagnostic details are captured below. Please copy them and share with the developer:"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#333333"))
            setPadding(0, 0, 0, 20)
        }

        val copyBtn = android.widget.Button(this).apply {
            text = "📋 Copy Diagnostic Error"
            setBackgroundColor(android.graphics.Color.parseColor("#1A73E8"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Fitness Sync Diagnostic", errorText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "Copied error log to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        val retryBtn = android.widget.Button(this).apply {
            text = "🔄 Retry App Launch"
            setOnClickListener { recreate() }
        }

        val errorView = android.widget.TextView(this).apply {
            text = errorText
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#212121"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        }

        val scroll = android.widget.ScrollView(this).apply {
            addView(errorView)
        }

        rootLayout.addView(title)
        rootLayout.addView(desc)
        rootLayout.addView(copyBtn)
        rootLayout.addView(android.widget.Space(this).apply { minimumHeight = 12 })
        rootLayout.addView(retryBtn)
        rootLayout.addView(android.widget.Space(this).apply { minimumHeight = 20 })
        rootLayout.addView(scroll)

        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        try {
            if (::healthConnectManager.isInitialized) {
                checkInitialState()
            }
        } catch (e: Throwable) {
            Log.e("FitnessSync", "Error in onResume", e)
        }
    }

    private fun checkInitialState() {
        try {
            isHealthConnectAvailable = healthConnectManager.isHealthConnectAvailable()
            if (isHealthConnectAvailable) {
                lifecycleScope.launch {
                    try {
                        hasPermissions = healthConnectManager.hasPermissions()
                        if (hasPermissions) {
                            refreshSteps()
                        }
                    } catch (e: Exception) {
                        Log.e("FitnessSync", "Failed checking permissions", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FitnessSync", "Error in checkInitialState", e)
        }
    }

    private fun requestHealthPermissions() {
        try {
            if (!healthConnectManager.isHealthConnectAvailable()) {
                Toast.makeText(this, "Please install Health Connect on this device first.", Toast.LENGTH_LONG).show()
                startActivity(healthConnectManager.getInstallIntent())
                return
            }
            permissionLauncher?.launch(healthConnectManager.permissions)
                ?: Toast.makeText(this, "Permission launcher not ready", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("FitnessSync", "Failed to launch permission intent", e)
            Toast.makeText(this, "Failed to launch permission screen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshSteps() {
        lifecycleScope.launch {
            try {
                val result = healthConnectManager.readDailySteps(LocalDate.now())
                result.onSuccess { steps ->
                    todaySteps = steps
                }.onFailure { ex ->
                    Toast.makeText(this@MainActivity, "Failed to read steps: ${ex.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("FitnessSync", "Error reading steps", e)
            }
        }
    }

    private fun triggerManualSync() {
        if (!::prefs.isInitialized || prefs.webAppUrl.isBlank() || prefs.secretToken.isBlank()) {
            Toast.makeText(this, "Please enter both Web App URL and Secret Token in settings (⚙️) first!", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            try {
                isSyncing = true
                lastSyncStatus = "Reading steps..."

                val today = LocalDate.now()
                val stepsResult = healthConnectManager.readDailySteps(today)

                if (stepsResult.isFailure) {
                    isSyncing = false
                    val err = stepsResult.exceptionOrNull()?.message ?: "Failed to read steps"
                    lastSyncStatus = "Error: $err"
                    Toast.makeText(this@MainActivity, "Sync cancelled: $err", Toast.LENGTH_LONG).show()
                    return@launch
                }

                todaySteps = stepsResult.getOrThrow()
                lastSyncStatus = "Syncing to Sheets..."

                val sheetsClient = SheetsSyncClient()
                val result = sheetsClient.syncSteps(
                    webAppUrl = prefs.webAppUrl,
                    apiSecretToken = prefs.secretToken,
                    date = today,
                    steps = todaySteps
                )

                isSyncing = false
                val nowTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                lastSyncTime = nowTime
                prefs.lastSyncTime = nowTime
                prefs.lastSyncSteps = todaySteps

                when (result) {
                    is SyncResult.Success -> {
                        lastSyncStatus = "Success (${result.action} row ${result.row})"
                        prefs.lastSyncStatus = lastSyncStatus
                        Toast.makeText(this@MainActivity, "Google Sheet updated successfully!", Toast.LENGTH_SHORT).show()
                    }
                    is SyncResult.Error -> {
                        lastSyncStatus = "Failed: ${result.errorMessage}"
                        prefs.lastSyncStatus = lastSyncStatus
                        Toast.makeText(this@MainActivity, "Sync failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                isSyncing = false
                lastSyncStatus = "Sync exception: ${e.message}"
                Toast.makeText(this@MainActivity, "Sync error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fitness Sync - Diagnostic Log") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Startup Diagnostic Notice", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The app encountered an initial exception. The complete error details are captured below:",
                        fontSize = 13.sp
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Fitness Sync Diagnostic", errorMessage)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied error log to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Log")
                }
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isAvailable: Boolean,
    hasPermission: Boolean,
    steps: Long,
    isSyncing: Boolean,
    lastSyncStatus: String,
    lastSyncTime: String,
    prefs: SyncPreferences,
    onInstallHealthConnect: () -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshSteps: () -> Unit,
    onSyncNow: () -> Unit,
    onAutoSyncToggled: (Boolean) -> Unit,
    onOpenPrivacyPolicy: () -> Unit
) {
    var webAppUrl by remember { mutableStateOf(prefs.webAppUrl) }
    var secretToken by remember { mutableStateOf(prefs.secretToken) }
    var autoSyncEnabled by remember { mutableStateOf(prefs.isAutoSyncEnabled) }
    var showSettings by remember { mutableStateOf(false) }

    val stepTarget = 10000L
    val progress = (steps.toFloat() / stepTarget).coerceIn(0f, 1f)
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fitness Sync (Health Connect)") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Health Connect Status Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isAvailable && hasPermission) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAvailable && hasPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Status",
                        tint = if (isAvailable && hasPermission) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (!isAvailable) "Health Connect Not Available"
                            else if (!hasPermission) "Permission Required"
                            else "Health Connect Connected",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (!isAvailable) "Please install Health Connect on this device (Android 9-13)."
                            else if (!hasPermission) "Tap 'Grant Access' to allow reading your daily steps."
                            else "Ready to read aggregated steps.",
                            fontSize = 13.sp,
                            color = Color.DarkGray
                        )
                    }
                    if (!isAvailable) {
                        Button(onClick = onInstallHealthConnect) {
                            Text("Install")
                        }
                    } else if (!hasPermission) {
                        Button(onClick = onRequestPermission) {
                            Text("Grant")
                        }
                    }
                }
            }

            // Today's Steps Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Today's Steps",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = onRefreshSteps) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val formattedSteps = java.text.NumberFormat.getIntegerInstance().format(steps)
                    val formattedTarget = java.text.NumberFormat.getIntegerInstance().format(stepTarget)
                    val percent = (progress * 100).toInt()

                    Text(
                        text = formattedSteps,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Goal: $formattedTarget steps ($percent%)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = if (progress >= 1.0f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFFE0E0E0),
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Sync Button
                    Button(
                        onClick = onSyncNow,
                        enabled = !isSyncing && hasPermission,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Syncing to Google Sheets...")
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sync Now to Google Sheet")
                        }
                    }
                }
            }

            // Sync Status & Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Sync Status",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Last Status: $lastSyncStatus", fontSize = 13.sp)
                    Text("Last Synced At: $lastSyncTime", fontSize = 13.sp, color = Color.Gray)
                }
            }

            // Auto-sync Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic Daily Sync", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "WorkManager syncs your steps in the background whenever network is available.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = {
                            autoSyncEnabled = it
                            onAutoSyncToggled(it)
                        }
                    )
                }
            }

            // Settings Card (Collapsible)
            AnimatedVisibility(visible = showSettings) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Connection Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = webAppUrl,
                            onValueChange = { webAppUrl = it },
                            label = { Text("Google Apps Script Web App URL") },
                            placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = secretToken,
                            onValueChange = { secretToken = it },
                            label = { Text("Secret Token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                prefs.webAppUrl = webAppUrl.trim()
                                prefs.secretToken = secretToken.trim()
                                Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Save Settings")
                        }
                    }
                }
            }

            // Footer link
            TextButton(
                onClick = onOpenPrivacyPolicy,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Health Connect Privacy Policy & Details", fontSize = 12.sp)
            }
        }
    }
}
