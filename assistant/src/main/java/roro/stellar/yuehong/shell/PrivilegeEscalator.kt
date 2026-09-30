package roro.stellar.yuehong.shell

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import roro.stellar.Stellar
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 提权流程的各个阶段
enum class EscalationStage {
    Idle,
    CheckingPermission,
    ValidatingCommand,
    ImportingLocalResource,
    InstallingResources,
    ExecutingPayload,
    CheckingRoot,
    ActivatingKernelSu,
    Done,
}

// 提权结果
sealed interface EscalationResult {
    data object Idle : EscalationResult
    data class RootConfirmed(val output: String, val suPath: String) : EscalationResult
    data class KernelSuActivated(val output: String) : EscalationResult
    data class KernelSuActivationFailure(val reason: String) : EscalationResult
    data class Failure(val stage: EscalationStage, val reason: String) : EscalationResult
}

// 提权编排器：串联 Stellar 权限检查、本地载荷执行、结果检测
class PrivilegeEscalator(
    context: Context,
    private val shell: StellarShellController,
) : AutoCloseable {
    var stage by mutableStateOf(EscalationStage.Idle)
        private set

    var result by mutableStateOf<EscalationResult>(EscalationResult.Idle)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val appContext = context.applicationContext
    private var selectedBundledPayload: KsuRootPayload? = null

    fun startWithBundledPayload(payload: KsuRootPayload) {
        if (stage != EscalationStage.Idle && stage != EscalationStage.Done) return
        selectedBundledPayload = payload
        resetState()
        log("starting local KSuRoot payload: " + payload.library)
        updateStage(EscalationStage.CheckingPermission)
        shell.ensurePermission(
            onGranted = { executor.execute { runBundledFlow(payload) } },
            onDenied = ::cancelForMissingPermission,
        )
    }

    fun startWithLocalPayload(uri: Uri, command: String) {
        selectedBundledPayload = null
        startInternal(LocalPayloadRequest(uri, command.trim()))
    }

    private fun startInternal(localRequest: LocalPayloadRequest) {
        if (stage != EscalationStage.Idle && stage != EscalationStage.Done) return

        resetState()
        log("starting fully local privilege escalation")
        updateStage(EscalationStage.CheckingPermission)
        shell.ensurePermission(
            onGranted = { executor.execute { runFlow(localRequest) } },
            onDenied = ::cancelForMissingPermission,
        )
    }

    fun reset() {
        resetState()
    }

    fun activateKernelSu() {
        mainHandler.post {
            val rootResult = result as? EscalationResult.RootConfirmed ?: return@post
            if (stage != EscalationStage.Done) return@post
            stage = EscalationStage.ActivatingKernelSu
            result = EscalationResult.Idle
            val bundled = selectedBundledPayload
            executor.execute {
                if (bundled == null) runKernelSuActivation(rootResult.suPath)
                else runBundledKernelSuActivation(rootResult.suPath)
            }
        }
    }

    private fun resetState() {
        mainHandler.post {
            stage = EscalationStage.Idle
            result = EscalationResult.Idle
        }
    }

    // StellarShellController 会显示专用权限提示；这里结束流程但不再生成通用失败结果，
    // 避免“需要 Stellar 权限”和“提权失败”两个提示同时出现。
    private fun cancelForMissingPermission() {
        resetState()
    }

    private fun runBundledFlow(payload: KsuRootPayload) {
        try {
            updateStage(EscalationStage.ValidatingCommand)
            val payloadFile = KsuRootPayloadCatalog.fileFor(appContext, payload)
                ?: throw IOException("内置载荷校验失败：" + payload.library)
            val helperFile = File(appContext.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            if (!helperFile.isFile) throw IOException("缺少 KSuRoot helper")
            log("selected payload: " + payload.displayName + " (" + payload.library + ")")
            log("payload SHA-256: " + payload.sha256)
            log("payload command: " + payload.command)
            log("payload source: " + payload.source)

            updateStage(EscalationStage.InstallingResources)
            val cleanup = shell.removeKsuRootFiles()
            if (cleanup.exitCode != 0) throw IOException("清理旧 KSuRoot 文件失败")
            if (shell.installKsuRootHelper(helperFile).exitCode != 0) {
                throw IOException("写入 KSuRoot helper 失败")
            }
            if (shell.installKsuRootPayload(payloadFile).exitCode != 0) {
                throw IOException("写入 KSuRoot 载荷失败")
            }

            updateStage(EscalationStage.ExecutingPayload)
            val command = runtimeKsuRootCommand(payload)
            log("executing KSuRoot command: " + command)
            val execution = shell.executeKsuRoot(
                command + "; rc=\$?; cat '" + KSU_ROOT_LOG_DESTINATION +
                    "' 2>/dev/null; exit \$rc",
            )
            log("KSuRoot output: " + execution.output.trim().ifBlank { "<empty>" })
            log("KSuRoot exit code=" + execution.exitCode)
            if (execution.exitCode != 0) {
                fail(EscalationStage.ExecutingPayload, "KSuRoot command exited with code " + execution.exitCode)
                return
            }

            updateStage(EscalationStage.CheckingRoot)
            val payloadOutput = execution.output.trim()
            val rootCheck = findRootSu()
            if (rootCheck == null) {
                log("root detected=false")
                updateStage(EscalationStage.Done)
                mainHandler.post {
                    result = EscalationResult.Failure(
                        EscalationStage.CheckingRoot,
                        "uid=0 was not found from any available su path",
                    )
                }
                return
            }
            val output = listOf(payloadOutput, rootCheck.output)
                .filter(String::isNotBlank)
                .joinToString("\n")
            log("root detected=true via " + rootCheck.path)
            updateStage(EscalationStage.Done)
            mainHandler.post {
                result = EscalationResult.RootConfirmed(output, rootCheck.path)
            }
        } catch (error: Throwable) {
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            fail(EscalationStage.ExecutingPayload, reason)
        }
    }

    private fun runtimeKsuRootCommand(payload: KsuRootPayload): String {
        var command = payload.command
            .replace("<payload>", STELLAR_KSU_PAYLOAD_DESTINATION)
            .replace("/data/local/tmp/preload.so", STELLAR_KSU_PAYLOAD_DESTINATION)
            .replace("/data/local/tmp/mi_mt6895_43499", STELLAR_KSU_PAYLOAD_DESTINATION)
            .replace("<su>", "/system/bin/id")
        payload.kernelVersion?.substringAfterLast('.')?.let {
            command = command.replace("<内核版本的后三位>", it)
        }
        return if (command.contains('<') || command.contains('>')) {
            "LD_PRELOAD=" + STELLAR_KSU_PAYLOAD_DESTINATION + " /system/bin/id"
        } else {
            command
        }
    }

    private fun runBundledKernelSuActivation(suPath: String) {
        var failure: Throwable? = null
        var cachedScript: File? = null
        var output = ""
        try {
            cachedScript = prepareKernelSuActivationScript()
            val installation = shell.installKernelSuActivationScript(cachedScript)
            if (installation.exitCode != 0) {
                throw IOException("KernelSU activation script installation failed")
            }
            val execution = executeSuCommand(
                suPath,
                "/system/bin/sh '" + KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION + "'; " +
                    "rc=\$?; cat '" + KERNEL_SU_ACTIVATION_SCRIPT_LOG +
                    "' 2>/dev/null; exit \$rc",
            )
            output = execution.output.trim()
            if (execution.exitCode != 0) {
                throw IOException("KernelSU activation script exited with code " + execution.exitCode)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { shell.removeKernelSuActivationScript() }
            runCatching { shell.removeKsuRootFiles() }
            cachedScript?.delete()
        }
        val activationFailure = failure
        if (activationFailure != null) {
            val reason = activationFailure.message?.takeIf(String::isNotBlank)
                ?: activationFailure.javaClass.simpleName
            shell.appendOutput("[KernelSU 激活失败] " + reason, isError = true)
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivationFailure(reason)
            }
        } else {
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivated(output)
            }
        }
    }

    private fun runFlow(localRequest: LocalPayloadRequest) {
        try {
            updateStage(EscalationStage.ValidatingCommand)
            val validatedCommand = validateCommand(localRequest.command)
            if (validatedCommand.isEmpty()) {
                fail(EscalationStage.ValidatingCommand, "local payload command is empty or invalid")
                return
            }
            log("local payload command validated: $validatedCommand")
            log("local payload su path fixed at $LOCAL_PAYLOAD_SU_PATH")
            val prepared = prepareLocalPayload(localRequest.uri)
            log("fixed resource destination: $STELLAR_PAYLOAD_DESTINATION")
            log("fixed resource mode: $STELLAR_PAYLOAD_MODE")

            try {
                updateStage(EscalationStage.InstallingResources)
                log("removing old resource: $STELLAR_PAYLOAD_DESTINATION")
                val removal = shell.removeExistingPayload()
                if (removal.output.isNotBlank()) log("old resource removal output: ${removal.output.trim()}")
                log("old resource removal exit code=${removal.exitCode}")
                if (removal.exitCode != 0) {
                    fail(
                        EscalationStage.InstallingResources,
                        "failed to remove old resource at $STELLAR_PAYLOAD_DESTINATION",
                    )
                    return
                }
                log("old resource removed successfully")
                log("writing resource to $STELLAR_PAYLOAD_DESTINATION")
                val install = shell.installPayload(prepared.file)
                if (install.output.isNotBlank()) log("resource install output: ${install.output.trim()}")
                log("resource install exit code=${install.exitCode}")
                if (install.exitCode != 0) {
                    fail(EscalationStage.InstallingResources, "resource install failed with code ${install.exitCode}")
                    return
                }
                log("resource installed successfully: $STELLAR_PAYLOAD_DESTINATION")
                log("resource permission set successfully: $STELLAR_PAYLOAD_MODE")
            } finally {
                prepared.file.delete()
            }

            updateStage(EscalationStage.ExecutingPayload)
            log("executing local payload command: $validatedCommand")
            val execution = executeCommand(validatedCommand)
            log("local command output: ${execution.output.trim().ifBlank { "<empty>" }}")
            log("local command exit code=${execution.exitCode}")
            if (execution.exitCode != 0) {
                log("local command exit code is non-zero; continuing with the fixed su availability check")
            }

            updateStage(EscalationStage.CheckingRoot)
            log("checking root with su path: $LOCAL_PAYLOAD_SU_PATH -c 'id'")
            val rootCheck = executeSuCommand(LOCAL_PAYLOAD_SU_PATH, ROOT_CHECK_INNER_COMMAND)
            val rootOutput = rootCheck.output.trim()
            val isRoot = ROOT_UID_PATTERN.containsMatchIn(rootOutput)
            log("root check output: $rootOutput")
            log("root check exit code=${rootCheck.exitCode} (not used for success decision)")
            log("root detected=$isRoot (based on uid=0 output)")

            updateStage(EscalationStage.Done)
            mainHandler.post {
                result = if (isRoot) {
                    EscalationResult.RootConfirmed(
                        execution.output.trim().ifBlank { rootOutput },
                        LOCAL_PAYLOAD_SU_PATH,
                    )
                } else {
                    EscalationResult.Failure(
                        EscalationStage.CheckingRoot,
                        "uid=0 was not found in output of $LOCAL_PAYLOAD_SU_PATH -c 'id'",
                    )
                }
            }
        } catch (error: Throwable) {
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            fail(stage, reason)
        }
    }

    private fun prepareLocalPayload(uri: Uri): PreparedPayload {
        updateStage(EscalationStage.ImportingLocalResource)
        val resolver = appContext.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.replace(Regex("[\\r\\n]"), " ")?.take(MAX_LOCAL_DISPLAY_NAME_LENGTH)
            ?.ifBlank { null }
            ?: "selected payload"

        val cacheDirectory = File(appContext.cacheDir, LOCAL_PAYLOAD_CACHE_DIRECTORY)
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Unable to create the local payload cache")
        }
        val target = File.createTempFile(LOCAL_PAYLOAD_PREFIX, ".bin", cacheDirectory)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Unable to open the selected local payload")
            input.use { source ->
                FileOutputStream(target).use { destination ->
                    val buffer = ByteArray(LOCAL_COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copiedBytes += read
                        if (copiedBytes > MAX_RESOURCE_BYTES) {
                            throw IOException("Selected local payload exceeds the 512 MiB limit")
                        }
                        destination.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    destination.fd.sync()
                }
            }
            if (copiedBytes == 0L) throw IOException("Selected local payload is empty")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            log("local payload selected: $displayName")
            log("local payload size: $copiedBytes bytes")
            log("local payload SHA-256: $sha256")
            PreparedPayload(target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun runKernelSuActivation(suPath: String) {
        val outputs = mutableListOf<String>()
        var failure: Throwable? = null
        var cachedScript: File? = null
        try {
            cachedScript = prepareKernelSuActivationScript()
            log("installing GhostLock-style KernelSU activation script")
            val installation = shell.installKernelSuActivationScript(cachedScript)
            if (installation.output.isNotBlank()) outputs += installation.output.trim()
            if (installation.exitCode != 0) {
                throw IOException("KernelSU activation script installation exited with code ${installation.exitCode}")
            }

            log("running KernelSU activation script through temporary su")
            val execution = executeSuCommand(
                suPath,
                "/system/bin/sh '$KERNEL_SU_ACTIVATION_SCRIPT_DESTINATION'; " +
                    "rc=\$?; cat '$KERNEL_SU_ACTIVATION_SCRIPT_LOG' 2>/dev/null; exit \$rc",
            )
            log("KernelSU activation script exit code=${execution.exitCode}")
            if (execution.output.isNotBlank()) {
                val output = execution.output.trim()
                outputs += output
                log("KernelSU activation script output: $output")
            }
            if (execution.exitCode != 0) {
                throw IOException("KernelSU activation script exited with code ${execution.exitCode}")
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { shell.removeKernelSuActivationScript() }
                .onSuccess { cleanup ->
                    log("KernelSU activation script cleanup exit code=${cleanup.exitCode}")
                    if (cleanup.output.isNotBlank()) log(cleanup.output.trim())
                }
                .onFailure { error ->
                    shell.appendOutput(
                        "[KernelSU 脚本清理失败] ${error.message ?: error.javaClass.simpleName}",
                        isError = true,
                    )
                }
            cachedScript?.delete()
        }

        val activationFailure = failure
        if (activationFailure != null) {
            val reason = activationFailure.message?.takeIf(String::isNotBlank)
                ?: activationFailure.javaClass.simpleName
            shell.appendOutput("[KernelSU 激活失败] $reason", isError = true)
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivationFailure(reason)
            }
        } else {
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivated(outputs.joinToString("\n"))
            }
        }
    }

    private fun prepareKernelSuActivationScript(): File {
        val target = File.createTempFile("yhroot-ksu-", ".sh", appContext.cacheDir)
        return try {
            appContext.assets.open(KERNEL_SU_ACTIVATION_ASSET).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    // 本地命令限制为单条 shell 命令，UTF-8 最多 1024 字节，
    // 禁止 NUL、分号、&、|| 或换行；允许单个管道、重定向、引号及命令替换。
    private fun validateCommand(command: String): String {
        val cmd = command.trim()
        if (cmd.isEmpty() || cmd.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) return ""
        if (cmd.indexOf('\u0000') >= 0 || FORBIDDEN_COMMAND_PATTERN.containsMatchIn(cmd)) return ""
        return cmd
    }

    private fun validateSuPath(value: String): String {
        val path = value.trim()
        if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > MAX_SU_PATH_BYTES) return ""
        if (!SU_PATH_PATTERN.matches(path)) return ""
        if (path.split('/').any { it == "." || it == ".." }) return ""
        return path
    }

    // 通过 Stellar shell 执行命令，合并 stdout/stderr，并把输出和退出码作为同一个结果返回。
    @Suppress("DEPRECATION")
    private fun executeCommand(command: String): CommandExecution {
        val wrappedCommand = "$command 2>&1"
        val process = Stellar.newProcess(arrayOf("sh", "-c", wrappedCommand), null, "/")
        return try {
            // 预设中的非交互 sh 需要立即收到 EOF，否则会持续等待标准输入。
            process.outputStream.close()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            CommandExecution(out, process.waitFor())
        } finally {
            runCatching { process.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    private fun executeSuCommand(suPath: String, innerCommand: String): CommandExecution {
        require(validateSuPath(suPath) == suPath) { "Invalid su path" }
        return executeProcess(arrayOf(suPath, "-c", innerCommand))
    }

    private fun findRootSu(): RootCheck? {
        for (path in ROOT_SU_CANDIDATES) {
            try {
                val check = executeSuCommand(path, ROOT_CHECK_INNER_COMMAND)
                val output = check.output.trim()
                log("root check " + path + " exit=" + check.exitCode + ": " + output.ifBlank { "<empty>" })
                if (ROOT_UID_PATTERN.containsMatchIn(output)) {
                    return RootCheck(path, output)
                }
            } catch (error: Throwable) {
                log("root check " + path + " unavailable")
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun executeProcess(arguments: Array<String>): CommandExecution {
        val nullableArguments = Array<String?>(arguments.size) { index -> arguments[index] }
        val process = Stellar.newProcess(nullableArguments, null, "/")
        return try {
            process.outputStream.close()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val output = listOf(stdout, stderr)
                .filter(String::isNotBlank)
                .joinToString("\n")
            CommandExecution(output, process.waitFor())
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun updateStage(next: EscalationStage) {
        mainHandler.post { stage = next }
    }

    private fun log(message: String) {
        shell.appendOutput("[提权] $message")
    }

    private fun fail(stage: EscalationStage, reason: String) {
        mainHandler.post {
            this.stage = EscalationStage.Done
            this.result = EscalationResult.Failure(stage, reason)
        }
        shell.appendOutput("[提权失败][$stage] $reason", isError = true)
    }

    override fun close() {
        executor.shutdownNow()
    }

    private data class PreparedPayload(val file: File)

    private data class LocalPayloadRequest(
        val uri: Uri,
        val command: String,
    )

    private data class CommandExecution(
        val output: String,
        val exitCode: Int,
    )

    private companion object {
        val FORBIDDEN_COMMAND_PATTERN = Regex("[;\\r\\n&]|\\|\\|")
        val SU_PATH_PATTERN = Regex("^/(?:[A-Za-z0-9_+@.-]+/)*[A-Za-z0-9_+@.-]+$")
        val ROOT_UID_PATTERN = Regex("(?:^|\\s)uid=0(?:\\(root\\))?(?:\\s|\\z)")
        const val MAX_COMMAND_BYTES = 1024
        const val MAX_SU_PATH_BYTES = 256
        const val MAX_RESOURCE_BYTES = 512L * 1024L * 1024L
        const val LOCAL_PAYLOAD_SU_PATH = "/data/local/tmp/su"
        const val MAX_LOCAL_DISPLAY_NAME_LENGTH = 128
        const val LOCAL_COPY_BUFFER_BYTES = 64 * 1024
        const val LOCAL_PAYLOAD_CACHE_DIRECTORY = "local-payloads"
        const val LOCAL_PAYLOAD_PREFIX = "payload-"
        const val ROOT_CHECK_INNER_COMMAND = "id"
        const val KERNEL_SU_ACTIVATION_ASSET = "yhroot_ksu_activate.sh"
        val ROOT_SU_CANDIDATES = listOf(
            LOCAL_PAYLOAD_SU_PATH,
            "/apex/com.android.virt/bin/su",
            "/product/bin/su",
            "/debug_ramdisk/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/odm/bin/su",
        )
    }

    private data class RootCheck(
        val path: String,
        val output: String,
    )
}
