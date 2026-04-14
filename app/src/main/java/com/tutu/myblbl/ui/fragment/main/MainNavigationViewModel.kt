package com.tutu.myblbl.ui.fragment.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow

class MainNavigationViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

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

    companion object {
        const val KEY_TAB_INDEX = "saved_main_tab_index"
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _currentTabIndex = MutableStateFlow(
        savedStateHandle[KEY_TAB_INDEX] ?: 0
    )
    val currentTabIndex: StateFlow<Int> = _currentTabIndex.asStateFlow()

    fun dispatch(event: Event) {
        _events.tryEmit(event)
    }

    fun onTabSelected(index: Int) {
        _currentTabIndex.value = index
        savedStateHandle[KEY_TAB_INDEX] = index
    }

    fun getSavedTabIndex(): Int {
        return savedStateHandle[KEY_TAB_INDEX] ?: -1
    }
}
