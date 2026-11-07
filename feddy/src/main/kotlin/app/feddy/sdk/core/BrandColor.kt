package app.feddy.sdk.core

import kotlin.math.pow

/** A brand colour from the project config, parsed from "#RRGGBB" or "#RGB". */
internal data class Rgb(val red: Double, val green: Double, val blue: Double) {
    /** Relative luminance (WCAG), used only to pick black or white text. */
    val luminance: Double
        get() {
            fun channel(value: Double) =
                if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
        }

    /**
     * A light brand colour needs dark text on top of it. Measured as the
     * contrast white would actually get: 3:1 is what WCAG asks of a UI
     * component. Same formula as the iOS SDK.
     */
    val needsDarkText: Boolean get() = 1.05 / (luminance + 0.05) < 3

    val argb: Int
        get() = (0xFF shl 24) or
            ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or
            (blue * 255).toInt()

    companion object {
        fun parse(hex: String?): Rgb? {
            var value = hex?.trim() ?: return null
            if (value.startsWith("#")) value = value.substring(1)
            if (value.length == 3) value = value.map { "$it$it" }.joinToString("")
            if (value.length != 6) return null
            val raw = value.toLongOrNull(16) ?: return null
            return Rgb(
                ((raw shr 16) and 0xFF) / 255.0,
                ((raw shr 8) and 0xFF) / 255.0,
                (raw and 0xFF) / 255.0,
            )
        }

        fun fromArgb(argb: Int) = Rgb(
            ((argb shr 16) and 0xFF) / 255.0,
            ((argb shr 8) and 0xFF) / 255.0,
            (argb and 0xFF) / 255.0,
        )
    }
}
