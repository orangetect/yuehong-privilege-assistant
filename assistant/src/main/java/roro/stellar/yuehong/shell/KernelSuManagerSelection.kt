package roro.stellar.yuehong.shell

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Metadata for an installed app carrying a compatible KernelSU ksud executable. */
data class KernelSuManagerInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    /** The matching library file in the app's arm64 native library directory. */
    val libKsudPath: String,
)

/**
 * Stores the manager package selected on the mode-selection page and discovers compatible
 * managers by checking for the ksud late-load CLI, regardless of its native library filename.
 */
object KernelSuManagerSelection {
    const val DEFAULT_PACKAGE: String = "me.weishu.kernelsu"
    private const val PREFS_NAME = "kernel_su_manager_selection"
    private const val PACKAGE_KEY = "package_name"
    private const val LIBRARY_NAME = "libksud.so"
    private const val MIN_RANDOM_LIBRARY_SIZE = 32 * 1024L
    private const val MAX_FINGERPRINT_SCAN_SIZE = 32 * 1024 * 1024L
    private const val KSUD_HELP_TIMEOUT_SECONDS = 2L
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val APP_PACKAGE_DIRECTORY = Regex("^/data/app/[^/]+/([^/]+)$")
    private val KSUD_BINARY_MARKERS = listOf(
        "Load kernelsu.ko and execute late-load stage scripts",
        "Pass allow_shell=1 when loading kernelsu.ko",
        "Specify kernel KMI version instead of auto-detection",
        "manager package name",
    )
    private val KSUD_HELP_OPTIONS = listOf("--kmi", "--allow-shell", "--package-name")

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

    /** Copies the selected manager's ksud executable from its installed arm64 library directory. */
    fun copySelectedKsud(context: Context, destination: File): Boolean {
        val selected = selectedPackage(context)
        val manager = scan(context).firstOrNull { it.packageName == selected } ?: return false
        val libraryPath = manager.libKsudPath
        val parent = destination.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return runCatching {
            File(libraryPath).inputStream().use { input ->
                FileOutputStream(destination, false).use { output -> input.copyTo(output) }
            }
            destination.isFile && destination.length() > 0L
        }.getOrDefault(false)
    }

    /**
     * Finds installed applications carrying ksud under `/data/app/<install>/<package>/lib/arm64`.
     * Fixed-name libraries are preferred. Randomized names must contain the shared ksud CLI
     * fingerprint and expose the expected `late-load --help` options. Run this off the main
     * thread because it scans native libraries and probes matching candidates.
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
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?: return null

        // Filter the app package directory first, before examining any library files.
        val packageDirectory = nativeDirectory.parentFile
            ?.parentFile
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?: return null
        if (!isAllowedAppPackageDirectory(packageDirectory)) return null

        val expectedNativeDirectory = runCatching {
            File(packageDirectory, "lib/arm64").canonicalFile
        }.getOrNull() ?: return null
        if (nativeDirectory != expectedNativeDirectory || !nativeDirectory.isDirectory) return null

        // Preserve the historical exact-name match before attempting generic discovery.
        val exactNameLibrary = File(nativeDirectory, LIBRARY_NAME)
        if (isDirectLibraryFile(exactNameLibrary, nativeDirectory)) {
            return exactNameLibrary.canonicalPath
        }
        return findRandomLibrary(nativeDirectory)?.canonicalPath
    }

    private fun isAllowedAppPackageDirectory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val packageDirectoryName = APP_PACKAGE_DIRECTORY
            .matchEntire(directory.path)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false
        return !packageDirectoryName.contains("android", ignoreCase = true)
    }

    private fun findRandomLibrary(directory: File): File? {
        return directory.listFiles()
            ?.asSequence()
            ?.filter {
                it.name.endsWith(".so", ignoreCase = true) && isDirectLibraryFile(it, directory)
            }
            ?.filter { it.length() >= MIN_RANDOM_LIBRARY_SIZE }
            ?.sortedByDescending(File::length)
            ?.firstOrNull { candidate ->
                hasKsudBinaryFingerprint(candidate) &&
                    supportsKsudLateLoad(candidate.absolutePath)
            }
    }

    private fun isDirectLibraryFile(file: File, directory: File): Boolean {
        return runCatching {
            file.isFile && file.canonicalFile.parentFile == directory
        }.getOrDefault(false)
    }

    private fun hasKsudBinaryFingerprint(file: File): Boolean {
        return runCatching {
            file.inputStream().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var totalBytes = 0L
                var tooLarge = false
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_FINGERPRINT_SCAN_SIZE) {
                        tooLarge = true
                        break
                    }
                    output.write(buffer, 0, count)
                }
                if (tooLarge) {
                    false
                } else {
                    val contents = String(output.toByteArray(), Charsets.ISO_8859_1)
                    KSUD_BINARY_MARKERS.all(contents::contains)
                }
            }
        }.getOrDefault(false)
    }

    private fun supportsKsudLateLoad(path: String): Boolean {
        val process = runCatching {
            ProcessBuilder(path, "late-load", "--help")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false

        return try {
            if (!process.waitFor(KSUD_HELP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.exitValue() == 0 &&
                    output.contains("late-load") &&
                    KSUD_HELP_OPTIONS.all(output::contains)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }
}
