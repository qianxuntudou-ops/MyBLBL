package com.tutu.myblbl.ui.fragment.main

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationViewModelTest {

    @Test
    fun dispatch_emits_events_in_order() = runBlocking {
        val viewModel = MainNavigationViewModel(SavedStateHandle())
        val events = mutableListOf<MainNavigationViewModel.Event>()

        val collectionJob = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.events.take(3).toList(events)
        }

        viewModel.dispatch(MainNavigationViewModel.Event.MainTabSelected(3))
        viewModel.dispatch(
            MainNavigationViewModel.Event.SecondaryTabReselected(
                host = MainNavigationViewModel.SecondaryTabHost.LIVE,
                position = 0
            )
        )
        viewModel.dispatch(MainNavigationViewModel.Event.MenuPressed)

        withTimeout(1_000L) {
            collectionJob.join()
        }

        assertEquals(
            listOf(
                MainNavigationViewModel.Event.MainTabSelected(3),
                MainNavigationViewModel.Event.SecondaryTabReselected(
                    host = MainNavigationViewModel.SecondaryTabHost.LIVE,
                    position = 0
                ),
                MainNavigationViewModel.Event.MenuPressed
            ),
            events
        )
    }
}
