@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package roro.stellar.yuehong.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.yuehong.R
import roro.stellar.yuehong.dfroot.DfRootReporter
import roro.stellar.yuehong.dfroot.DfRootRunner

@Composable
internal fun DfRootScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    fun appendLog(message: String) {
        log = (log + message).takeLast(96 * 1024)
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dfroot_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    scrolledContainerColor = colors.background,
                ),
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeviceCard(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                kernel = System.getProperty("os.version", "unknown").orEmpty(),
            )

            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // Keep the control reusable and visually enabled. A second
                    // tap during an active run is ignored until the first run
                    // has released the native exploit resources.
                    if (!running) {
                        running = true
                        log = ""
                        scope.launch(Dispatchers.IO) {
                            val reporter = DfRootReporter { message ->
                                scope.launch(Dispatchers.Main.immediate) { appendLog(message) }
                            }
                            // The native result reports the upstream KernelSU late-load outcome.
                            val outcome = runCatching {
                                DfRootRunner.run(context, reporter)
                            }
                            withContext(Dispatchers.Main) {
                                outcome.exceptionOrNull()?.let {
                                    appendLog("\n[DirtyFrag] ${it.message ?: it.javaClass.simpleName}\n")
                                }
                                appendLog(
                                    if (outcome.getOrNull() == 0) {
                                        context.getString(R.string.dfroot_finished)
                                    } else {
                                        context.getString(
                                            R.string.dfroot_failed,
                                            outcome.getOrNull() ?: -1,
                                        )
                                    },
                                )
                                running = false
                            }
                        }
                    }
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(
                        if (running) R.string.dfroot_running else R.string.dfroot_run,
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }

            LogCard(
                modifier = Modifier.weight(1f),
                log = log,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    manufacturer: String,
    model: String,
    kernel: String,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = stringResource(R.string.dfroot_device_info_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.dfroot_device_info, manufacturer, model, kernel),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogCard(
    modifier: Modifier,
    log: String,
) {
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    LaunchedEffect(log) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.dfroot_log_title),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.7f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (log.isBlank()) {
                    Text(
                        text = stringResource(R.string.dfroot_log_empty),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = log,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}
