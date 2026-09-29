package roro.stellar.yuehong.ghostlock

import com.kernelpack.KernelPack
import com.kernelpack.PackRequest
import com.kernelpack.policy.GateDecision
import com.kernelpack.profile.BaselineRegistry
import com.kernelpack.profile.BaselineScheme
import java.util.function.Consumer

/** Small Java-facing bridge around the pure Kotlin KSuRoot kernel packer. */
data class DynamicBuildResult(
    val payload: ByteArray?,
    val summary: String,
    val warnings: String,
)

object DynamicPayloadEngine {
    @JvmStatic
    fun build(boot: ByteArray, base: ByteArray, baselineId: String, logger: Consumer<String>): DynamicBuildResult {
        val result = KernelPack.pack(
            PackRequest(
                bootImage = boot,
                baseLibrary = base,
                baseline = BaselineRegistry.byId(baselineId)?.profile,
                scheme = if (baselineId.startsWith("PD2520-")) BaselineScheme.VIVO else BaselineScheme.UNIVERSAL,
                log = { line -> logger.accept(line) },
            ),
        )
        return DynamicBuildResult(
            result.packedLibrary,
            (result.gate as? GateDecision.Blocked)?.let { "${it.title}: ${it.detail.joinToString("；")}" }
                ?: result.summary(),
            result.warnings.joinToString("\n"),
        )
    }
}
