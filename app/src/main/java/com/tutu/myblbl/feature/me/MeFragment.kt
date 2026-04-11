package com.tutu.myblbl.feature.me

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
import com.tutu.myblbl.databinding.FragmentMeBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.ui.dialog.UserInfoDialog
import com.tutu.myblbl.ui.fragment.main.settings.SignInFragment
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.ImageLoader
import com.tutu.myblbl.utils.enableTouchNavigation
import com.tutu.myblbl.utils.focusNearestTabTo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MeFragment : BaseFragment<FragmentMeBinding>(), MainTabFocusTarget {
    companion object {
        private const val TAG = "MainEntryFocus"
        private const val USER_INFO_CACHE_TTL_MS = 10 * 60 * 1000L

        fun newInstance(): MeFragment = MeFragment()
    }

    private val appEventHub: AppEventHub by inject()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val viewModel: MeViewModel by viewModel()
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: MeFragmentAdapter
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMeBinding {
        return FragmentMeBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        tabLayout = binding.tabLayout
        viewPager = binding.viewPager
        binding.rightStateContainer.setOnClickListener {
            if (!viewModel.isLoggedIn.value) {
                openInHostContainer(SignInFragment.newInstance())
            }
        }

        adapter = MeFragmentAdapter(
            childFragmentManager,
            lifecycle
        )
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)?.let(::getString).orEmpty()
        }.attach()
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            matchLegacyViewPagerAnimation = true,
            onNavigateDown = ::focusCurrentPagePrimaryContent
        )
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                notifyCurrentTab { it.onTabReselected() }
            }
        })

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                notifyCurrentTab { it.onTabSelected() }
            }
        }.also { viewPager.registerOnPageChangeCallback(it) }

        viewPager.currentItem = getDefaultTabIndex()
        viewPager.post { notifyCurrentTab { it.onTabSelected() } }
        
        binding.imageAvatar.setOnClickListener {
            onAvatarClick()
        }
    }

    override fun initData() {
        viewModel.loadUserInfo()
        renderLoginState(viewModel.isLoggedIn.value)
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.userInfo.collectLatest { userInfo ->
                    userInfo?.let {
                        updateUserInfo(it.face)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoggedIn.collectLatest { isLoggedIn ->
                    renderLoginState(isLoggedIn)
                    if (!isLoggedIn) {
                        updateUserInfo("")
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
                            if (event.index == 4) {
                                if (viewModel.shouldRefresh(USER_INFO_CACHE_TTL_MS)) {
                                    viewModel.loadUserInfo()
                                }
                                dispatchHostEvent(MeTabPage.HostEvent.SELECT_TAB4)
                            }

                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 4) {
                                if (viewModel.shouldRefresh(USER_INFO_CACHE_TTL_MS)) {
                                    viewModel.loadUserInfo()
                                }
                                dispatchHostEvent(MeTabPage.HostEvent.CLICK_TAB4) {
                                    getCurrentTabPage()?.refresh()
                                }
                            }

                        MainNavigationViewModel.Event.BackPressed -> {
                            dispatchHostEvent(MeTabPage.HostEvent.BACK_PRESSED) {
                                getCurrentTabPage()?.scrollToTop()
                            }
                        }

                        MainNavigationViewModel.Event.MenuPressed -> {
                            dispatchHostEvent(MeTabPage.HostEvent.KEY_MENU_PRESS) {
                                getCurrentTabPage()?.refresh()
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden) {
                        viewModel.loadUserInfo()
                        adapter.getFragments().forEach { fragment ->
                            if (fragment.view != null) {
                                (fragment as? MeTabPage)?.refresh()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateUserInfo(avatarUrl: String) {
        if (avatarUrl.isNotEmpty()) {
            ImageLoader.loadCircle(
                imageView = binding.imageAvatar,
                url = avatarUrl,
                placeholder = R.drawable.default_avatar,
                error = R.drawable.default_avatar
            )
        } else {
            binding.imageAvatar.setImageResource(R.drawable.default_avatar)
        }
    }

    private fun onAvatarClick() {
        if (!sessionGateway.isLoggedIn()) {
            openInHostContainer(SignInFragment.newInstance())
            return
        }
        UserInfoDialog(requireContext()).show()
    }

    private fun getDefaultTabIndex(): Int {
        val startPage = requireContext()
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .getInt("defaultStartPage", 1)
        return (startPage - 4).coerceIn(0, adapter.itemCount - 1)
    }

    fun scrollToTop() {
        if (!viewModel.isLoggedIn.value) {
            return
        }
        getCurrentTabPage()?.scrollToTop()
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        return binding.tabLayout.focusNearestTabTo(anchorView)
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (!viewModel.isLoggedIn.value) {
            val handled = binding.rightStateContainer.requestFocus()
            AppLog.d(
                TAG,
                "MeFragment.focusEntryFromMainTab loggedOut: handled=$handled preferSpatialEntry=$preferSpatialEntry"
            )
            return handled
        }
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry)
        AppLog.d(
            TAG,
            "MeFragment.focusEntryFromMainTab: currentItem=${viewPager.currentItem} handled=$handled preferSpatialEntry=$preferSpatialEntry anchor=${anchorView?.javaClass?.simpleName ?: "null"} focus=${view?.findFocus()?.javaClass?.simpleName ?: "null"}"
        )
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        return getCurrentTabPage()?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    private fun getCurrentTabPage(): MeTabPage? {
        return adapter.getFragment(viewPager.currentItem) as? MeTabPage
    }

    private fun notifyCurrentTab(action: (MeTabPage) -> Unit) {
        getCurrentTabPage()?.let(action)
    }

    private fun dispatchHostEvent(event: MeTabPage.HostEvent, fallback: (() -> Unit)? = null) {
        if (!viewModel.isLoggedIn.value) {
            return
        }
        if (getCurrentTabPage()?.onHostEvent(event) == true) {
            return
        }
        fallback?.invoke()
    }

    private fun renderLoginState(isLoggedIn: Boolean) {
        if (!isAdded || view == null) {
            return
        }
        binding.rightStateContainer.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        binding.imageAvatar.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.tabLayout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.viewPager.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.rightStateText.text = getString(R.string.feature_wait_tip)
    }

    override fun onDestroyView() {
        pageChangeCallback?.let(viewPager::unregisterOnPageChangeCallback)
        pageChangeCallback = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }
}
