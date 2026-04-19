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
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.ui.dialog.UserInfoDialog
import com.tutu.myblbl.feature.settings.SignInFragment
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.core.ui.tab.enableTouchNavigation
import com.tutu.myblbl.core.ui.tab.focusNearestTabTo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MeFragment : BaseFragment<FragmentMeBinding>(), MainTabFocusTarget {
    companion object {
        private const val USER_INFO_CACHE_TTL_MS = 10 * 60 * 1000L

        fun newInstance(): MeFragment = MeFragment()
    }

    private val appSettings: AppSettingsDataStore by inject()
    private val appEventHub: AppEventHub by inject()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val viewModel: MeViewModel by viewModel()
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: MeFragmentAdapter
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var pendingRefreshAfterLogin = false

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
                notifyCurrentTabWithRetry({ page -> page.onTabSelected() })
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
                        val oType = it.officialVerify?.type ?: it.official?.let { o -> if (o.role > 0) o.type else -1 } ?: -1
                        val vStatus = it.vipStatus.coerceAtLeast(it.vip?.vipStatus ?: 0)
                        val vType = it.vipType.coerceAtLeast(it.vip?.vipType ?: 0)
                        binding.imageAvatar.setBadge(
                            officialVerifyType = oType,
                            vipStatus = vStatus,
                            vipType = vType
                        )
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
                        binding.imageAvatar.setBadge(officialVerifyType = -1)
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
                        pendingRefreshAfterLogin = true
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
        val startPage = appSettings.getCachedInt("defaultStartPage", 1)
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
            return handled
        }
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry) ||
            focusCurrentTab(anchorView)
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

    private fun notifyCurrentTabWithRetry(action: (MeTabPage) -> Unit, retries: Int = 5) {
        val page = getCurrentTabPage()
        if (page != null) {
            action(page)
        } else if (retries > 0) {
            viewPager.post { notifyCurrentTabWithRetry(action, retries - 1) }
        }
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

        if (isLoggedIn && pendingRefreshAfterLogin) {
            pendingRefreshAfterLogin = false
            binding.viewPager.post {
                if (!isAdded || view == null) return@post
                adapter.getFragments().forEach { fragment ->
                    if (fragment.view != null) {
                        (fragment as? MeTabPage)?.refresh()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        pageChangeCallback?.let(viewPager::unregisterOnPageChangeCallback)
        pageChangeCallback = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }
}
