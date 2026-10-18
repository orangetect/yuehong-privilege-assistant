package roro.stellar.yuehong.ghostlock

import android.content.Context
import com.ghostlock.app.data.HoconSupport
import com.ghostlock.app.data.NativeProfileDocument
import com.ghostlock.app.data.ValueMap
import com.ghostlock.app.data.asValueMap
import com.ghostlock.app.data.asValueList
import com.ghostlock.app.data.profile.CpuPairView
import com.ghostlock.app.data.profile.ProfileMerger
import com.ghostlock.app.data.profile.ProfileResolver
import com.ghostlock.app.data.route.RouteKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class GhostLockProfileSource { CACHE, APK_BUILTIN, OTA }

data class GhostLockResolvedProfile(
    val release: String,
    val bytes: ByteArray,
    val source: GhostLockProfileSource,
)

/** Resolves the assistant's cache → bundled profile → OTA profile chain. */
object GhostLockProfileResolver {
    private const val BUILTIN_DIRECTORY = "kernel_profiles"
    private const val CACHE_DIRECTORY = "ghostlock_profiles"
    private val safeRelease = Regex("[A-Za-z0-9._+-]+")

    @JvmStatic
    fun hasBuiltin(context: Context, release: String): Boolean =
        runCatching { builtinEntry(context, release) != null }.getOrDefault(false)

    @JvmStatic
    fun loadCached(context: Context, release: String): GhostLockResolvedProfile? {
        val file = cacheFile(context, release) ?: return null
        if (!file.isFile) return null
        return runCatching {
            resolveText(context, release, file.readText(Charsets.UTF_8), GhostLockProfileSource.CACHE)
        }.getOrNull()
    }

    @JvmStatic
    fun loadBuiltin(context: Context, release: String): GhostLockResolvedProfile? {
        return runCatching {
            val document = builtinEntry(context, release) ?: return null
            resolveDocument(context, release, document, GhostLockProfileSource.APK_BUILTIN)
        }.getOrNull()
    }

    /** Validates, persists, and serializes the upstream extractor's flattened HOCON profile. */
    @JvmStatic
    @Throws(IOException::class, IllegalArgumentException::class)
    fun parseAndCache(context: Context, release: String, profileFile: File): GhostLockResolvedProfile {
        require(profileFile.isFile) { "OTA profile was not written: ${profileFile.absolutePath}" }
        val text = profileFile.readText(Charsets.UTF_8)
        val resolved = resolveText(context, release, text, GhostLockProfileSource.OTA)
        val target = cacheFile(context, release)
            ?: throw IllegalArgumentException("invalid kernel release: $release")
        val parent = target.parentFile ?: throw IOException("profile cache has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("cannot create profile cache: ${parent.absolutePath}")
        }
        val temporary = File(parent, "${target.name}.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
        return resolved
    }

    private fun resolveText(
        context: Context,
        release: String,
        text: String,
        source: GhostLockProfileSource,
    ): GhostLockResolvedProfile {
        val profile = HoconSupport.parseValue(text).asValueMap()
            ?: throw IllegalArgumentException("profile is not a HOCON object")
        return resolveDocument(context, release, profile, source)
    }

    private fun resolveDocument(
        context: Context,
        release: String,
        document: ValueMap,
        source: GhostLockProfileSource,
    ): GhostLockResolvedProfile {
        require(safeRelease.matches(release)) { "invalid kernel release: $release" }
        require(document["release"] == release) {
            "profile release mismatch: expected $release, got ${document["release"]}"
        }
        require((document["schema_version"] as? Number)?.toInt() == 1) {
            "unsupported GhostLock profile schema"
        }

        val route = routeNameOf(document)
        val fallback = fallbackOf(document)
        val loader = AssetLoader(context)
        val tuning = parseAsset(loader, "$BUILTIN_DIRECTORY/execution-tuning.conf")
            ?.get("execution").asValueMap()
        val presets = linkedMapOf<String, ValueMap>()
        for (kind in RouteKind.entries) {
            val path = "$BUILTIN_DIRECTORY/execution-${kind.token.replace('_', '-')}.conf"
            val execution = parseAsset(loader, path)?.get("execution").asValueMap()
            val routeValues = execution?.get("routes").asValueMap()?.get(kind.token).asValueMap()
            if (routeValues != null) presets[kind.token] = routeValues
        }
        val merged = ProfileMerger.resolveMerged(
            deviceRelease = release,
            builtin = document,
            imported = null,
            overrides = null,
            tuningExecution = tuning,
            pair = CpuPairView(0, 1),
            routePresets = presets,
        )
        val errors = ProfileResolver.validateMerged(merged, route, fallback)
        require(errors.isEmpty()) {
            "profile validation failed: ${errors.joinToString { it.fieldPath }}"
        }

        val blob = NativeProfileDocument.from(release, route, fallback) { path ->
            ProfileResolver.nativeValue(merged, route, fallback, path)
        }.toBinary()
        val decoded = NativeProfileDocument.fromBinary(blob)
            ?: throw IllegalArgumentException("cannot encode GhostLock profile")
        require(decoded.release == release) { "encoded profile release mismatch" }
        return GhostLockResolvedProfile(release, blob, source)
    }

    private fun builtinEntry(context: Context, release: String): ValueMap? {
        if (!safeRelease.matches(release)) return null
        val loader = AssetLoader(context)
        val index = parseAsset(loader, "$BUILTIN_DIRECTORY/index.conf") ?: return null
        val entries = index["profiles"].asValueList() ?: return null
        val entry = entries.asSequence()
            .mapNotNull { it.asValueMap() }
            .firstOrNull { it["release"] == release }
            ?: return null
        val file = entry["file"] as? String ?: return null
        val document = parseAsset(loader, "$BUILTIN_DIRECTORY/$file") ?: return null
        require(document["release"] == release) { "profile index release mismatch" }
        return document
    }

    private fun routeNameOf(profile: ValueMap): String? = when (val route = profile["route"]) {
        is String -> route
        is Map<*, *> -> RouteKind.entries.firstOrNull { it.token in route.keys }?.token
        else -> null
    }

    private fun fallbackOf(profile: ValueMap): String? {
        val fallback = profile["fallback"].asValueMap()
        return (fallback?.get("to") as? String)
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: (profile["fallback_to"] as? String)?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun parseAsset(loader: AssetLoader, path: String): ValueMap? =
        HoconSupport.parseValue(loader.load(path)).asValueMap()

    private fun cacheFile(context: Context, release: String): File? {
        if (!safeRelease.matches(release)) return null
        return File(File(context.filesDir, CACHE_DIRECTORY), "$release.conf")
    }

    private class AssetLoader(private val context: Context) {
        fun load(path: String): String = expand(path, linkedSetOf())

        private fun expand(path: String, visiting: MutableSet<String>): String {
            if (!visiting.add(path)) return ""
            val text = runCatching {
                context.assets.open(path).bufferedReader().use { it.readText() }
            }.getOrElse {
                visiting.remove(path)
                return ""
            }
            val directory = path.substringBeforeLast('/', "")
            val expanded = StringBuilder()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                val include = when {
                    trimmed.startsWith("#") || trimmed.startsWith("//") -> null
                    trimmed.startsWith("include ") ->
                        trimmed.removePrefix("include ").trim().trim('"').takeIf(String::isNotEmpty)
                    else -> null
                }
                if (include == null) {
                    expanded.append(line).append('\n')
                } else {
                    val nested = if (directory.isEmpty()) include else "$directory/$include"
                    expanded.append(expand(nested, visiting)).append('\n')
                }
            }
            visiting.remove(path)
            return expanded.toString()
        }
    }
}
