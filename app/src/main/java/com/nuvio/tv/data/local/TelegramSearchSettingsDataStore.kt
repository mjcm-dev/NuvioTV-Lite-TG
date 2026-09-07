// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TelegramSearchSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "telegram_search_settings"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val allowChannelContextSeriesMatchKey =
        booleanPreferencesKey("allow_channel_context_series_match")

    val allowChannelContextSeriesMatch: StateFlow<Boolean> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                prefs[allowChannelContextSeriesMatchKey] ?: true
            }
        }.stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setAllowChannelContextSeriesMatch(enabled: Boolean) {
        store().edit { prefs ->
            prefs[allowChannelContextSeriesMatchKey] = enabled
        }
    }
}
