package com.tutu.myblbl.feature.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.event.AppEventHub
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RecommendListFragment : VideoFeedFragment() {

    companion object {
        fun newInstance(): RecommendListFragment {
            return RecommendListFragment()
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val viewModel: RecommendViewModel by viewModel()

    override val feedViewModel: VideoFeedViewModel
        get() = viewModel
    override val secondaryTabPosition: Int = 0
    override val dispatchHomeContentReady: Boolean = true

    override fun initObserver() {
        super.initObserver()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && isResumed && !isLoading) {
                        refresh()
                    }
                }
            }
        }
    }
}
