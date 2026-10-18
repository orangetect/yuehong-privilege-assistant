package roro.stellar.yuehong.shell

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipFile

/** Metadata for a KernelSU manager whose APK contains libksud.so. */
data class KernelSuManagerInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    /** The matching native library path, or an APK/entry path when the library is unextracted. */
    val libKsudPath: String,
)

/**
 * Stores the manager package selected on the mode-selection page and discovers compatible
 * managers by looking for libksud.so in their extracted native directory or APK splits.
 */
object KernelSuManagerSelection {
    const val DEFAULT_PACKAGE: String = "me.weishu.kernelsu"
    private const val PREFS_NAME = "kernel_su_manager_selection"
    private const val PACKAGE_KEY = "package_name"
    private const val LIBRARY_NAME = "libksud.so"
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Returns the persisted package, falling back to the stock KernelSU package when empty. */
    fun selectedPackage(context: Context): String {
        val appContext = context.applicationContext
        val stored = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PACKAGE_KEY, null)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PACKAGE_KEY, null)
            } else {
                null
            }
        return stored
            ?.trim()
            ?.takeIf(PACKAGE_PATTERN::matches)
            ?: DEFAULT_PACKAGE
    }

    /** Persists a package name; null or blank deliberately resets to the stock package. */
    fun saveSelectedPackage(context: Context, packageName: String?): String {
        val normalized = packageName?.trim()
            ?.takeIf(PACKAGE_PATTERN::matches)
            ?: DEFAULT_PACKAGE
        val appContext = context.applicationContext
        // DirtyFrag's bootstrap runs after a direct-boot style hand-off. Keep
        // the value in both preference stores and commit before arming any
        // root flow so a fresh context cannot fall back to the default package.
        appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PACKAGE_KEY, normalized)
            .commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PACKAGE_KEY, normalized)
                .commit()
        }
        return normalized
    }

    /** Copies libksud.so from the selected manager, including split APK installs. */
    fun copySelectedKsud(context: Context, destination: File): Boolean {
        val selected = selectedPackage(context)
        val manager = scan(context).firstOrNull { it.packageName == selected } ?: return false
        val libraryPath = manager.libKsudPath
        val split = libraryPath.indexOf("!/")
        val parent = destination.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return runCatching {
            if (split < 0) {
                File(libraryPath).inputStream().use { input ->
                    FileOutputStream(destination, false).use { output -> input.copyTo(output) }
                }
            } else {
                val apk = libraryPath.substring(0, split)
                val entryName = libraryPath.substring(split + 2)
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return false
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(destination, false).use { output -> input.copyTo(output) }
                    }
                }
            }
            destination.isFile && destination.length() > 0L
        }.getOrDefault(false)
    }

    /**
     * Finds every installed application carrying libksud.so. This is intended to run off the
     * main thread because inspecting split APKs can touch many APK zip files.
     */
    @Suppress("DEPRECATION")
    fun scan(context: Context): List<KernelSuManagerInfo> {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val applications = runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrElse { emptyList() }
        val selected = selectedPackage(appContext)
        return applications.asSequence()
            .mapNotNull { application ->
                val libraryPath = findLibraryPath(application)
                    ?: return@mapNotNull null
                val packageName = application.packageName.orEmpty().trim()
                if (packageName.isEmpty()) return@mapNotNull null
                val label = runCatching {
                    application.loadLabel(packageManager).toString().trim()
                }.getOrNull().orEmpty().ifBlank { packageName }
                val icon = runCatching { application.loadIcon(packageManager) }
                    .getOrElse { packageManager.defaultActivityIcon }
                KernelSuManagerInfo(packageName, label, icon, libraryPath)
            }
            .distinctBy(KernelSuManagerInfo::packageName)
            .sortedWith(
                compareBy<KernelSuManagerInfo> {
                    if (it.packageName == selected) 0 else 1
                }.thenBy { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.packageName },
            )
            .toList()
    }

    private fun findLibraryPath(application: ApplicationInfo): String? {
        val nativeDirectory = application.nativeLibraryDir
        if (!nativeDirectory.isNullOrBlank()) {
            val extracted = File(nativeDirectory, LIBRARY_NAME)
            if (extracted.isFile) return extracted.absolutePath
        }

        val sourceApks = buildList {
            application.sourceDir?.let(::add)
            application.splitSourceDirs?.forEach(::add)
        }
        for (source in sourceApks.distinct()) {
            val entry = findLibraryEntry(source) ?: continue
            return "$source!/$entry"
        }
        return null
    }

    private fun findLibraryEntry(apkPath: String): String? {
        return runCatching {
            ZipFile(apkPath).use { zip ->
                // Only advertise an ABI that the arm64 DirtyFrag payload can
                // execute. ZIP order is not stable in multi-ABI manager APKs.
                listOf("lib/arm64-v8a/$LIBRARY_NAME", "lib/arm64/$LIBRARY_NAME")
                    .firstOrNull { zip.getEntry(it) != null }
            }
        }.getOrNull()
    }
}
