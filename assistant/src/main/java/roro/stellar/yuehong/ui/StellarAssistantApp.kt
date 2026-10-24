package roro.stellar.yuehong.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import roro.stellar.yuehong.BuildConfig
import roro.stellar.yuehong.dfroot.DirtyFragVrActivity
import roro.stellar.yuehong.shell.HttpStartupVerificationApi
import roro.stellar.yuehong.shell.KernelSuManagerSelection
import roro.stellar.yuehong.shell.StartupVerification
import roro.stellar.yuehong.shell.StartupVerificationResult
import com.ghostlock.app.ui.MainActivity as GhostlockMainActivity

private enum class AppPage {
    Announcement,
    ChannelVerification,
    NecessaryPermissions,
    ModeSelection,
}

@Composable
fun StellarAssistantApp() {
    StellarTheme {
        val context = LocalContext.current
        BackHandler { (context as? Activity)?.moveTaskToBack(true) }
        val startupVerificationApi = remember(context.applicationContext) {
            HttpStartupVerificationApi(context.applicationContext)
        }
        var page by rememberSaveable { mutableStateOf(AppPage.Announcement) }
        var startupRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }
        val dirtyFragVrPlatformAvailable = remember {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

        fun openGhostLockMode() {
            val activity = context as? Activity ?: return
            val manager = KernelSuManagerSelection.selectedManagerTarget(context)
            val managerPackage = manager?.packageName
                ?: KernelSuManagerSelection.selectedPackage(context)
            activity.startActivity(
                Intent(activity, GhostlockMainActivity::class.java).apply {
                    putExtra(GhostlockMainActivity.EXTRA_MANAGER_PACKAGE, managerPackage)
                    putExtra(
                        GhostlockMainActivity.EXTRA_KSUD_PATH,
                        manager?.libKsudPath.orEmpty(),
                    )
                },
            )
        }

        fun openDirtyFragVrMode() {
            if (!dirtyFragVrPlatformAvailable) return
            val activity = context as? Activity ?: return
            val manager = KernelSuManagerSelection.selectedManagerTarget(context)
            val managerPackage = manager?.packageName
                ?: KernelSuManagerSelection.selectedPackage(context)
            activity.startActivity(
                Intent(activity, DirtyFragVrActivity::class.java).apply {
                    putExtra(DirtyFragVrActivity.EXTRA_MANAGER_PACKAGE, managerPackage)
                    putExtra(DirtyFragVrActivity.EXTRA_KSUD_PATH, manager?.libKsudPath.orEmpty())
                },
            )
        }

        fun openStellarMode() {
            val activity = context as? Activity ?: return
            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "roro.stellar.manager.ui.features.manager.ManagerActivity",
                )
                putExtra("route", "workspace")
            }
            activity.startActivity(intent)
        }

        fun openVivoWiredMode() {
            val activity = context as? Activity ?: return
            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "roro.stellar.manager.ui.features.wired.VivoWiredActivity",
                )
            }
            activity.startActivity(intent)
        }

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val direction = if (forward) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = tween(480, easing = FastOutSlowInEasing),
                    initialOffsetX = { width -> direction * width / 3 },
                ) + fadeIn(tween(320)) + scaleIn(tween(420), initialScale = 0.985f)
                val exit = slideOutHorizontally(
                    animationSpec = tween(420, easing = FastOutSlowInEasing),
                    targetOffsetX = { width -> -direction * width / 4 },
                ) + fadeOut(tween(260)) + scaleOut(tween(360), targetScale = 0.99f)
                enter togetherWith exit
            },
            label = "app-page-transition",
        ) { activePage ->
            when (activePage) {
                AppPage.Announcement -> AnnouncementGate(
                    api = startupVerificationApi,
                    refreshGeneration = startupRefreshGeneration,
                    onContinue = { verification ->
                        if (!verification.authorized) {
                            page = AppPage.ChannelVerification
                        } else if (NecessaryPermissions.allGranted(context)) {
                            page = AppPage.ModeSelection
                        } else {
                            page = AppPage.NecessaryPermissions
                        }
                    },
                    onRetry = { startupRefreshGeneration++ },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )

                AppPage.ChannelVerification -> ChannelVerificationScreen(
                    api = startupVerificationApi,
                    onVerified = {
                        page = if (NecessaryPermissions.allGranted(context)) {
                            AppPage.ModeSelection
                        } else {
                            AppPage.NecessaryPermissions
                        }
                    },
                    onStartupInvalidated = {
                        startupRefreshGeneration++
                        page = AppPage.Announcement
                    },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )

                AppPage.NecessaryPermissions -> NecessaryPermissionsScreen(
                    onGranted = { page = AppPage.ModeSelection },
                    onExit = { (context as? Activity)?.moveTaskToBack(true) },
                )

                AppPage.ModeSelection -> ModeSelectionScreen(
                    dirtyFragVrPlatformAvailable = dirtyFragVrPlatformAvailable,
                    onOpenGhostLock = ::openGhostLockMode,
                    onOpenDirtyFragVr = ::openDirtyFragVrMode,
                    onOpenStellar = ::openStellarMode,
                    onOpenVivoWired = ::openVivoWiredMode,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementGate(
    api: HttpStartupVerificationApi,
    refreshGeneration: Int,
    onContinue: (StartupVerification) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    var verificationResult by remember { mutableStateOf<StartupVerificationResult?>(null) }

    LaunchedEffect(api, refreshGeneration) {
        verificationResult = null
        verificationResult = api.verifyStartup()
    }

    val result = verificationResult
    AnimatedContent(
        targetState = result,
        transitionSpec = {
            fadeIn(tween(260, easing = FastOutSlowInEasing)) togetherWith
                fadeOut(tween(180))
        },
        label = "announcement-loading-transition",
    ) { loadedResult ->
        if (loadedResult == null) {
            AnnouncementLoadingScreen()
        } else {
            AnnouncementScreen(
                result = loadedResult,
                localVersion = BuildConfig.VERSION_NAME,
                localVersionCode = BuildConfig.VERSION_CODE,
                    onContinue = onContinue,
                    onRetry = onRetry,
                    onExit = onExit,
            )
        }
    }
}
