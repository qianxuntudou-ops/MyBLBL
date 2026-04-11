package com.tutu.myblbl.ui.activity

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.R
import com.tutu.myblbl.MyBLBLApplication
import com.tutu.myblbl.databinding.ActivityMainBinding
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.ui.base.BaseActivity
import com.tutu.myblbl.ui.base.OnBackPressedHandler
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.fragment.main.category.CategoryFragment
import com.tutu.myblbl.ui.fragment.main.dynamic.DynamicFragment
import com.tutu.myblbl.ui.fragment.main.home.HomeFragment
import com.tutu.myblbl.ui.fragment.main.live.LiveFragment
import com.tutu.myblbl.ui.fragment.main.me.MeFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.ui.fragment.main.search.SearchNewFragment
import com.tutu.myblbl.ui.fragment.main.settings.SettingsFragment
import com.tutu.myblbl.ui.fragment.main.settings.SignInFragment
import com.tutu.myblbl.ui.dialog.UserInfoDialog
import com.tutu.myblbl.ui.fragment.player.PlayerLaunchContext
import com.tutu.myblbl.ui.fragment.player.VideoPlayerFragment
import com.tutu.myblbl.ui.view.TabBarView
import com.tutu.myblbl.utils.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.koin.android.ext.android.inject
import java.lang.ref.WeakReference

@OptIn(UnstableApi::class)
class MainActivity : BaseActivity<ActivityMainBinding>(), TabBarView.OnTabClickListener {

    private data class FocusRestoreAnchor(
        val viewRef: WeakReference<View>
    )

    companion object {
        private const val TAG = "MainEntryFocus"
        private const val SETTINGS_OVERLAY_TAG = "settings"
        private const val SETTINGS_OVERLAY_EXIT_ANIM_MS = 275L
        private const val SEARCH_TAB_INDEX = 5
        private const val STATE_CURRENT_TAB_INDEX = "main_current_tab_index"
    }

    private val fragments = mutableListOf<Fragment>()
    private val mainNavigationViewModel: MainNavigationViewModel by viewModels()
    private val userRepository: UserRepository by inject()
    private var currentFragmentIndex = -1
    private var exitTime: Long = 0
    private val exitInterval = 2000L
    private val focusRestoreAnchors = ArrayDeque<FocusRestoreAnchor>()
    private var lastBackStackEntryCount = 0
    private var pendingFocusRestoreDelayMs = 0L
    private var startupTasksScheduled = false
    private var splashDismissed = false
    private var restoredFromSavedState = false
    private var restoredTabIndex = -1

