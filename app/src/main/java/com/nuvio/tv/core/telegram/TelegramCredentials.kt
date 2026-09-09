// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure validation for user-supplied Telegram API credentials
 * (https://my.telegram.org). Kept free of Android dependencies for unit tests.
 */
object TelegramCredentialsValidator {
    /** Trims and parses; null when blank, non-numeric or not positive. */
    fun parseApiId(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /** A Telegram API hash is 32 hex chars. */
    fun isValidApiHash(raw: String?): Boolean {
        val clean = raw?.trim().orEmpty()
        return clean.length == 32 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

/**
 * Device-level Telegram API credentials, entered once per device in
 * Settings → Telegram. Deliberately NOT per-profile and NOT compiled in:
 * with option B the APK ships without keys and each device carries its own.
 * Stored in private SharedPreferences; never leaves the device.
 */
@Singleton
class TelegramCredentialsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 0 = unset. */
    var apiId: Int
        get() = prefs.getInt(KEY_API_ID, 0)
        private set(value) = prefs.edit().putInt(KEY_API_ID, value).apply()

    var apiHash: String
        get() = prefs.getString(KEY_API_HASH, "").orEmpty()
        private set(value) = prefs.edit().putString(KEY_API_HASH, value).apply()

    fun hasUserCredentials(): Boolean =
        apiId != 0 && TelegramCredentialsValidator.isValidApiHash(apiHash)

    fun save(apiId: Int, apiHash: String) {
        this.apiId = apiId
        this.apiHash = apiHash.trim()
    }

    fun clear() {
        prefs.edit().remove(KEY_API_ID).remove(KEY_API_HASH).apply()
    }

    private companion object {
        const val PREFS_NAME = "tg_credentials"
        const val KEY_API_ID = "api_id"
        const val KEY_API_HASH = "api_hash"
    }
}
