package roro.stellar.yuehong.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import roro.stellar.yuehong.R

internal object NecessaryPermissions {
    /**
     * QUERY_ALL_PACKAGES is represented by an app-op on Android 11 and later.
     * Some OEM permission panels revoke that app-op without changing the
     * manifest permission result, so checkSelfPermission() alone can still
     * report GRANTED while PackageManager hides most installed packages.
     */
    private const val QUERY_ALL_PACKAGES_OP = "android:query_all_packages"

    fun hasAppListAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true

        if (context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        // QUERY_ALL_PACKAGES is not a runtime permission, but Android 11+
        // exposes an app-op that OEMs can toggle independently.  Treat an
        // explicit ignored/errored result as denied even when the permission
        // API above still reports it as granted.
        val appOpsResult = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(
                    QUERY_ALL_PACKAGES_OP,
                    Process.myUid(),
                    context.packageName,
                )
        }.getOrNull()
        // MODE_DEFAULT means the device has no explicit override and the
        // manifest permission controls visibility.  Only an explicit ignore
        // or error is a denial; treating MODE_DEFAULT as denied would make
        // the page fail on AOSP builds where this app-op is not registered.
        if (appOpsResult == AppOpsManager.MODE_IGNORED ||
            appOpsResult == AppOpsManager.MODE_ERRORED
        ) {
            return false
        }

        return runCatching {
            // Keep a real PackageManager probe here.  A successful call is
            // required by the manager selector; the list may legitimately be
            // empty on a device with no third-party applications.
            context.packageManager.getInstalledApplications(0)
            true
        }.getOrDefault(false)
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = context
            .getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() != false
        return permissionGranted && notificationsEnabled
    }

    fun allGranted(context: Context): Boolean =
        hasAppListAccess(context) && hasNotificationAccess(context)
}

@Composable
internal fun NecessaryPermissionsScreen(
    onGranted: () -> Unit,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var appListGranted by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }

    fun refresh() {
        appListGranted = NecessaryPermissions.hasAppListAccess(context)
        notificationGranted = NecessaryPermissions.hasNotificationAccess(context)
        checking = false
    }

    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }

    fun openAppPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refresh()
        if (NecessaryPermissions.allGranted(context)) onGranted()
    }

    LaunchedEffect(Unit) {
        refresh()
        if (NecessaryPermissions.allGranted(context)) {
            onGranted()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .align(Alignment.Center)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = context.getString(R.string.necessary_permissions_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (checking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = context.getString(R.string.necessary_permissions_checking),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = context.getString(R.string.necessary_permissions_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionStatusRow(
                    label = context.getString(R.string.necessary_permission_app_list),
                    granted = appListGranted,
                )
                PermissionStatusRow(
                    label = context.getString(R.string.necessary_permission_notifications),
                    granted = notificationGranted,
                )
                if (!appListGranted) {
                    Text(
                        text = context.getString(R.string.necessary_permission_app_list_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onExit) {
                    Text(context.getString(R.string.cancel))
                }
                TextButton(
                    enabled = !checking,
                    onClick = {
                        val appListReady = NecessaryPermissions.hasAppListAccess(context)
                        val notificationReady = NecessaryPermissions.hasNotificationAccess(context)
                        appListGranted = appListReady
                        notificationGranted = notificationReady
                        checking = false
                        if (!notificationReady &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else if (!notificationReady) {
                            openNotificationSettings()
                        } else if (appListReady) {
                            onGranted()
                        } else {
                            // QUERY_ALL_PACKAGES is not a runtime permission.
                            // OEMs that expose it in their permission manager
                            // must be opened through the app details page.
                            openAppPermissionSettings()
                        }
                    },
                ) {
                    Text(context.getString(R.string.necessary_permissions_retry))
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(
            text = if (granted) "✓" else "!",
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}
