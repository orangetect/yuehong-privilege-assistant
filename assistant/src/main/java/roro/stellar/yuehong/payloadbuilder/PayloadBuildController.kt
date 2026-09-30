package roro.stellar.yuehong.payloadbuilder

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kernelpack.KernelPack
import com.kernelpack.PackRequest
import com.kernelpack.boot.BootImageParser
import com.kernelpack.boot.KernelDecompressor
import com.kernelpack.kallsyms.KallsymsFinder
import com.kernelpack.kallsyms.KallsymsOptions
import com.kernelpack.ota.OtaPayloadExtractor
import com.kernelpack.policy.KernelSchemeSelector
import com.kernelpack.profile.BaselineRegistry
import com.kernelpack.profile.BaselineScheme
import com.kernelpack.vivo.VrKoBypass
import com.kernelpack.vivo.VrKoGateDecision
import com.kernelpack.vivo.VrKoPayloadGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.yuehong.R
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PayloadBuildScheme(
    val library: String,
    val filePrefix: String,
    val baselineScheme: BaselineScheme,
    val titleRes: Int,
) {
    Universal("libionstack.so", "payload-universal", BaselineScheme.UNIVERSAL, R.string.builder_scheme_universal),
    VivoVrKo("libbs.so", "payload-vivo", BaselineScheme.VIVO, R.string.builder_scheme_vivo),
}

enum class PayloadBuildPhase { Idle, Reading, Packing, Done, Failed }

data class PayloadBuildState(
    val phase: PayloadBuildPhase = PayloadBuildPhase.Idle,
    val scheme: PayloadBuildScheme = PayloadBuildScheme.Universal,
    val sourceName: String = "",
    val sourceSize: Long = 0,
    val otaRunning: Boolean = false,
    val otaUrl: String = "",
    val kernelRelease: String = "",
    val log: List<String> = emptyList(),
    val summary: String = "",
    val outputName: String = "",
    val outputSize: Long = 0,
    val outputSha256: String = "",
    val savedFiles: List<String> = emptyList(),
    val error: String? = null,
) {
    val busy: Boolean get() = phase == PayloadBuildPhase.Reading || phase == PayloadBuildPhase.Packing
}

/**
 * 构建产物的最小编排层：输入 boot/OTA，复用 KernelPack，导出三份文件。
 * 算法仍由 com.kernelpack 提供；本类只做文件读取、线程切换和结果保存。
 */
