// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.ui.screens.settings.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.telegram.TelegramAuthState
import com.nuvio.tv.core.telegram.TelegramClientManager
import com.nuvio.tv.data.local.TelegramSearchSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TelegramAuthViewModel @Inject constructor(
    private val clientManager: TelegramClientManager,
    private val telegramSearchSettingsDataStore: TelegramSearchSettingsDataStore
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = clientManager.authState
    val allowChannelContextSeriesMatch: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.allowChannelContextSeriesMatch
    // TG-START: per-type i18n toggles under "Búsqueda TG" (re-apply on upstream merge)
    val moviesI18nEnabled: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.moviesI18nEnabled
    val seriesI18nEnabled: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.seriesI18nEnabled
    // TG-END

    fun initialize() = clientManager.initialize()

    fun requestQrCode() = clientManager.requestQrCode()

    fun submitPhoneNumber(phone: String) = clientManager.submitPhoneNumber(phone)

    fun submitCode(code: String) = clientManager.submitCode(code)

    fun submitPassword(password: String) = clientManager.submitPassword(password)

    fun setAllowChannelContextSeriesMatch(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setAllowChannelContextSeriesMatch(enabled)
        }
    }

    // TG-START: per-type i18n toggles under "Búsqueda TG" (re-apply on upstream merge)
    fun setMoviesI18nEnabled(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setMoviesI18nEnabled(enabled)
        }
    }

    fun setSeriesI18nEnabled(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setSeriesI18nEnabled(enabled)
        }
    }

    // TG-START: discard series files in movie searches (re-apply on upstream merge)
    val discardSeriesInMovies: StateFlow<Boolean> =
        telegramSearchSettingsDataStore.discardSeriesInMovies

    fun setDiscardSeriesInMovies(enabled: Boolean) {
        viewModelScope.launch {
            telegramSearchSettingsDataStore.setDiscardSeriesInMovies(enabled)
        }
    }
    // TG-END

    fun unbind() {
        viewModelScope.launch { clientManager.unbind() }
    }
}
