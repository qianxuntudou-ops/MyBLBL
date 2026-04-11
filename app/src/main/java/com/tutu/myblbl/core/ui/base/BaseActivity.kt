package com.tutu.myblbl.core.ui.base

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import androidx.viewbinding.ViewBinding
import org.koin.core.context.GlobalContext

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB

    abstract fun getViewBinding(): VB

    private val appSettings: AppSettingsDataStore
        get() = GlobalContext.get().get()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        configureFullscreenWindow()
        binding = getViewBinding()
        setContentView(binding.root)
        initView()
        initData()
        initObserver()
    }

    override fun onResume() {
        super.onResume()
        applyFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullscreenMode()
        }
    }

    open fun initView() {}
    open fun initData() {}
    open fun initObserver() {}

    open fun applyTheme() {
        val themeIndex = appSettings.getCachedInt("theme", 1)
        setTheme(themeIndex.toThemeResId())
    }

    private fun Int.toThemeResId(): Int {
        return when (this) {
            0 -> R.style.DarkTheme
            1 -> R.style.DarkTheme
            2 -> R.style.WhiteTheme
            3 -> R.style.ClassicsTheme
            4 -> R.style.PinkTheme
            5 -> R.style.BlueTheme
            6 -> R.style.PurpleTheme
            7 -> R.style.RedTheme
            else -> R.style.DarkTheme
        }
    }

    protected fun <T : Any?> runOnUiThread(action: () -> T) {
        if (!isFinishing) {
            runOnUiThread { action() }
        }
    }

    private fun configureFullscreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        applyFullscreenMode()
    }

    private fun applyFullscreenMode() {
        if (isFullscreenEnabled()) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            enterImmersiveFullscreen()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            showSystemBars()
        }
    }

    private fun isFullscreenEnabled(): Boolean {
        return appSettings.getCachedString("fullscreen_app") != "关"
    }

    private fun enterImmersiveFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
