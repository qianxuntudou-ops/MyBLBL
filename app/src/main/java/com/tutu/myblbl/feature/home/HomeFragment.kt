package com.tutu.myblbl.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import com.tutu.myblbl.databinding.FragmentHomeBinding
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.enableTouchNavigation
import com.tutu.myblbl.utils.focusNearestTabTo
import com.tutu.myblbl.utils.getHomeDefaultStartPageIndex
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), MainTabFocusTarget {

    companion object {
        private const val TAG = "MainEntryFocus"

        fun newInstance(): HomeFragment {
            return HomeFragment()
        }
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var adapter: HomeFragmentStateAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = HomeFragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        tabMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.also { it.attach() }
        binding.tabLayout.enableTouchNavigation(
            viewPager = binding.viewPager,
            matchLegacyViewPagerAnimation = true,
            onNavigateDown = ::focusCurrentPagePrimaryContent,
            onNavigateLeft = ::focusLeftFunctionArea
        )

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                postTopTabEvent(tab.position)
            }
        })
        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                (adapter.getCurrentFragment(position) as? HomeTabPage)?.onTabSelected()
            }
        }.also { callback ->
            binding.viewPager.registerOnPageChangeCallback(callback)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    val currentBinding = _binding ?: return@collectLatest
                    if (isHidden) {
                        return@collectLatest
                    }
                    if (event is MainNavigationViewModel.Event.MainTabSelected && event.index == 0) {
                        (adapter.getCurrentFragment(currentBinding.viewPager.currentItem) as? HomeTabPage)
                            ?.onTabSelected()
                    }
                }
            }
        }

        binding.viewPager.currentItem = getDefaultTabIndex()
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        tabMediator?.detach()
        tabMediator = null
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun getDefaultTabIndex(): Int {
        return requireContext().getHomeDefaultStartPageIndex(
            maxIndex = adapter.itemCount - 1,
            defaultIndex = 0
        )
    }

    private fun postTopTabEvent(position: Int) {
        mainNavigationViewModel.dispatch(
            MainNavigationViewModel.Event.SecondaryTabReselected(
                host = MainNavigationViewModel.SecondaryTabHost.HOME,
                position = position
            )
        )
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        val currentBinding = _binding ?: return false
        return currentBinding.tabLayout.focusNearestTabTo(anchorView)
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry)
        AppLog.d(
            TAG,
            "HomeFragment.focusEntryFromMainTab: currentItem=${binding.viewPager.currentItem} handled=$handled preferSpatialEntry=$preferSpatialEntry anchor=${anchorView?.javaClass?.simpleName ?: "null"} focus=${view?.findFocus()?.javaClass?.simpleName ?: "null"}"
        )
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        return getCurrentTabPage()?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    private fun focusLeftFunctionArea(): Boolean {
        return (activity as? MainActivity)?.focusLeftFunctionArea() == true
    }

    private fun getCurrentTabPage(): HomeTabPage? {
        val currentItem = binding.viewPager.currentItem
        val fragmentTag = "f${adapter.getItemId(currentItem)}"
        return childFragmentManager.findFragmentByTag(fragmentTag) as? HomeTabPage
    }
}
