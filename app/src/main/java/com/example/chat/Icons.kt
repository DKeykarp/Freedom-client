package com.example.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Свой набор тонких линейных иконок — те же контуры (viewBox 24x24, stroke), что в server/web/icons.js,
 * ради единого стиля между веб- и Android-клиентом. Собраны из "d"-строк SVG-путей через PathParser, а не
 * через material-icons-extended: этой библиотеки нет в зависимостях (см. app/build.gradle.kts), а тащить
 * несколько мегабайт ради десятка иконок ни к чему.
 */
private fun lineIcon(name: String, vararg d: String): ImageVector {
    val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (path in d) {
        b.addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),   // цвет всё равно переопределяет Icon(tint = …)
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object FIcons {
    val Message: ImageVector by lazy {
        lineIcon("message", "M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8z")
    }
    val Users: ImageVector by lazy {
        lineIcon("users",
            "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
            "M23 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75")
    }
    val Compass: ImageVector by lazy {
        lineIcon("compass", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z",
            "M16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76z")
    }
    val Channel: ImageVector by lazy {
        // «вещание»: точка-источник и две расходящиеся дуги — тот же приём с дугами, что и в Compass/More
        lineIcon("channel",
            "M5 12a1.4 1.4 0 1 0 0 -2.8a1.4 1.4 0 0 0 0 2.8z",
            "M8 8a6.5 6.5 0 0 1 0 8",
            "M11 5a11 11 0 0 1 0 14")
    }
    val Logout: ImageVector by lazy {
        lineIcon("logout", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17 21 12 16 7", "M21 12H9")
    }
    val Lock: ImageVector by lazy {
        lineIcon("lock", "M3 11h18v11H3z", "M7 11V7a5 5 0 0 1 10 0v4")
    }
    val Videocam: ImageVector by lazy {
        lineIcon("videocam", "M23 7 16 12 23 17 23 7z", "M1 5h15v14H1z")
    }
    val Mic: ImageVector by lazy {
        lineIcon("mic", "M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z",
            "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v4", "M8 23h8")
    }
    val Play: ImageVector by lazy { lineIcon("play", "M6 3 20 12 6 21 6 3z") }
    val Pause: ImageVector by lazy {
        lineIcon("pause", "M6 4h4v16H6z", "M14 4h4v16h-4z")
    }
    val Close: ImageVector by lazy { lineIcon("close", "M18 6 6 18", "M6 6 18 18") }
    val Image: ImageVector by lazy {
        lineIcon("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M21 15 16 10 5 21")
    }
    val Reply: ImageVector by lazy {
        lineIcon("reply", "M9 17L4 12L9 7", "M20 18v-2a4 4 0 0 0-4-4H4")
    }
    val Edit: ImageVector by lazy {
        lineIcon("edit", "M12 20h9", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z")
    }
    val Trash: ImageVector by lazy {
        lineIcon("trash", "M3 6L5 6L21 6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z")
    }
    val File: ImageVector by lazy {
        lineIcon("file", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z", "M14 2L14 8L20 8")
    }
    val More: ImageVector by lazy {
        lineIcon("more",
            "M12 6.5a1.5 1.5 0 1 0 0 -3a1.5 1.5 0 0 0 0 3z",
            "M12 13.5a1.5 1.5 0 1 0 0 -3a1.5 1.5 0 0 0 0 3z",
            "M12 20.5a1.5 1.5 0 1 0 0 -3a1.5 1.5 0 0 0 0 3z")
    }
    val Download: ImageVector by lazy {
        lineIcon("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10L12 15L17 10", "M12 15L12 3")
    }
    val Check: ImageVector by lazy { lineIcon("check", "M20 6L9 17L4 12") }
    val Forward: ImageVector by lazy {
        lineIcon("forward", "M15 17L20 12L15 7", "M4 18v-2a4 4 0 0 1 4-4h12")
    }
    val Pin: ImageVector by lazy {
        // окружность (та же проверенная формула, что у Compass/Users/More, r=5 вместо 10/1.5) + ножка
        lineIcon("pin", "M12 14a5 5 0 1 0 0 -10a5 5 0 0 0 0 10z", "M12 14L12 22")
    }
    val Poll: ImageVector by lazy {
        // три столбика по возрастанию — прямые линии, тот же смысл, что у веб-иконки (там залитые rect)
        lineIcon("poll", "M5 20L5 13", "M12 20L12 9", "M19 20L19 4")
    }
    val Sticker: ImageVector by lazy {
        // тот же контур, что у веб-иконки (icons.js): скруглённый квадрат с загнутым уголком.
        // Android PathParser разбирает те же данные пути, что и SVG — путь переносится дословно.
        lineIcon("sticker", "M4 8a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v6a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4z", "M14 4v6a4 4 0 0 0 4 4h4")
    }
    val Clock: ImageVector by lazy {
        // окружность (та же проверенная формула, r=9) + стрелки
        lineIcon("clock", "M12 21a9 9 0 1 0 0 -18a9 9 0 0 0 0 18z", "M12 12L12 7", "M12 12L16 14")
    }
    val UnmutedBell: ImageVector by lazy {
        // упрощённый колокольчик — только прямые линии, без кривых (риск ниже, чем у точной формы)
        lineIcon("unmuted_bell", "M12 5L7 14L17 14Z", "M9 16L15 16")
    }
    val MutedBell: ImageVector by lazy {
        lineIcon("muted_bell", "M12 5L7 14L17 14Z", "M9 16L15 16", "M4 4L20 20")
    }
    val Archive: ImageVector by lazy {
        lineIcon("archive", "M4 8L4 19L20 19L20 8Z", "M3 5L21 5L21 8L3 8Z", "M10 13L14 13")
    }
}
