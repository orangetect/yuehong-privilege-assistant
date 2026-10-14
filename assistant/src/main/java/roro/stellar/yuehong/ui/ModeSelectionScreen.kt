package roro.stellar.yuehong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.JailbreakActivationMode

@Composable
internal fun ModeSelectionScreen(
    ghostLockKernelAvailable: Boolean,
    dfRootPlatformAvailable: Boolean,
    activationMode: JailbreakActivationMode,
    onActivationModeChange: (JailbreakActivationMode) -> Unit,
    onOpenGhostLock: () -> Unit,
    onOpenDfRoot: () -> Unit,
    onOpenStellar: () -> Unit,
    onOpenVivoWired: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.mode_selection_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenStellar,
            ) {
                Text(
                    text = stringResource(R.string.mode_stellar_open),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = dfRootPlatformAvailable,
                onClick = onOpenDfRoot,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mode_dfroot_open),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.mode_dfroot_kernel_required),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = ghostLockKernelAvailable,
                onClick = onOpenGhostLock,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mode_ghostlock_open),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.mode_ghostlock_kernel_required),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenVivoWired,
            ) {
                Text(
                    text = stringResource(R.string.mode_vivo_wired_open),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                JailbreakActivationMode.entries.forEach { mode ->
                    JailbreakModeOption(
                        modifier = Modifier.weight(1f),
                        mode = mode,
                        selected = activationMode == mode,
                        onClick = { onActivationModeChange(mode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JailbreakModeOption(
    modifier: Modifier = Modifier,
    mode: JailbreakActivationMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val containerColor = if (selected) colors.primaryContainer else colors.surfaceContainerLow
    val contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface

    Row(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = stringResource(mode.labelResource),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
        )
    }
}
