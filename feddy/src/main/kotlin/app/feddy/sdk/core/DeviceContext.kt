package app.feddy.sdk.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import app.feddy.sdk.BuildConfig
import java.util.Locale
import java.util.TimeZone

/**
 * What every message says about where it came from. Keys for platform
 * "android" match iOS, so the dashboard renders both with one schema.
 */
internal class DeviceContext(context: Context) : ContextSource {
    private val appContext = context.applicationContext

    override val platform: String = "android"

    @Volatile
    var wrapper: Pair<String, String>? = null

    private val appVersion: Pair<String, String> by lazy {
        try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
            (info.versionName ?: "") to code
        } catch (_: PackageManager.NameNotFoundException) {
            "" to ""
        }
    }

    override fun context(): Map<String, String> {
        val context = linkedMapOf(
            "app_version" to appVersion.first,
            "build" to appVersion.second,
            "os_version" to "Android ${Build.VERSION.RELEASE}",
            "device_model" to deviceModel(Build.MANUFACTURER, Build.MODEL),
            "locale" to locale(),
            // IANA name ("Europe/Berlin"): the dashboard shows the user's local time.
            "timezone" to TimeZone.getDefault().id,
            "sdk_version" to BuildConfig.SDK_VERSION,
        )
        wrapper?.let { (name, version) ->
            context["sdk"] = name
            context["sdk_wrapper_version"] = version
        }
        return context
    }

    /** The user's first preferred language, the same value on every request. */
    override fun locale(): String {
        val locale = if (Build.VERSION.SDK_INT >= 24) LocaleList.getDefault()[0] else Locale.getDefault()
        return locale.toLanguageTag()
    }

    companion object {
        /**
         * "Samsung SM-S928B", "Google Pixel 9". The model code alone does not
         * say who made it, and some manufacturers already start the model
         * with their name, which should not be said twice.
         */
        fun deviceModel(manufacturer: String?, model: String?): String {
            val maker = manufacturer.orEmpty().trim()
            val name = model.orEmpty().trim()
            if (name.isEmpty()) return maker.ifEmpty { "unknown" }
            if (maker.isEmpty() || name.startsWith(maker, ignoreCase = true)) return name
            val capitalized = maker.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            return "$capitalized $name"
        }
    }
}
