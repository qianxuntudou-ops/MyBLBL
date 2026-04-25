package com.tutu.myblbl.feature.home

import org.koin.androidx.viewmodel.ext.android.viewModel

class HotListFragment : VideoFeedFragment() {

    companion object {
        fun newInstance(): HotListFragment {
            return HotListFragment()
        }
    }

    private val viewModel: HotViewModel by viewModel()

    override val feedViewModel: VideoFeedViewModel
        get() = viewModel
    override val secondaryTabPosition: Int = 1
    override val toastNonEmptyError: Boolean = true
}
