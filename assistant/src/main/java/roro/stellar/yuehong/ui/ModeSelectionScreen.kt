package roro.stellar.yuehong.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.R

@Composable
internal fun ModeSelectionScreen(
    ghostLockKernelAvailable: Boolean,
    onOpenGhostLock: () -> Unit,
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
        }
    }
}
