package roro.stellar.yuehong.ghostlock

import com.ghostlock.app.data.ota.OtaPayloadExtractor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.function.Consumer

/** Java bridge to the upstream Android HTTP Range OTA partition extractor. */
object GhostLockOtaPartitionExtractor {
    @JvmStatic
    fun extractBlocking(
        url: String,
        workDir: File,
        onLog: Consumer<String>,
    ): OtaPayloadExtractor.ExtractedPartitions = runBlocking {
        OtaPayloadExtractor.extractPartitions(url, workDir) { message ->
            onLog.accept(message)
        }
    }
}