class PayloadBuildController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null
    private var otaJob: Job? = null
    private var bootUri: Uri? = null
    private var bootFile: File? = null

    var state by mutableStateOf(PayloadBuildState())
        private set

    fun selectBoot(uri: Uri, name: String, size: Long) {
        bootUri = uri
        bootFile = null
        state = state.copy(sourceName = name, sourceSize = size, error = null)
    }

    fun selectScheme(scheme: PayloadBuildScheme) {
        if (!state.busy) state = state.copy(scheme = scheme, error = null)
    }

    fun setOtaUrl(url: String) {
        state = state.copy(otaUrl = url, error = null)
    }

    fun parseOta() {
        val url = state.otaUrl.trim()
        if (state.otaRunning || url.isBlank()) {
            if (url.isBlank()) state = state.copy(error = appContext.getString(R.string.builder_ota_bad_url))
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            state = state.copy(error = appContext.getString(R.string.builder_ota_bad_url))
            return
        }
        otaJob?.cancel()
        otaJob = scope.launch {
            state = state.copy(otaRunning = true, error = null, log = emptyList())
            val lines = ArrayList<String>()
            fun log(line: String) {
                synchronized(lines) { lines += line }
                val snapshot = synchronized(lines) { lines.takeLast(MAX_LOG_LINES) }
                scope.launch { state = state.copy(log = snapshot) }
            }
            try {
                val workDir = File(appContext.cacheDir, OTA_CACHE_DIR)
                withContext(Dispatchers.IO) {
                    workDir.mkdirs()
                    workDir.listFiles()?.filter { it.name.startsWith(OTA_FILE_PREFIX) }?.forEach { it.delete() }
                }
                log(appContext.getString(R.string.builder_ota_started))
                val extracted = withContext(Dispatchers.IO) {
                    OtaPayloadExtractor.extractPartitions(url, workDir, onLog = ::log)
                }
                val file = extracted.bootFile
                bootFile = file
                bootUri = null
                state = state.copy(
                    otaRunning = false,
                    sourceName = file.name,
                    sourceSize = file.length(),
                    log = lines.takeLast(MAX_LOG_LINES),
                )
            } catch (error: Throwable) {
                state = state.copy(
                    otaRunning = false,
                    log = lines.takeLast(MAX_LOG_LINES),
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun build(scheme: PayloadBuildScheme) {
        if (state.busy || (bootUri == null && bootFile == null)) {
            if (bootUri == null && bootFile == null) {
                state = state.copy(
                    phase = PayloadBuildPhase.Failed,
                    error = appContext.getString(R.string.builder_no_input),
                )
            }
            return
        }
        buildJob?.cancel()
        buildJob = scope.launch {
            val sourceUri = bootUri
            val sourceFile = bootFile
            val lines = ArrayList<String>(128)
            fun log(line: String) {
                synchronized(lines) { lines += line }
                val snapshot = synchronized(lines) { lines.takeLast(MAX_LOG_LINES) }
                scope.launch { state = state.copy(log = snapshot) }
            }
            state = PayloadBuildState(
                phase = PayloadBuildPhase.Reading,
                scheme = scheme,
                sourceName = state.sourceName,
                sourceSize = state.sourceSize,
            )
            try {
                log(appContext.getString(R.string.builder_phase_reading, state.sourceName))
                val bootBytes = withContext(Dispatchers.IO) {
                    when {
                        sourceFile != null -> {
                            require(sourceFile.isFile) { appContext.getString(R.string.builder_input_gone) }
                            sourceFile.readBytes()
                        }
                        sourceUri != null -> appContext.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                            ?: error(appContext.getString(R.string.builder_read_failed))
                        else -> error(appContext.getString(R.string.builder_no_input))
                    }
                }

                val kernelRelease = withContext(Dispatchers.Default) {
                    val parsed = BootImageParser.parse(bootBytes, KernelDecompressor.default)
                    parsed.diagnosis?.let(::log)
                    KallsymsFinder(parsed.image, KallsymsOptions()).run().versionNumber
                }
                state = state.copy(kernelRelease = kernelRelease)
                log(appContext.getString(R.string.builder_detected_kernel, kernelRelease))

                val selected = when (val decision = KernelSchemeSelector.select(kernelRelease)) {
                    is KernelSchemeSelector.Decision.Selected -> decision
                    is KernelSchemeSelector.Decision.Blocked -> error(
                        buildString {
                            append(decision.title).append('\n')
                            decision.detail.forEach { append(it).append('\n') }
                            append(decision.remedy)
                        },
                    )
                }
                selected.notes.forEach(::log)
                val profileId = BaselineRegistry.profileIdFor(
                    scheme.baselineScheme,
                    selected.series,
                    selected.release,
                ) ?: error(
                    appContext.getString(R.string.builder_missing_baseline, appContext.getString(scheme.titleRes), selected.series),
                )
                val baseline = BaselineRegistry.byId(profileId)?.profile
                    ?: error("找不到基线档位：$profileId")
                val baseName = BaselineRegistry.BaselineLibraries.resolve(scheme.library, selected.release)
                val baseFile = File(appContext.applicationInfo.nativeLibraryDir, baseName)
                require(baseFile.isFile) { "缺少基础载荷：$baseName" }
                val baseBytes = withContext(Dispatchers.IO) { baseFile.readBytes() }
                log(appContext.getString(R.string.builder_loaded, bootBytes.size / 1024, baseBytes.size / 1024))

                val vrDecision = withContext(Dispatchers.Default) {
                    VrKoPayloadGate.decide(scheme.baselineScheme, baseName, baseBytes)
                }
                if (vrDecision is VrKoGateDecision.Blocked) {
                    error(
                        buildString {
                            append(vrDecision.title).append('\n')
                            vrDecision.detail.forEach { append(it).append('\n') }
                            append(vrDecision.remedy)
                        },
                    )
                }
                if (vrDecision is VrKoGateDecision.Pass) log(appContext.getString(R.string.builder_vr_ko_ok))
                val identity = listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE, Build.PRODUCT)
                    .joinToString(" ").lowercase()
                val modules = withContext(Dispatchers.IO) { runCatching { File("/proc/modules").readText() }.getOrNull() }
                if (VrKoBypass.shouldSuggestVivoScheme(modules, identity, scheme.baselineScheme)) {
                    log(appContext.getString(R.string.builder_vr_ko_hint))
                }

                state = state.copy(phase = PayloadBuildPhase.Packing)
                val result = withContext(Dispatchers.Default) {
                    KernelPack.pack(
                        PackRequest(
                            bootImage = bootBytes,
                            baseLibrary = baseBytes,
                            baseline = baseline,
                            scheme = scheme.baselineScheme,
                            log = ::log,
                        ),
                    )
                }
                val packed = result.packedLibrary ?: error(
                    buildString {
                        append(result.summary())
                        result.warnings.forEach { append('\n').append(it) }
                    },
                )
                result.patchReport?.let { report ->
                    log(appContext.getString(
                        R.string.builder_patch_done,
                        report.outcomes.count { it.changed },
                        report.outcomes.sumOf { it.sitesPatched + it.dataLiteralsPatched },
                    ))
                }
                val digest = sha256Hex(packed)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val stem = "${scheme.filePrefix}-$stamp"
                val artifacts = linkedMapOf(
                    "$stem.so" to packed,
                    "$stem.json" to result.offsetsJson.toByteArray(Charsets.UTF_8),
                    "$stem.h" to result.targetHeader.toByteArray(Charsets.UTF_8),
                )
                val paths = withContext(Dispatchers.IO) {
                    artifacts.map { (name, bytes) -> DownloadStore.save(appContext, name, bytes) }
                }
                paths.forEach { log(appContext.getString(R.string.builder_saved_download, it)) }
                state = state.copy(
                    phase = PayloadBuildPhase.Done,
                    summary = result.summary(),
                    outputName = "$stem.so",
                    outputSize = packed.size.toLong(),
                    outputSha256 = digest,
                    savedFiles = paths,
                    log = synchronized(lines) { lines.takeLast(MAX_LOG_LINES) },
                )
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                synchronized(lines) { lines += "[X] $message" }
                state = state.copy(
                    phase = PayloadBuildPhase.Failed,
                    log = synchronized(lines) { lines.takeLast(MAX_LOG_LINES) },
                    error = message,
                )
            }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    override fun close() {
        otaJob?.cancel()
        buildJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val MAX_LOG_LINES = 300
        const val OTA_CACHE_DIR = "ksuroot-ota"
        const val OTA_FILE_PREFIX = "ksuroot_ota_"

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
