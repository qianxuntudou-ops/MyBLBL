package com.tutu.myblbl.ui.fragment.main

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainNavigationViewModel : ViewModel() {

    sealed interface Event {
        data class MainTabSelected(val index: Int) : Event
        data class MainTabReselected(val index: Int) : Event
        data class SecondaryTabReselected(
            val host: SecondaryTabHost,
            val position: Int
        ) : Event

        data object BackPressed : Event
        data object MenuPressed : Event
        data object HomeContentReady : Event
    }

    enum class SecondaryTabHost {
        HOME,
        LIVE,
        ME
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun dispatch(event: Event) {
        _events.tryEmit(event)
    }
}
