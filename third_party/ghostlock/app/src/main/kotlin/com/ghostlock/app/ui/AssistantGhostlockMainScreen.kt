package com.ghostlock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.R
import com.ghostlock.app.domain.model.ShizukuStatus

private val AssistantLightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECEBFF),
    onPrimaryContainer = Color(0xFF29217A),
    secondary = Color(0xFF555A68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEEF3),
    onSecondaryContainer = Color(0xFF242832),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF1B1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFF0F0F3),
    onSurfaceVariant = Color(0xFF5F6068),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF777780),
    outlineVariant = Color(0xFFD7D7DD),
    error = Color(0xFFBA1A1A),
)

private val AssistantDarkColors = darkColorScheme(
    primary = Color(0xFFAEB4FF),
    onPrimary = Color(0xFF171A4F),
    primaryContainer = Color(0xFF292E5D),
    onPrimaryContainer = Color(0xFFE1E3FF),
    secondary = Color(0xFFC4C6D4),
    onSecondary = Color(0xFF292B34),
    secondaryContainer = Color(0xFF343741),
    onSecondaryContainer = Color(0xFFE2E3EF),
    background = Color(0xFF08090D),
    onBackground = Color(0xFFF0F0F7),
    surface = Color(0xFF11131A),
    onSurface = Color(0xFFF0F0F7),
    surfaceVariant = Color(0xFF1D202A),
    onSurfaceVariant = Color(0xFFC8CAD6),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF8D91A1),
    outlineVariant = Color(0xFF343846),
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun AssistantGhostlockMainScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
        AssistantDarkColors
    } else {
        AssistantLightColors
    }
    MaterialTheme(colorScheme = colorScheme) {
        var otaUrl by rememberSaveable { mutableStateOf("") }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        val canRun = state.kernelSupported &&
                state.executionHasProfile &&
                state.profileInvalidPaths.isEmpty() &&
                (!state.shizukuEnabled || state.shizukuStatus == ShizukuStatus.READY)
        val deviceSummary = listOf(state.deviceName, state.socName)
            .filter(String::isNotBlank)
            .joinToString(" · ")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = deviceSummary.ifBlank { stringResource(R.string.device_label) },
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.kernelRelease.ifBlank { "-" },
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        enabled = !state.running,
                        onClick = { showSettings = true },
                    ) {
                        Text(stringResource(R.string.assistant_home_settings))
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = stringResource(R.string.assistant_home_action_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.assistant_home_action_body),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                enabled = canRun && !state.running,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = ButtonDefaults.ContentPadding,
                                onClick = actions::onRun,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (state.running) R.string.action_running
                                        else R.string.assistant_home_start,
                                    ),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                            if (!canRun && !state.running) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(role = Role.Button) { actions.onProfileInvalid() },
                                )
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            enabled = !state.running,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = ButtonDefaults.ContentPadding,
                            onClick = actions::onParseImage,
                        ) {
                            Text(
                                text = stringResource(R.string.assistant_home_parse_local),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = otaUrl,
                            onValueChange = { otaUrl = it },
                            enabled = !state.running,
                            placeholder = {
                                Text(stringResource(R.string.assistant_home_ota_url_hint))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                        )
                        OutlinedButton(
                            modifier = Modifier.height(56.dp),
                            enabled = !state.running,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = ButtonDefaults.ContentPadding,
                            onClick = {
                                if (otaUrl.isBlank()) actions.onParseOta()
                                else actions.onParseOta(otaUrl.trim())
                            },
                        ) {
                            Text(stringResource(R.string.action_parse_ota), maxLines = 1)
                        }
                    }
                }
            }

            AssistantGhostlockLogPanel(
                lines = state.logLines,
                onCopy = actions::onCopyLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }

        if (showSettings) {
            AssistantGhostlockSettingsDialog(
                state = state,
                actions = actions,
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun AssistantGhostlockLogPanel(
    lines: List<GhostlockLogLine>,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF19191C),
        border = BorderStroke(1.dp, Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.log_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC7C5CC),
                )
                TextButton(onClick = onCopy) {
                    Text(
                        text = stringResource(R.string.action_copy),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFC7C5CC),
                    )
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_home_log_empty),
                        color = Color(0xFF9B9BA2),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 6.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line.text.trimEnd('\r', '\n'),
                                color = assistantLogLineColor(line.color),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun assistantLogLineColor(color: Int): Color = when (color) {
    0xFFFF6B6B.toInt() -> Color(0xFFFF6B6B)
    0xFF5FD68A.toInt() -> Color(0xFF5FD68A)
    0xFFFFC94D.toInt() -> Color(0xFFFFC94D)
    0xFF60A5FA.toInt() -> Color(0xFF60A5FA)
    else -> Color(0xFFE5E1E6)
}

@Composable
private fun AssistantGhostlockSettingsDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
    onDismiss: () -> Unit,
) {
    var cpuMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 520.dp),
        title = { Text(stringResource(R.string.assistant_home_settings)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.cpuPairLabels.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            onClick = { cpuMenuExpanded = true },
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(stringResource(R.string.cpu_pair_label))
                                Text(
                                    text = state.customCpuPair?.let {
                                        stringResource(
                                            R.string.cpu_pair_custom,
                                            "${it.primary}, ${it.consumer}",
                                        )
                                    } ?: state.cpuPairLabels.getOrElse(state.cpuPairIndex) { "-" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = cpuMenuExpanded,
                            onDismissRequest = { cpuMenuExpanded = false },
                        ) {
                            state.cpuPairLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        cpuMenuExpanded = false
                                        actions.onCpuPairSelected(index)
                                    },
                                )
                            }
                        }
                    }
                }

                AssistantSettingSwitch(
                    title = stringResource(R.string.safe_mode_label),
                    summary = stringResource(R.string.safe_mode_summary),
                    checked = state.safeModeEnabled,
                    onCheckedChange = actions::onSafeModeChanged,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AssistantSettingSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
