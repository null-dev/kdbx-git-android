package ax.nd.kdbxgit.android.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val current by viewModel.serverConfig.collectAsStateWithLifecycle()
    val pushEndpoint by viewModel.pushEndpoint.collectAsStateWithLifecycle()
    val savedInterval by viewModel.pollIntervalMinutes.collectAsStateWithLifecycle()

    var serverUrl       by rememberSaveable { mutableStateOf(current?.serverUrl       ?: "") }
    var clientId        by rememberSaveable { mutableStateOf(current?.clientId        ?: "") }
    var password        by rememberSaveable { mutableStateOf(current?.password        ?: "") }
    var customCaCert    by rememberSaveable { mutableStateOf(current?.customCaCertPem ?: "") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var pollIntervalMinutes by rememberSaveable { mutableStateOf(savedInterval) }
    var intervalDropdownExpanded by rememberSaveable { mutableStateOf(false) }

    // Populate fields when existing config is first loaded (e.g. after process restore).
    LaunchedEffect(current) {
        if (serverUrl.isEmpty() && current != null) {
            serverUrl    = current!!.serverUrl
            clientId     = current!!.clientId
            password     = current!!.password
            customCaCert = current!!.customCaCertPem ?: ""
        }
    }
    LaunchedEffect(savedInterval) {
        pollIntervalMinutes = savedInterval
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://kdbx-git.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                                          else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password"
                                                 else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = customCaCert,
                onValueChange = { customCaCert = it },
                label = { Text("Custom CA Certificate (optional)") },
                placeholder = { Text("-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = intervalDropdownExpanded,
                onExpandedChange = { intervalDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = pollIntervalLabel(pollIntervalMinutes),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Poll interval") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(intervalDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = intervalDropdownExpanded,
                    onDismissRequest = { intervalDropdownExpanded = false },
                ) {
                    POLL_INTERVAL_OPTIONS.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(pollIntervalLabel(minutes)) },
                            onClick = {
                                pollIntervalMinutes = minutes
                                intervalDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (serverUrl.isBlank() || clientId.isBlank() || password.isEmpty()) {
                        scope.launch { snackbarHostState.showSnackbar("All fields are required") }
                        return@Button
                    }
                    viewModel.save(
                        serverUrl           = serverUrl,
                        clientId            = clientId,
                        password            = password,
                        customCaCertPem     = customCaCert.takeIf { it.isNotBlank() },
                        pollIntervalMinutes = pollIntervalMinutes,
                    )
                    scope.launch { snackbarHostState.showSnackbar("Settings saved") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Instant Sync (UnifiedPush)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            val distributorPackage = remember(pushEndpoint) {
                UnifiedPush.getSavedDistributor(context)
            }
            val statusText = when {
                pushEndpoint != null ->
                    "Active — distributor: $distributorPackage"
                distributorPackage != null ->
                    "Distributor found, registration pending\u2026"
                else ->
                    "No distributor installed — using periodic sync only"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

private val POLL_INTERVAL_OPTIONS = listOf(5L, 15L, 30L, 60L, 120L, 180L, 360L, 720L, 1440L)

private fun pollIntervalLabel(minutes: Long): String = when (minutes) {
    5L    -> "5 minutes"
    15L   -> "15 minutes"
    30L   -> "30 minutes"
    60L   -> "1 hour"
    120L  -> "2 hours"
    180L  -> "3 hours"
    360L  -> "6 hours"
    720L  -> "12 hours"
    1440L -> "1 day"
    else  -> "$minutes minutes"
}
