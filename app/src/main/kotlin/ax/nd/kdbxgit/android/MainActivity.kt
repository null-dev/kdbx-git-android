package ax.nd.kdbxgit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ax.nd.kdbxgit.android.settings.SettingsScreen
import ax.nd.kdbxgit.android.sync.SyncLogEntry
import ax.nd.kdbxgit.android.sync.SyncOutcome
import ax.nd.kdbxgit.android.sync.SyncStatus
import ax.nd.kdbxgit.android.sync.SyncType
import ax.nd.kdbxgit.android.sync.SyncWorker
import ax.nd.kdbxgit.android.ui.theme.KdbxGitTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KdbxGitTheme {
                KdbxGitApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as KdbxGitApplication
        if (app.settingsRepository.serverConfig.value != null) {
            SyncWorker.schedulePeriodicSync(this)
        }
    }
}

@Composable
private fun KdbxGitApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(onOpenSettings = { navController.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(onNavigateUp = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onOpenSettings: () -> Unit,
    vm: MainViewModel = viewModel(),
) {
    val syncStatus by vm.syncStatus.collectAsState()
    val syncLog by vm.syncLog.collectAsState()
    val isSyncing = syncStatus is SyncStatus.Pulling || syncStatus is SyncStatus.Pushing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KDBX Git") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Status header ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = statusLabel(syncStatus),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    syncLog.firstOrNull()?.let { last ->
                        Text(
                            text = "Last sync: ${formatTimestamp(last.timestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(onClick = { vm.syncNow() }, enabled = !isSyncing) {
                    Text("Sync now")
                }
            }

            HorizontalDivider()

            // ── Sync log ──────────────────────────────────────────────────
            if (syncLog.isEmpty()) {
                Text(
                    text = "No sync history yet",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(syncLog) { entry ->
                        SyncLogRow(entry)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncLogRow(entry: SyncLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTimestamp(entry.timestamp),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.4f),
        )
        Text(
            text = entry.trigger.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.2f),
        )
        Text(
            text = typeLabel(entry.type),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.0f),
        )
        Text(
            text = outcomeLabel(entry.outcome, entry.errorMessage),
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.outcome == SyncOutcome.FAILURE)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
        )
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

private val timestampFmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
private fun formatTimestamp(epochMs: Long): String =
    timestampFmt.format(Date(epochMs))

private fun statusLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Idle        -> "Status: Idle"
    SyncStatus.Pulling     -> "Status: Pulling…"
    SyncStatus.Pushing     -> "Status: Pushing…"
    is SyncStatus.Error    -> "Status: Error — ${status.message}"
}

private fun typeLabel(type: SyncType): String = when (type) {
    SyncType.PULL      -> "PULL"
    SyncType.PUSH      -> "PUSH"
    SyncType.PUSH_PULL -> "PUSH+PULL"
}

private fun outcomeLabel(outcome: SyncOutcome, error: String?): String = when (outcome) {
    SyncOutcome.SUCCESS   -> "✓ OK"
    SyncOutcome.MERGED    -> "✓ Merged"
    SyncOutcome.NO_CHANGE -> "– No change"
    SyncOutcome.FAILURE   -> "✗ ${error ?: "Error"}"
}