    override fun getViewBinding(): ActivityMainBinding = ActivityMainBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null && shouldFinishDuplicateLauncherLaunch()) {
            AppLog.d(TAG, "ignore duplicate launcher launch and keep existing task")
            finish()
            return
        }
        restoredFromSavedState = savedInstanceState != null
        restoredTabIndex = savedInstanceState?.getInt(STATE_CURRENT_TAB_INDEX, -1) ?: -1
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
        supportFragmentManager.addOnBackStackChangedListener {
            val currentCount = supportFragmentManager.backStackEntryCount
            if (currentCount < lastBackStackEntryCount) {
                restoreFocusAfterOverlayPop()
            }
            lastBackStackEntryCount = currentCount
            updateNavigationVisibility()
        }
        lastBackStackEntryCount = supportFragmentManager.backStackEntryCount
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collect { event ->
                    if (event == MainNavigationViewModel.Event.HomeContentReady) {
                        dismissSplash()
                    }
                }
            }
        }
    }

    override fun initView() {
        initFragments()
        binding.myTabView.setOnTabClickListener(this)
        applyBackgroundImage()
        applyLiveEntryVisibility()
    }

    override fun initData() {
        if (restoredFromSavedState) {
            restoreUiStateAfterRecreation()
        } else {
            binding.myTabView.selectTab(0)
        }
        refreshAvatar(allowNetworkFetch = false)
        updateNavigationVisibility()
        scheduleDeferredStartupTasks()
    }

    private fun restoreUiStateAfterRecreation() {
        val resolvedTabIndex = when {
            restoredTabIndex in fragments.indices -> restoredTabIndex
            else -> inferCurrentMainTabIndex()
        }
        if (resolvedTabIndex in fragments.indices) {
            currentFragmentIndex = resolvedTabIndex
            binding.myTabView.restoreTabHighlight(resolvedTabIndex)
            AppLog.d(
                TAG,
                "restoreUiStateAfterRecreation: restoredTabIndex=$resolvedTabIndex, backStack=${supportFragmentManager.backStackEntryCount}"
            )
        } else {
            binding.myTabView.selectTab(0)
        }
    }

    private fun inferCurrentMainTabIndex(): Int {
        val visibleMainIndex = fragments.indices.firstOrNull { index ->
            supportFragmentManager.findFragmentByTag("fragment_$index")?.isVisible == true
        }
        if (visibleMainIndex != null) {
            return visibleMainIndex
        }
        return fragments.indices.lastOrNull { index ->
            supportFragmentManager.findFragmentByTag("fragment_$index")?.isAdded == true
        } ?: -1
    }

    private fun applyBackgroundImage() {
        val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val themeIndex = try {
            prefs.getInt("theme", 1)
        } catch (_: ClassCastException) {
            prefs.getString("theme", "1")?.toIntOrNull() ?: 1
        }
        binding.mainBackgroundImage.visibility = if (themeIndex == 3) View.VISIBLE else View.GONE
    }

    fun applyLiveEntryVisibility() {
        val liveEntry = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .getString("live_entry", "关") ?: "关"
        val show = liveEntry == "开"
        binding.myTabView.setLiveButtonVisible(show)
        if (!show && currentFragmentIndex == 3) {
            binding.myTabView.selectTab(0)
        }
    }

    private fun initFragments() {
        fragments.clear()
        fragments.add(HomeFragment.newInstance())
        fragments.add(CategoryFragment.newInstance())
        fragments.add(DynamicFragment.newInstance())
        fragments.add(LiveFragment.newInstance())
        fragments.add(MeFragment.newInstance())
        fragments.add(SearchNewFragment.newInstance())
    }

    private fun showFragment(index: Int) {
        if (index < 0 || index >= fragments.size) return
        if (index == 3 && !binding.myTabView.isLiveButtonVisible()) return
        if (currentFragmentIndex == index) return

        val fragmentTag = "fragment_$index"
        val previousIndex = currentFragmentIndex
        val currentFragment = supportFragmentManager.findFragmentByTag("fragment_$previousIndex")
        val targetFragment = supportFragmentManager.findFragmentByTag(fragmentTag) ?: fragments[index]

        val transaction = supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)

        if (currentFragment != null) {
            transaction.hide(currentFragment)
        }

        if (!targetFragment.isAdded) {
            transaction.add(R.id.container, targetFragment, fragmentTag)
        } else {
            transaction.show(targetFragment)
        }

        currentFragmentIndex = index
        transaction.commit()
    }

    override fun onTabSelected(index: Int) {
        if (supportFragmentManager.backStackEntryCount > 0) {
            focusRestoreAnchors.clear()
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        showFragment(index)
        postTabSelectedEvent(index)
    }

    override fun onTabReselected(index: Int) {
        postTabClickEvent(index)
    }

    override fun onTabNavigateRight(index: Int): Boolean {
        if (index !in fragments.indices) {
            return false
        }
        val anchorView = currentFocus
        AppLog.d(
            TAG,
            "onTabNavigateRight: index=$index currentFragmentIndex=$currentFragmentIndex currentFocus=${describeView(anchorView)}"
        )
        binding.root.post {
            AppLog.d(
                TAG,
                "post focusCurrentMainContent: index=$index currentFragmentIndex=$currentFragmentIndex currentFocus=${describeView(anchorView)}"
            )
            focusCurrentMainContent(anchorView, preferSpatialEntry = true)
        }
        return true
    }

    override fun onSearchClick() {
        val anchorView = currentFocus ?: binding.myTabView
        if (supportFragmentManager.backStackEntryCount > 0) {
            focusRestoreAnchors.clear()
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        showFragment(SEARCH_TAB_INDEX)
        if (currentFragmentIndex == SEARCH_TAB_INDEX) {
            postTabSelectedEvent(SEARCH_TAB_INDEX)
            binding.root.post {
                focusCurrentMainContent(anchorView = anchorView, preferSpatialEntry = false)
            }
        }
    }

    fun openSearch(keyword: String? = null) {
        binding.myTabView.selectTab(SEARCH_TAB_INDEX)
        keyword
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizedKeyword ->
                val searchFragment = supportFragmentManager
                    .findFragmentByTag("fragment_$SEARCH_TAB_INDEX") as? SearchNewFragment
                    ?: fragments.getOrNull(SEARCH_TAB_INDEX) as? SearchNewFragment
                binding.root.post {
                    searchFragment?.openKeyword(normalizedKeyword)
                }
            }
    }

    override fun onSettingClick() {
        if (isOverlayVisible(SETTINGS_OVERLAY_TAG)) {
            return
        }
        openOverlayFragment(SettingsFragment.newInstance(), SETTINGS_OVERLAY_TAG)
    }

    override fun onAvatarClick() {
        if (!NetworkManager.isLoggedIn()) {
            openOverlayFragment(SignInFragment.newInstance(), "sign_in")
            return
        }
        showUserInfoDialog()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: String) {
        if (event == "signIn" || event == "updateUserInfo") {
            refreshAvatar()
        }
    }

    private fun dismissSplash() {
        if (splashDismissed) return
        splashDismissed = true
        window.setBackgroundDrawableResource(R.color.systemBackgroundColor)
    }

    private fun refreshAvatar(allowNetworkFetch: Boolean = true) {
        if (!NetworkManager.isLoggedIn()) {
            binding.myTabView.setAvatarUrl(null)
            return
        }

        val cachedAvatar = NetworkManager.getUserInfo()?.face
        if (!cachedAvatar.isNullOrBlank()) {
            binding.myTabView.setAvatarUrl(cachedAvatar)
            return
        }

        if (!allowNetworkFetch) return

        lifecycleScope.launch {
            val refreshed = userRepository.refreshCurrentUserInfo().getOrNull()
            binding.myTabView.setAvatarUrl(refreshed?.face)
        }
    }

    private fun scheduleDeferredStartupTasks() {
        if (startupTasksScheduled) {
            return
        }
        startupTasksScheduled = true
        binding.root.post {
            MyBLBLApplication.instance.scheduleDeferredSessionPrewarm()
            lifecycleScope.launch {
                delay(800L)
                refreshAvatar(allowNetworkFetch = true)
            }
        }
    }

    fun focusLeftFunctionArea(sourceView: View? = currentFocus): Boolean {
        if (binding.myTabView.visibility != View.VISIBLE) {
            return false
        }
        AppLog.d(TAG, "focusLeftFunctionArea: source=${describeView(sourceView)}")
        return binding.myTabView.focusNearestButtonTo(sourceView)
    }

    fun showTabBar(show: Boolean) {
        binding.myTabView.visibility = if (show) View.VISIBLE else View.GONE
        binding.divide.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun animateTabBar(show: Boolean) {
        if (show) {
            binding.myTabView.animate()
                .translationX(0f)
                .setDuration(200)
                .start()
        } else {
            binding.myTabView.animate()
                .translationX(-binding.myTabView.width.toFloat())
                .setDuration(200)
                .start()
        }
    }

    private fun handleBackPressed() {
        if (dispatchBackPressedToVisibleFragment()) {
            return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            pendingFocusRestoreDelayMs =
                if (isOverlayVisible(SETTINGS_OVERLAY_TAG)) SETTINGS_OVERLAY_EXIT_ANIM_MS else 0L
            supportFragmentManager.popBackStack()
            return
        }

        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.BackPressed)

        if (System.currentTimeMillis() - exitTime <= exitInterval) {
            finish()
            return
        }

        exitTime = System.currentTimeMillis()
        Toast.makeText(this, R.string.app_exit, Toast.LENGTH_SHORT).show()
    }

    private fun dispatchBackPressedToVisibleFragment(): Boolean {
        val topFragment = supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?: return false
        return topFragment.findBackPressedHandler()?.onBackPressed() == true
    }

    private fun Fragment.findBackPressedHandler(): OnBackPressedHandler? {
        childFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.findBackPressedHandler()
            ?.let { return it }
        return this as? OnBackPressedHandler
    }

    fun openOverlayFragment(fragment: Fragment, tag: String) {
        openFragmentWithReferenceBehavior(fragment, tag, addToBackStack = true)
    }

    fun openInHostContainer(fragment: Fragment, addToBackStack: Boolean = true) {
        openFragmentWithReferenceBehavior(
            fragment = fragment,
            tag = fragment::class.java.name,
            addToBackStack = addToBackStack
        )
    }

    fun openVideoPlayer(
        launchContext: PlayerLaunchContext,
        addToBackStack: Boolean = true
    ) {
        openFragmentWithReferenceBehavior(
            fragment = VideoPlayerFragment.newInstance(launchContext),
            tag = buildVideoPlayerTag(
                aid = launchContext.aid,
                bvid = launchContext.bvid,
                cid = launchContext.cid,
                epId = launchContext.epId,
                seasonId = launchContext.seasonId
            ),
            addToBackStack = addToBackStack
        )
    }

    fun openVideoPlayer(
        aid: Long = 0L,
        bvid: String = "",
        cid: Long = 0L,
        epId: Long = 0L,
        seasonId: Long = 0L,
        seekPositionMs: Long = 0L,
        initialVideo: VideoModel? = null,
        playQueue: List<VideoModel> = emptyList(),
        startEpisodeIndex: Int = -1,
        addToBackStack: Boolean = true
    ) {
        openVideoPlayer(
            launchContext = PlayerLaunchContext.create(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = seasonId,
                seekPositionMs = seekPositionMs,
                initialVideo = initialVideo,
                playQueue = playQueue,
                startEpisodeIndex = startEpisodeIndex
            ),
            addToBackStack = addToBackStack
        )
    }

    private fun openFragmentWithReferenceBehavior(
        fragment: Fragment,
        tag: String,
        addToBackStack: Boolean
    ) {
        if (addToBackStack) {
            currentFocus?.let { focusedView ->
                AppLog.d("FocusDebug", "saveFocus: saving focus for view=$focusedView, class=${focusedView.javaClass.simpleName}, id=${focusedView.id}")
                focusRestoreAnchors.addLast(
                    FocusRestoreAnchor(
                        viewRef = WeakReference(focusedView)
                    )
                )
            }
        }
        val isSettingsOverlay = isSettingsOverlay(fragment = fragment, tag = tag)
        val transaction = supportFragmentManager.beginTransaction().apply {
            if (isSettingsOverlay) {
                setCustomAnimations(
                    R.anim.m3_side_sheet_enter_from_right,
                    0,
                    0,
                    R.anim.m3_side_sheet_exit_to_right
                )
            } else {
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            }
        }

        supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.view
            ?.clearFocus()

        supportFragmentManager.fragments
            .filter { it.isAdded && it.isVisible }
            .forEach { visibleFragment ->
                transaction.hide(visibleFragment)
            }

        transaction.add(R.id.container, fragment, tag)
        if (addToBackStack) {
            transaction.addToBackStack(tag)
        }
        transaction.commit()
        showTabBar(false)
    }

    fun showUserInfoDialog() {
        if (isFinishing || isDestroyed) {
            return
        }
        UserInfoDialog(this).show()
    }

    private fun updateNavigationVisibility() {
        val shouldShowTabBar = supportFragmentManager.backStackEntryCount == 0
        if (shouldShowTabBar) {
            focusRestoreAnchors.clear()
        }
        showTabBar(shouldShowTabBar)
    }

    private fun restoreFocusAfterOverlayPop() {
        val anchor = focusRestoreAnchors.removeLastOrNull()
        val delayMs = pendingFocusRestoreDelayMs
        pendingFocusRestoreDelayMs = 0L
        AppLog.d("FocusDebug", "restoreFocusAfterOverlayPop: anchor=${anchor?.viewRef?.get()}, delayMs=$delayMs, stackSize=${focusRestoreAnchors.size}")
        binding.root.postDelayed({
            val target = anchor?.viewRef?.get()
            AppLog.d("FocusDebug", "restoreFocus delayed: target=$target, attached=${target?.isAttachedToWindow}, shown=${target?.isShown}, focusable=${target?.isFocusable}")
            if (target?.isAttachedToWindow == true && target.isShown && target.isFocusable) {
                val result = target.requestFocus()
                AppLog.d("FocusDebug", "restoreFocus: requestFocus on target=$target, result=$result")
                if (result) {
                    return@postDelayed
                }
            }
            val handledByContent = focusCurrentMainContent(
                anchorView = target,
                preferSpatialEntry = false
            )
            AppLog.d("FocusDebug", "restoreFocus content fallback: handled=$handledByContent")
            if (handledByContent) {
                return@postDelayed
            }
            val handledByTabBar = binding.myTabView.focusCurrentTab()
            AppLog.d("FocusDebug", "restoreFocus tab fallback: handled=$handledByTabBar")
            if (handledByTabBar) {
                return@postDelayed
            }
            supportFragmentManager.fragments
                .asReversed()
                .firstOrNull { it.isVisible }
                ?.view
                ?.let { visibleRoot ->
                    val currentFocus = visibleRoot.findFocus()
                    AppLog.d("FocusDebug", "restoreFocus fallback: visibleRoot=$visibleRoot, currentFocus=$currentFocus")
                    currentFocus?.requestFocus()
                }
        }, delayMs)
    }

    fun skipNextFocusRestore() {
        if (focusRestoreAnchors.isNotEmpty()) {
            focusRestoreAnchors.removeLastOrNull()
            AppLog.d("FocusDebug", "skipNextFocusRestore: removed last anchor, stackSize=${focusRestoreAnchors.size}")
        }
    }

    private fun isSettingsOverlay(fragment: Fragment, tag: String): Boolean {
        return tag == SETTINGS_OVERLAY_TAG || fragment is SettingsFragment
    }

    private fun isOverlayVisible(tag: String): Boolean {
        return supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.tag == tag
    }

    private fun buildVideoPlayerTag(
        aid: Long,
        bvid: String,
        cid: Long,
        epId: Long,
        seasonId: Long
    ): String {
        return buildString {
            append("video_player:")
            append(aid)
            append(':')
            append(bvid.ifBlank { "_" })
            append(':')
            append(cid)
            append(':')
            append(epId)
            append(':')
            append(seasonId)
            append(':')
            append(SystemClock.uptimeMillis())
        }
    }

    private fun focusCurrentMainContent(
        anchorView: View? = currentFocus,
        preferSpatialEntry: Boolean = false
    ): Boolean {
        val currentFragment = supportFragmentManager.findFragmentByTag("fragment_$currentFragmentIndex")
        val handled = (currentFragment as? MainTabFocusTarget)
            ?.focusEntryFromMainTab(anchorView, preferSpatialEntry) == true
        AppLog.d(
            TAG,
            "focusCurrentMainContent: fragment=${currentFragment?.javaClass?.simpleName} handled=$handled preferSpatialEntry=$preferSpatialEntry anchor=${describeView(anchorView)} currentFocusAfter=${describeView(currentFocus)}"
        )
        return handled
    }

    private fun describeView(view: View?): String {
        if (view == null) {
            return "null"
        }
        val idName = if (view.id != View.NO_ID) {
            runCatching { resources.getResourceEntryName(view.id) }.getOrNull()
        } else {
            null
        }
        return "${view.javaClass.simpleName}(id=${idName ?: view.id},hash=${System.identityHashCode(view)})"
    }

    private fun shouldFinishDuplicateLauncherLaunch(): Boolean {
        val launchIntent = intent
        return !isTaskRoot &&
            launchIntent?.action == android.content.Intent.ACTION_MAIN &&
            launchIntent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }

    private fun postTabClickEvent(index: Int) {
        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MainTabReselected(index))
    }

    private fun postTabSelectedEvent(index: Int) {
        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MainTabSelected(index))
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event?.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MenuPressed)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_TAB_INDEX, currentFragmentIndex)
        super.onSaveInstanceState(outState)
    }
}
