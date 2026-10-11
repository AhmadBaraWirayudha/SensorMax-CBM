package com.research.sensormax

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** App-level language choices exposed by the pinned preferences bar. */
enum class SensorMaxLanguage(val localeTag: String) {
    ENGLISH("en"),
    INDONESIAN("id"),
    SIMPLIFIED_CHINESE("zh-CN"),
    SYSTEM("")
}

/** Persisted user preferences; never stores measurement samples or credentials. */
object SensorMaxPreferences {
    private const val PREFS_NAME = "sensormax_display_preferences"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_UNITS = "measurement_system"

    fun language(context: Context): SensorMaxLanguage {
        val stored = preferences(context).getString(KEY_LANGUAGE, SensorMaxLanguage.ENGLISH.name)
        return runCatching { SensorMaxLanguage.valueOf(stored ?: SensorMaxLanguage.ENGLISH.name) }
            .getOrDefault(SensorMaxLanguage.ENGLISH)
    }

    fun setLanguage(context: Context, language: SensorMaxLanguage) {
        preferences(context).edit().putString(KEY_LANGUAGE, language.name).apply()
        applyLanguage(language)
    }

    /** Apply persisted locale before inflating views so all resource strings are consistent. */
    fun applySavedLanguage(context: Context) {
        applyLanguage(language(context))
    }

    fun units(context: Context): MeasurementSystem {
        val stored = preferences(context).getString(KEY_UNITS, MeasurementSystem.METRIC.name)
        return runCatching { MeasurementSystem.valueOf(stored ?: MeasurementSystem.METRIC.name) }
            .getOrDefault(MeasurementSystem.METRIC)
    }

    fun setUnits(context: Context, units: MeasurementSystem) {
        preferences(context).edit().putString(KEY_UNITS, units.name).apply()
    }

    private fun applyLanguage(language: SensorMaxLanguage) {
        val desired = LocaleListCompat.forLanguageTags(language.localeTag)
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
