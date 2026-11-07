package io.github.originaldadi.soundcast.android

import io.github.originaldadi.soundcast.core.SonosGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CastStatus {
    data object Idle : CastStatus
    data class Starting(val group: SonosGroup) : CastStatus
    data class Casting(val group: SonosGroup, val streamUrl: String) : CastStatus
    data class Failed(val message: String) : CastStatus
}

/** Shared between the screen and the background service. */
object CastState {
    private val _status = MutableStateFlow<CastStatus>(CastStatus.Idle)
    val status: StateFlow<CastStatus> = _status.asStateFlow()

    fun set(status: CastStatus) {
        _status.value = status
    }
}
