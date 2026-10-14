package roro.stellar.yuehong.shell

import android.content.Context
import androidx.annotation.StringRes
import roro.stellar.yuehong.R

enum class JailbreakActivationMode(
    val preferenceValue: String,
    val scriptAsset: String,
    val scriptDestination: String,
    val scriptLogPath: String?,
    @StringRes val labelResource: Int,
) {
    KERNELSU(
        preferenceValue = "kernelsu",
        scriptAsset = "yhroot_ksu_activate.sh",
        scriptDestination = "/data/local/tmp/yhroot_ksu_activate.sh",
        scriptLogPath = "/data/local/tmp/yhroot_ksu_activate.log",
        labelResource = R.string.jailbreak_mode_kernelsu,
    ),
    APATCH(
        preferenceValue = "apatch",
        scriptAsset = "yhroot_ap_activate.sh",
        scriptDestination = "/data/local/tmp/yhroot_ap_activate.sh",
        scriptLogPath = null,
        labelResource = R.string.jailbreak_mode_apatch,
    );

    companion object {
        private const val PREFERENCES = "yuehong_privilege_assistant"
        private const val PREFERENCE_KEY = "jailbreak_activation_mode"
        const val INTENT_EXTRA = "jailbreak_activation_mode"

        @JvmStatic
        fun fromPreferenceValue(value: String?): JailbreakActivationMode? =
            entries.firstOrNull { it.preferenceValue == value }

        @JvmStatic
        fun read(context: Context): JailbreakActivationMode {
            val value = context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PREFERENCE_KEY, null)
            return fromPreferenceValue(value) ?: KERNELSU
        }

        @JvmStatic
        fun write(context: Context, mode: JailbreakActivationMode) {
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFERENCE_KEY, mode.preferenceValue)
                .commit()
        }
    }
}
