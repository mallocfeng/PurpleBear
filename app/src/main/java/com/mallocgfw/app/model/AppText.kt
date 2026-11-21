package com.mallocgfw.app.model

import java.util.Locale

/** Display languages of the app (see AppLanguage for the user setting). */
enum class TextLanguage { Chinese, English, Russian }

/**
 * The language user-facing messages are built in, for code outside Compose:
 * the VPN service and its notifications, diagnostics, background sync, error
 * messages. The UI sets it whenever the resolved language changes; services
 * set it from the saved settings, so they follow the app even with no UI open.
 *
 * Text that is stored (node descriptions, group names) must not use [tr]: it
 * would be frozen in whatever language was active when it was saved.
 */
object AppTextLanguage {
    @Volatile
    var current: TextLanguage = fromLocale()

    fun apply(language: AppLanguage) {
        current = when (language) {
            AppLanguage.Chinese -> TextLanguage.Chinese
            AppLanguage.English -> TextLanguage.English
            AppLanguage.Russian -> TextLanguage.Russian
            AppLanguage.System -> fromLocale()
        }
    }

    fun fromLocale(): TextLanguage = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "zh" -> TextLanguage.Chinese
        "ru" -> TextLanguage.Russian
        else -> TextLanguage.English
    }
}

/** A message in the current app language (see [AppTextLanguage]). */
fun tr(zh: String, en: String, ru: String): String = when (AppTextLanguage.current) {
    TextLanguage.Chinese -> zh
    TextLanguage.English -> en
    TextLanguage.Russian -> ru
}
