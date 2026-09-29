package roro.stellar.yuehong.ghostlock

import com.ghostlock.app.data.LegacyProfileConverter
import com.ghostlock.app.data.NativeProfileDocument
import com.ghostlock.app.data.ValueMap
import com.ghostlock.app.data.asValueMap
import com.ghostlock.app.data.profile.CpuPairView
import com.ghostlock.app.data.profile.ProfileMerger
import com.ghostlock.app.data.profile.ProfileResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts the existing offsets.json store to the upstream GhostLock GLK1
 * transport. The Activity keeps its JSON import/export and OTA flow; only the
 * native handoff changes to the typed profile stream.
 */
object GhostLockProfileBridge {
    private val executionDefaults: ValueMap = linkedMapOf(
        "recommended_cpus" to linkedMapOf("main" to 0L, "consumer" to 1L),
        "heap" to linkedMapOf(
            "prepare_max_attempts" to 4L,
            "prepare_timeout_ms" to 240000L,
            "kernelsnitch_timeout_ms" to 60000L,
        ),
        "race" to linkedMapOf(
            "route_wait_ms" to 1000L,
            "route_done_timeout_ms" to 300000L,
            "setup_settle_us" to 50000L,
            "state_poll_interval_us" to 1000L,
        ),
        "stages" to linkedMapOf(
            "w1_attempts" to 15L,
            "w1_settle_us" to 100000L,
            "w1_scratch_repair_attempts" to 3L,
            "w2_attempts" to 15L,
            "w2_settle_us" to 100000L,
            "w3_chain_rounds" to 3L,
            "w3_attempts" to 6L,
            "w3_settle_us" to 50000L,
        ),
        "handoff" to linkedMapOf(
            "pre_dispatch_settle_ms" to 2000L,
            "module_poll_attempts" to 30L,
            "module_poll_interval_ms" to 100L,
            "enforce_poll_attempts" to 200L,
            "enforce_poll_interval_ms" to 100L,
        ),
    )

    private val routePresets: Map<String, ValueMap> = mapOf(
        "select_stack" to linkedMapOf(
            "enter_delay_us" to 50000L,
            "timeout_us" to 200000L,
            "consumer_max_calls" to 1L,
            "consumer_burst_calls" to 1L,
        ),
        "tcp_zerocopy" to linkedMapOf(
            "attempts" to 2000L,
            "arm_sequence" to 16L,
            "post_receive_hold_iterations" to 20000L,
        ),
    )

    @JvmStatic
    fun fromOffsetsJson(text: String, release: String, mainCpu: Int, consumerCpu: Int): ByteArray {
        val entry = findEntry(text, release)
            ?: error("offsets.json has no entry for $release")
        val imported = jsonObjectToMap(entry)
        LegacyProfileConverter.convertValue(imported)
            ?: error("cannot convert offsets.json entry")
        val route = routeName(imported)
            ?: error("offsets.json has no supported route")
        val fallback = fallbackName(imported)
        val merged = ProfileMerger.resolveMerged(
            deviceRelease = release,
            builtin = imported,
            imported = null,
            overrides = null,
            tuningExecution = executionDefaults,
            pair = CpuPairView(mainCpu, consumerCpu),
            routePresets = routePresets,
        )
        val errors = ProfileResolver.validateMerged(merged, route, fallback)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        return NativeProfileDocument.from(
            release = release,
            route = route,
            fallbackTo = fallback,
        ) { path -> ProfileResolver.nativeValue(merged, route, fallback, path) }.toBinary()
    }

    private fun findEntry(text: String, release: String): JSONObject? {
        val root = org.json.JSONTokener(text).nextValue()
        if (root is JSONObject) {
            return if (root.optString("release") == release) root else null
        }
        if (root is JSONArray) {
            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i)
                if (item != null && item.optString("release") == release) return item
            }
        }
        return null
    }

    private fun jsonObjectToMap(value: JSONObject): ValueMap = linkedMapOf<String, Any?>().also { out ->
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = jsonValue(value.opt(key))
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> ArrayList<Any?>().also { out ->
            for (i in 0 until value.length()) out += jsonValue(value.opt(i))
        }
        is Number -> value.toLong()
        is Boolean -> value
        else -> value.toString()
    }

    private fun routeName(profile: Map<String, Any?>): String? {
        val route = profile["route"]
        if (route is String) return route
        if (route is Map<*, *>) {
            return listOf("tcp_zerocopy", "select_stack", "multicast_waiter")
                .firstOrNull { route.containsKey(it) }
        }
        return null
    }

    private fun fallbackName(profile: Map<String, Any?>): String? {
        val fallback = profile["fallback"]
        if (fallback is Map<*, *>) {
            val value = fallback["to"] as? String
            if (!value.isNullOrBlank() && value != "none") return value
        }
        val value = profile["fallback_to"] as? String
        return value?.takeIf { it.isNotBlank() && it != "none" }
    }
}
