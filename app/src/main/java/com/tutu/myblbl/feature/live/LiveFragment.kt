package com.tutu.myblbl.feature.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentLiveBinding
import com.tutu.myblbl.model.live.LiveAreaCategoryParent
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.tab.enableTouchNavigation
import com.tutu.myblbl.core.ui.tab.focusNearestTabTo
import com.tutu.myblbl.core.ui.tab.focusSelectedTab
import com.tutu.myblbl.core.common.ext.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveFragment : BaseFragment<FragmentLiveBinding>(), MainTabFocusTarget {
    companion object {
        private const val TAG = "MainEntryFocus"
        private const val CATEGORY_CACHE_TTL_MS = 20 * 60 * 1000L

        fun newInstance(): LiveFragment = LiveFragment()
    }

    private val viewModel: LiveViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: LiveFragmentAdapter
    private val categories = mutableListOf<LiveAreaCategoryParent>()

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBinding {
        return FragmentLiveBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        AppLog.d(TAG, "[Live] initView start")
        tabLayout = binding.tabLayout
        viewPager = binding.viewPager

        adapter = LiveFragmentAdapter(
            childFragmentManager,
            lifecycle
        )
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
        AppLog.e("[BLBL_DIAG]", "[Live] initView done, adapter.itemCount=${adapter.itemCount}")
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            matchLegacyViewPagerAnimation = true,
            onNavigateDown = ::focusCurrentPagePrimaryContent
        )
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                mainNavigationViewModel.dispatch(
                    MainNavigationViewModel.Event.SecondaryTabReselected(
                        host = MainNavigationViewModel.SecondaryTabHost.LIVE,
                        position = tab.position
                    )
                )
            }
        })
    }

    override fun initData() {
        AppLog.e("[BLBL_DIAG]", "[Live] initData: calling loadLiveAreas")
        viewModel.loadLiveAreas()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collectLatest { list ->
                    AppLog.e("[BLBL_DIAG]", "[Live] categories collected: size=${list.size}")
                    if (list.isNotEmpty()) {
                        val previousItem = viewPager.currentItem
                        categories.clear()
                        categories.addAll(list)
                        adapter.setCategories(categories)
                        AppLog.e("[BLBL_DIAG]", "[Live] setCategories done: tabCount=${categories.size}, adapter.itemCount=${adapter.itemCount}")
                        tabLayout.enableTouchNavigation(
                            viewPager = viewPager,
                            matchLegacyViewPagerAnimation = true,
                            onNavigateDown = ::focusCurrentPagePrimaryContent
                        )
                        viewPager.currentItem = previousItem.coerceIn(0, categories.lastIndex)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        requireContext().toast(error)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabSelected ->
                            if (event.index == 3) {
                                if (viewModel.shouldRefresh(CATEGORY_CACHE_TTL_MS)) {
                                    viewModel.loadLiveAreas()
                                }
                                adapter.getCurrentFragment(viewPager.currentItem)?.onTabSelected()
                            }

                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 3) {
                                adapter.getCurrentFragment(viewPager.currentItem)?.onExplicitRefresh()
                            }

                        MainNavigationViewModel.Event.MenuPressed ->
                            adapter.getCurrentFragment(viewPager.currentItem)?.onExplicitRefresh()

                        MainNavigationViewModel.Event.BackPressed -> scrollToTop()
                        else -> Unit
                    }
                }
            }
        }
    }

    fun scrollToTop() {
        adapter.getCurrentFragment(viewPager.currentItem)?.scrollToTop()
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        return binding.tabLayout.focusNearestTabTo(anchorView)
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry)
        AppLog.d(
            TAG,
            "LiveFragment.focusEntryFromMainTab: currentItem=${viewPager.currentItem} handled=$handled preferSpatialEntry=$preferSpatialEntry anchor=${anchorView?.javaClass?.simpleName ?: "null"} focus=${view?.findFocus()?.javaClass?.simpleName ?: "null"}"
        )
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        return adapter.getCurrentFragment(viewPager.currentItem)?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    override fun onDestroyView() {
        binding.viewPager.adapter = null
        super.onDestroyView()
    }
}
