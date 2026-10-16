package com.takaisaisei.pixelims.ui

import android.app.Application
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.takaisaisei.pixelims.ApplyMode
import com.takaisaisei.pixelims.R
import com.takaisaisei.pixelims.system.ShizukuShell
import com.takaisaisei.pixelims.system.NotificationController
import com.takaisaisei.pixelims.data.SettingsRepository
import com.takaisaisei.pixelims.domain.CfgValue
import com.takaisaisei.pixelims.domain.Feature
import com.takaisaisei.pixelims.domain.SlotQuery
import com.takaisaisei.pixelims.domain.SlotStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val shell = ShizukuShell(application)
    private val notifications = NotificationController(application)
    private val subscriptions = application.getSystemService(SubscriptionManager::class.java)
    private val telephony = application.getSystemService(TelephonyManager::class.java)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var pollJob: Job? = null
    private var isForeground = true

    init {
        _uiState.update { withConfig(it, it.selectedSlot).copy(bootSlots = settings.bootSlots()) }
        refreshAuthorization()
    }

    /** Driven by the Activity lifecycle so privileged polling only runs while the UI is visible. */
    fun setForeground(foreground: Boolean) {
        if (foreground == isForeground) return
        isForeground = foreground
        if (foreground) {
            refreshAuthorization()
        } else {
            pollJob?.cancel()
        }
    }

    fun selectSlot(slot: Int) = _uiState.update { withConfig(it, slot).copy(selectedSlot = slot) }

    fun setApplyOnBoot(slot: Int, value: Boolean) {
        settings.setApplyOnBoot(slot, value)
        _uiState.update { it.copy(bootSlots = settings.bootSlots()) }
    }

    fun setValue(slot: Int, key: String, value: CfgValue) {
        settings.setValue(slot, key, value)
        _uiState.update { state ->
            state.copy(configs = state.configs + (slot to state.config(slot).withValue(key, value)))
        }
    }

    fun setFlag(slot: Int, feature: Feature, value: Boolean) {
        settings.setFlag(slot, feature, value)
        val siblingsToClear = if (value) feature.exclusiveSiblings else emptyList()
        siblingsToClear.forEach { settings.setFlag(slot, it, false) }
        _uiState.update { state ->
            var config = state.config(slot).with(feature, value)
            siblingsToClear.forEach { config = config.with(it, false) }
            state.copy(configs = state.configs + (slot to config))
        }
    }

    fun refreshAuthorization() {
        val authorized = shell.isAuthorized()
        _uiState.update { it.copy(isAuthorized = authorized) }
        pollJob?.cancel()
        if (!authorized || !isForeground) return
        viewModelScope.launch { shell.grantPermissionsIfNeeded() }
        pollJob = viewModelScope.launch {
            shell.pollImsStatus(isPaused = { _uiState.value.isApplying }, onResult = ::applyQuery)
        }
    }

    fun apply() = runApply(clear = false, failureMessage = R.string.msg_apply_failed)

    fun restore() {
        val slot = _uiState.value.selectedSlot
        settings.resetToDefaults(slot)
        _uiState.update {
            it.copy(
                configs = it.configs + (slot to settings.readConfig(slot)),
                bootSlots = settings.bootSlots(),
            )
        }
        runApply(clear = true, failureMessage = R.string.msg_restore_failed)
    }

    private fun runApply(clear: Boolean, failureMessage: Int) {
        if (!shell.isAuthorized()) {
            emit(UiMessage(R.string.msg_authorize_first))
            return
        }
        val slot = _uiState.value.selectedSlot
        _uiState.update {
            it.copy(isApplying = true, statuses = it.statuses + (slot to SlotStatus()))
        }
        viewModelScope.launch {
            if (ApplyMode.detached) {
                notifications.showApplying()
            }
            val result = shell.runApply(slot, clear, notify = false)
            _uiState.update { it.copy(isApplying = false) }
            result.onFailure { error -> emit(UiMessage(failureMessage, error.message)) }
        }
    }

    private fun withConfig(state: MainUiState, slot: Int): MainUiState =
        if (state.configs.containsKey(slot)) state
        else state.copy(configs = state.configs + (slot to settings.readConfig(slot)))

    private fun applyQuery(query: SlotQuery) = _uiState.update { state ->
        val status = SlotStatus(
            loaded = true,
            imsRegistered = query.imsRegistered,
            effective = query.effective
        )
        val knownSlots = state.knownSlots + query.slot
        val selectedSlot =
            if (state.selectedSlot in knownSlots) state.selectedSlot else knownSlots.min()
        val carrierNames = carrierName(query.slot)
            ?.let { state.carrierNames + (query.slot to it) }
            ?: state.carrierNames
        withConfig(state, query.slot).copy(
            statuses = state.statuses + (query.slot to status),
            knownSlots = knownSlots,
            selectedSlot = selectedSlot,
            carrierNames = carrierNames,
        )
    }

    private fun carrierName(slot: Int): String? = runCatching {
        val info = subscriptions?.getActiveSubscriptionInfoForSimSlotIndex(slot) ?: return null
        val tm = telephony?.createForSubscriptionId(info.subscriptionId) ?: return null
        tm.simOperatorName?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun emit(message: UiMessage) {
        _messages.trySend(message)
    }

    private fun getString(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)
}
