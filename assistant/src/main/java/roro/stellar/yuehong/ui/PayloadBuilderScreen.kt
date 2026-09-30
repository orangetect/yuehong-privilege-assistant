package roro.stellar.yuehong.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.R
import roro.stellar.yuehong.payloadbuilder.PayloadBuildController
import roro.stellar.yuehong.payloadbuilder.PayloadBuildPhase
import roro.stellar.yuehong.payloadbuilder.PayloadBuildScheme

@Composable
fun PayloadBuilderScreen(controller: PayloadBuildController) {
    val context = LocalContext.current
    val current = controller.state
    var showError by remember { mutableStateOf<String?>(null) }
    val errorScrollState = rememberScrollState()
    val logScrollState = rememberScrollState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0).orEmpty() to if (cursor.isNull(1)) 0L else cursor.getLong(1)
            } else "boot.img" to 0L
        } ?: ("boot.img" to 0L)
        controller.selectBoot(uri, name, size)
    }

    LaunchedEffect(current.error) {
        current.error?.let { error ->
            showError = error
            controller.clearError()
        }
    }
    if (showError != null) {
        AlertDialog(
            onDismissRequest = { showError = null },
            title = { Text(stringResource(R.string.builder_error_title)) },
            text = {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(errorScrollState),
                    ) {
                        Text(showError.orEmpty())
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showError = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 840.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.builder_heading),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    modifier = Modifier.padding(top = 3.dp),
                    text = stringResource(R.string.builder_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                BuilderCard {
                    Text(
                        text = stringResource(R.string.builder_section_input),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (current.sourceName.isBlank()) {
                            stringResource(R.string.builder_boot_none)
                        } else {
                            stringResource(R.string.builder_boot_selected, current.sourceName, current.sourceSize)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !current.busy && !current.otaRunning,
                            onClick = { picker.launch(arrayOf("*/*")) },
                        ) {
                            Text(stringResource(R.string.builder_pick_boot))
                        }
                    }
                    Text(
                        text = stringResource(R.string.builder_ota),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = current.otaUrl,
                        onValueChange = controller::setOtaUrl,
                        enabled = !current.busy && !current.otaRunning,
                        singleLine = true,
                        label = { Text(stringResource(R.string.builder_ota_hint)) },
                    )
                    TextButton(
                        enabled = !current.busy && !current.otaRunning && current.otaUrl.isNotBlank(),
                        onClick = controller::parseOta,
                    ) {
                        Text(
                            stringResource(
                                if (current.otaRunning) R.string.builder_ota_running
                                else R.string.builder_ota_run,
                            ),
                        )
                    }
                    if (current.otaRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            item {
                BuilderCard {
                    Text(
                        text = stringResource(R.string.builder_scheme_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.builder_scheme_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PayloadBuildScheme.entries.forEach { scheme ->
                            FilterChip(
                                selected = current.scheme == scheme,
                                enabled = !current.busy,
                                onClick = { controller.selectScheme(scheme) },
                                label = { Text(stringResource(scheme.titleRes)) },
                            )
                        }
                    }
                    MotionButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !current.busy && !current.otaRunning && current.sourceName.isNotBlank(),
                        onClick = { controller.build(current.scheme) },
                    ) {
                        Text(
                            stringResource(
                                if (current.busy) R.string.builder_running else R.string.builder_run,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            item {
                BuilderCard {
                    Text(
                        text = stringResource(R.string.builder_log_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 260.dp)
                                .verticalScroll(logScrollState),
                        ) {
                            Text(
                                text = current.log.joinToString("\n").ifBlank {
                                    stringResource(R.string.builder_log_empty)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }

            if (current.phase == PayloadBuildPhase.Done) {
                item {
                    BuilderCard {
                        Text(
                            text = stringResource(R.string.builder_section_result),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(current.summary, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    stringResource(R.string.builder_output_size, current.outputSize),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(R.string.builder_output_sha, current.outputSha256),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                                current.savedFiles.forEach { path ->
                                    Text(path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuilderCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
