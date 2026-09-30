package com.fit.tracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PrivacyPolicyActivity: Displays Health Connect data usage policy.
 * Health Connect guidelines mandate an in-app privacy policy screen.
 */
class PrivacyPolicyActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Privacy Policy") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Health Connect Data Usage",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "This personal application connects to Android Health Connect solely to read your daily aggregated step count (StepsRecord).\n\n" +
                                    "1. How data is used: Your step count is read once per sync operation to update your designated private Google Sheet.\n" +
                                    "2. Data sharing: Your data is never sold, transferred to third-party advertisers, or shared with external analytics services. It transmits directly to your private Google Apps Script Web App URL.\n" +
                                    "3. Data storage: No fitness data is retained on external servers other than your personal Google Spreadsheet.\n" +
                                    "4. Permissions: You may revoke READ_STEPS access at any time via Android System Settings -> Privacy -> Health Connect -> Permissions.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
