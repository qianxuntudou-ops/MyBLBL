package com.tutu.myblbl.ui.fragment.main.settings

import android.os.Build
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.BuildConfig
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentSettingsBinding
import com.tutu.myblbl.model.SettingModel
import com.tutu.myblbl.ui.adapter.SettingAdapter
import com.tutu.myblbl.ui.adapter.SettingSelectionDialogAdapter
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.ui.widget.LinearSpacingItemDecoration
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.FileCacheManager
import com.tutu.myblbl.utils.ImageLoader
import com.tutu.myblbl.utils.PlayerMediaCache
import com.tutu.myblbl.utils.normalizeDanmakuSmartFilterValue
import java.util.Locale

class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {

    companion object {
        fun newInstance() = SettingsFragment()
        const val CATEGORY_COMMON = 0
        const val CATEGORY_PLAY = 1
        const val CATEGORY_DM = 2
        const val CATEGORY_DEVICE = 3

        private const val PREFS_NAME = "app_settings"
        private const val KEY_CACHE_LIMIT = "cache_limit"
        private const val KEY_DEFAULT_START_PAGE = "default_start_page"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_THEME = "theme"
        private const val KEY_FULLSCREEN_APP = "fullscreen_app"
        private const val KEY_LIVE_ENTRY = "live_entry"
        private const val KEY_MINOR_PROTECTION = "minor_protection"
        private const val KEY_DEFAULT_VIDEO_QUALITY = "default_video_quality"
        private const val KEY_DEFAULT_AUDIO_TRACK = "default_audio_track"
        private const val KEY_DEFAULT_PLAY_SPEED = "default_play_speed"
        private const val KEY_AFTER_PLAY = "after_play"
        private const val KEY_PLAY_FINISH_EXIT_PLAYER = "play_finish_exit_player"
        private const val KEY_SHOW_RE_FF = "show_re_ff"
        private const val KEY_VIDEO_CODEC = "video_codec"
        private const val KEY_SHOW_SUBTITLE_DEFAULT = "show_subtitle_default"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val KEY_SHOW_DEBUG = "show_debug"
        private const val KEY_SHOW_VIDEO_DETAIL = "show_video_detail"
        private const val KEY_SHOW_BOTTOM_PROGRESS_BAR = "show_bottom_progress_bar"
        private const val KEY_SIMPLE_KEY_PRESS = "simple_key_press"
        private const val KEY_GIVE_COIN_NUMBER = "give_coin_number"
        private const val KEY_SHOW_NEXT_PREVIOUS = "show_next_previous"
        private const val KEY_SHOW_DM_SWITCH = "show_dm_switch"
        private const val KEY_FF_SEEK_SECOND = "ff_seek_second"
        private const val KEY_DM_SWITCH = "dm_enable"
        private const val KEY_DM_ALPHA = "dm_alpha"
        private const val KEY_DM_TEXT_SIZE = "dm_text_size"
        private const val KEY_DM_SCREEN_AREA = "dm_area"
        private const val KEY_DM_SPEED = "dm_speed"
        private const val KEY_DM_ALLOW_TOP = "dm_allow_top"
        private const val KEY_DM_ALLOW_BOTTOM = "dm_allow_bottom"
        private const val KEY_DM_FILTER_WEIGHT = "dm_filter_weight"
        private const val KEY_DM_ALLOW_VIP_COLORFUL_DM = "dm_allow_vip_colorful_dm"
        private const val KEY_DM_SHOW_ADVANCED = "dm_show_advanced"
        private const val KEY_DM_MERGE_DUPLICATE = "dm_merge_duplicate"
        private val DM_SMART_FILTER_OPTIONS = arrayOf("关", "低", "中", "高")

        private val HOME_START_PAGE_OPTIONS = arrayOf("推荐", "热门", "番剧", "影视")
    }

    private lateinit var commonSettings: MutableList<SettingModel>
    private lateinit var playerSettings: MutableList<SettingModel>
    private lateinit var dmSettings: MutableList<SettingModel>
    private val deviceSettings = mutableListOf<SettingModel>()
    private lateinit var prefs: SharedPreferences

    private lateinit var adapter: SettingAdapter
    private var currentCategory = -1
    private var categorySwitchVersion = 0
    private var shouldRequestInitialCategoryFocus = false

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSettingsBinding {
        return FragmentSettingsBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        binding.tvTitle.text = getString(R.string.setting)
        binding.buttonBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        initSettings()
        setupRecyclerView()
        setupCategoryButtons()
    }

    override fun initData() {
        shouldRequestInitialCategoryFocus = true
        showCategory(CATEGORY_COMMON)
        requestInitialCategoryFocus()
    }

    override fun onResume() {
        super.onResume()
        requestInitialCategoryFocus()
    }

    private fun initSettings() {
        commonSettings = mutableListOf(
            SettingModel(getString(R.string.clear_cache), "0.0kb"),
            SettingModel(getString(R.string.cache_limit), "不限制"),
            SettingModel(getString(R.string.check_version), BuildConfig.VERSION_NAME),
            SettingModel(getString(R.string.default_start_page), "热门"),
            SettingModel(getString(R.string.image_quality), "中尺寸"),
            SettingModel(getString(R.string.theme), "黑色"),
            SettingModel(getString(R.string.fullscreen_app), "开"),
            SettingModel(getString(R.string.live_entry), "关"),
            SettingModel(getString(R.string.minor_protection), "开")
        )

        playerSettings = mutableListOf(
            SettingModel(getString(R.string.default_video_quality), "1080P"),
            SettingModel(getString(R.string.default_audio_track), "192kbps"),
            SettingModel(getString(R.string.default_play_speed), "1.0"),
            SettingModel(getString(R.string.after_play), "播推荐视频"),
            SettingModel(getString(R.string.play_finish_exit_player), "开"),
            SettingModel(getString(R.string.show_re_ff), "关"),
            SettingModel(getString(R.string.video_codec), "HEVC"),
            SettingModel(getString(R.string.show_subtitle_default), "关"),
            SettingModel(getString(R.string.subtitle_text_size), "45"),
            SettingModel(getString(R.string.show_debug), "关"),
            SettingModel(getString(R.string.show_video_detail), "关"),
            SettingModel(getString(R.string.show_bottom_progress_bar), "关"),
            SettingModel(getString(R.string.simple_key_press), "关"),
            SettingModel(getString(R.string.give_coin_number), "2"),
            SettingModel(getString(R.string.show_next_previous), "关"),
            SettingModel(getString(R.string.show_dm_switch), "关"),
            SettingModel(getString(R.string.ff_seek_second), "10s")
        )

        dmSettings = mutableListOf(
            SettingModel(getString(R.string.dm_switch), "开"),
            SettingModel(getString(R.string.dm_alpha), "1.0"),
            SettingModel(getString(R.string.dm_text_size), "40"),
            SettingModel(getString(R.string.dm_screen_area), "1/2"),
            SettingModel(getString(R.string.dm_speed), "4"),
            SettingModel(getString(R.string.dm_allow_top), "关"),
            SettingModel(getString(R.string.dm_allow_bottom), "关"),
            SettingModel(getString(R.string.dm_filter_weight), "关"),
            SettingModel(getString(R.string.allow_vip_colorful_dm), "开"),
            SettingModel(getString(R.string.dm_show_advanced), "开"),
            SettingModel(getString(R.string.dm_merge_duplicate), "开")
        )

        deviceSettings.add(SettingModel("设备型号", Build.MODEL))
        deviceSettings.add(SettingModel("系统版本", "Android ${Build.VERSION.RELEASE}"))
        deviceSettings.add(SettingModel("SDK版本", Build.VERSION.SDK_INT.toString()))
        deviceSettings.add(SettingModel("CPU架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"))
        deviceSettings.add(SettingModel("屏幕分辨率", "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}"))

        commonSettings[0].info = formatFileSize(getCurrentCacheSize())
        restoreSavedSettings()
    }

    private fun setupRecyclerView() {
        adapter = SettingAdapter { position, item ->
            onSettingItemClick(position, item)
        }
        val layoutManager = createExtraSpaceLayoutManager(
            resources.getDimensionPixelSize(R.dimen.px200)
        )
        binding.recyclerViewSetting.layoutManager = layoutManager
        binding.recyclerViewSetting.adapter = adapter
    }

    private fun setupCategoryButtons() {
        binding.buttonSettingCommon.setOnClickListener { showCategory(CATEGORY_COMMON) }
        binding.buttonSettingPlay.setOnClickListener { showCategory(CATEGORY_PLAY) }
        binding.buttonSettingDm.setOnClickListener { showCategory(CATEGORY_DM) }
        binding.buttonSettingDevice.setOnClickListener { showCategory(CATEGORY_DEVICE) }
    }

    private fun showCategory(category: Int) {
        if (currentCategory == category) {
            return
        }
        val previousCategory = currentCategory
        val animate = previousCategory != -1
        currentCategory = category
        updateCategorySelection(category)

        when (category) {
            CATEGORY_COMMON -> {
                showListCategory(commonSettings, animate)
            }
            CATEGORY_PLAY -> {
                showListCategory(playerSettings, animate)
            }
            CATEGORY_DM -> {
                showListCategory(dmSettings, animate)
            }
            CATEGORY_DEVICE -> {
                showListCategory(deviceSettings, animate)
            }
        }
    }

    private fun updateCategorySelection(category: Int) {
        binding.buttonSettingCommon.isSelected = category == CATEGORY_COMMON
        binding.buttonSettingPlay.isSelected = category == CATEGORY_PLAY
        binding.buttonSettingDm.isSelected = category == CATEGORY_DM
        binding.buttonSettingDevice.isSelected = category == CATEGORY_DEVICE
        val buttons = listOf(
            binding.buttonSettingCommon,
            binding.buttonSettingPlay,
            binding.buttonSettingDm,
            binding.buttonSettingDevice
        )
        buttons.forEach { button ->
            val selected = button.isSelected
            button.animate().cancel()
            button.animate()
                .scaleX(if (selected) 1.02f else 1f)
                .scaleY(if (selected) 1.02f else 1f)
                .setDuration(120L)
                .start()
        }
    }

    private fun onSettingItemClick(position: Int, item: SettingModel) {
        when (currentCategory) {
            CATEGORY_COMMON -> handleCommonSettingClick(position, item)
            CATEGORY_PLAY -> handlePlayerSettingClick(position, item)
            CATEGORY_DM -> handleDmSettingClick(position, item)
        }
    }

    private fun handleCommonSettingClick(position: Int, @Suppress("UNUSED_PARAMETER") item: SettingModel) {
        when (position) {
            0 -> clearCache()
            1 -> showCacheLimitDialog()
            2 -> checkVersion()
            3 -> showCommonChoiceDialog(position, KEY_DEFAULT_START_PAGE, HOME_START_PAGE_OPTIONS)
            4 -> showCommonChoiceDialog(position, KEY_IMAGE_QUALITY, arrayOf("低尺寸", "中尺寸", "高尺寸"))
            5 -> showCommonChoiceDialog(position, KEY_THEME, resources.getStringArray(R.array.themes).drop(1).toTypedArray())
            6 -> showCommonChoiceDialog(position, KEY_FULLSCREEN_APP, toggleOptions())
            7 -> showLiveEntryDialog(position)
            8 -> showCommonChoiceDialog(position, KEY_MINOR_PROTECTION, toggleOptions())
        }
    }

    private fun handlePlayerSettingClick(position: Int, @Suppress("UNUSED_PARAMETER") item: SettingModel) {
        when (position) {
            0 -> showPlayerChoiceDialog(position, KEY_DEFAULT_VIDEO_QUALITY, arrayOf("自动", "8K", "杜比视界", "HDR Vivid", "HDR", "4K", "1080P60", "1080P+", "智能修复", "1080P", "720P60", "720P", "480P", "360P", "240P"))
            1 -> showPlayerChoiceDialog(position, KEY_DEFAULT_AUDIO_TRACK, arrayOf("192kbps", "132kbps", "64kbps", "杜比全景声", "Hi-Res无损"))
            2 -> showPlayerChoiceDialog(position, KEY_DEFAULT_PLAY_SPEED, arrayOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0"))
            3 -> showPlayerChoiceDialog(position, KEY_AFTER_PLAY, arrayOf("什么都不做", "播推荐视频", "播列表中的下一个", "播剧集和PV中的下一个"))
            4 -> showPlayerChoiceDialog(position, KEY_PLAY_FINISH_EXIT_PLAYER, toggleOptions())
            5 -> showPlayerChoiceDialog(position, KEY_SHOW_RE_FF, toggleOptions())
            6 -> showPlayerChoiceDialog(position, KEY_VIDEO_CODEC, arrayOf("AVC", "HEVC", "AV1"))
            7 -> showPlayerChoiceDialog(position, KEY_SHOW_SUBTITLE_DEFAULT, toggleOptions())
            8 -> showPlayerChoiceDialog(position, KEY_SUBTITLE_TEXT_SIZE, arrayOf("35", "40", "45", "50", "55", "60"))
            9 -> showPlayerChoiceDialog(position, KEY_SHOW_DEBUG, toggleOptions())
            10 -> showPlayerChoiceDialog(position, KEY_SHOW_VIDEO_DETAIL, toggleOptions())
            11 -> showPlayerChoiceDialog(position, KEY_SHOW_BOTTOM_PROGRESS_BAR, toggleOptions())
            12 -> showPlayerChoiceDialog(position, KEY_SIMPLE_KEY_PRESS, toggleOptions())
            13 -> showPlayerChoiceDialog(position, KEY_GIVE_COIN_NUMBER, arrayOf("1", "2"))
            14 -> showPlayerChoiceDialog(position, KEY_SHOW_NEXT_PREVIOUS, toggleOptions())
            15 -> showPlayerChoiceDialog(position, KEY_SHOW_DM_SWITCH, toggleOptions())
            16 -> showPlayerChoiceDialog(position, KEY_FF_SEEK_SECOND, arrayOf("10s", "15s", "20s", "25s", "30s", "35s", "40s", "45s", "50s", "55s", "60s"))
        }
    }

    private fun handleDmSettingClick(position: Int, @Suppress("UNUSED_PARAMETER") item: SettingModel) {
        when (position) {
            0 -> showDmChoiceDialog(position, KEY_DM_SWITCH, toggleOptions())
            1 -> showDmChoiceDialog(position, KEY_DM_ALPHA, arrayOf("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"))
            2 -> showDmChoiceDialog(position, KEY_DM_TEXT_SIZE, Array(26) { (30 + it).toString() })
            3 -> showDmChoiceDialog(position, KEY_DM_SCREEN_AREA, arrayOf("1/8", "1/6", "1/4", "1/2", "3/4", "全屏"))
            4 -> showDmChoiceDialog(position, KEY_DM_SPEED, arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9"))
            5 -> showDmChoiceDialog(position, KEY_DM_ALLOW_TOP, toggleOptions())
            6 -> showDmChoiceDialog(position, KEY_DM_ALLOW_BOTTOM, toggleOptions())
            7 -> showDmChoiceDialog(position, KEY_DM_FILTER_WEIGHT, DM_SMART_FILTER_OPTIONS)
            8 -> showDmChoiceDialog(position, KEY_DM_ALLOW_VIP_COLORFUL_DM, toggleOptions())
            9 -> showDmChoiceDialog(position, KEY_DM_SHOW_ADVANCED, toggleOptions())
            10 -> showDmChoiceDialog(position, KEY_DM_MERGE_DUPLICATE, toggleOptions())
        }
    }

    private fun clearCache() {
        try {
            val context = requireContext()
            PlayerMediaCache.clear(context)
            FileCacheManager.clear()
            runCatching { ImageLoader.clearMemory(context) }
            ImageLoader.clearDiskCache(context)
            deleteDir(context.cacheDir)
            context.externalCacheDir?.let { deleteDir(it) }
            commonSettings[0].info = formatFileSize(getCurrentCacheSize())
            adapter.notifyItemChanged(0)
            android.widget.Toast.makeText(requireContext(), "缓存已清除", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.e("SettingsFragment", "clearCache failed", e)
        }
    }

    private fun showCacheLimitDialog() {
        showChoiceDialog(
            title = commonSettings[1].title,
            currentValue = commonSettings[1].info,
            options = arrayOf("不限制", "200 MB", "500 MB", "1 GB")
        ) { value ->
            updateSetting(commonSettings, 1, value)
            prefs.edit().putString(KEY_CACHE_LIMIT, value).apply()
            FileCacheManager.trimToLimit()
            commonSettings[0].info = formatFileSize(getCurrentCacheSize())
            adapter.notifyItemChanged(0)
        }
    }

    private fun checkVersion() {
        android.widget.Toast.makeText(requireContext(), "当前已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun getFolderSize(folder: java.io.File): Long {
        var size: Long = 0
        val files = folder.listFiles()
        if (files != null) {
            for (file in files) {
                size += if (file.isDirectory) {
                    getFolderSize(file)
                } else {
                    file.length()
                }
            }
        }
        return size
    }

    private fun deleteDir(folder: java.io.File) {
        val files = folder.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isDirectory) {
                    deleteDir(file)
                } else {
                    file.delete()
                }
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024))
            else -> String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024 * 1024))
        }
    }

    private fun restoreSavedSettings() {
        applySavedValue(commonSettings, 1, KEY_CACHE_LIMIT)
        val defaultStartPage = prefs.getInt("defaultStartPage", -1)
        if (defaultStartPage >= 0) {
            commonSettings[3].info = HOME_START_PAGE_OPTIONS
                .getOrNull(defaultStartPage)
                ?: HOME_START_PAGE_OPTIONS.first()
        } else {
            applySavedValue(commonSettings, 3, KEY_DEFAULT_START_PAGE)
        }
        if (commonSettings[3].info !in HOME_START_PAGE_OPTIONS) {
            commonSettings[3].info = HOME_START_PAGE_OPTIONS.first()
        }
        applySavedValue(commonSettings, 4, KEY_IMAGE_QUALITY)
        val theme = try { prefs.getInt("theme", 1) } catch (_: ClassCastException) { prefs.getString("theme", "1")?.toIntOrNull() ?: 1 }
        commonSettings[5].info = theme.toThemeName()
        applySavedValue(commonSettings, 6, KEY_FULLSCREEN_APP)
        applySavedValue(commonSettings, 7, KEY_LIVE_ENTRY)
        applySavedValue(commonSettings, 8, KEY_MINOR_PROTECTION)

        applySavedValue(playerSettings, 0, KEY_DEFAULT_VIDEO_QUALITY)
        applySavedValue(playerSettings, 1, KEY_DEFAULT_AUDIO_TRACK)
        applySavedValue(playerSettings, 2, KEY_DEFAULT_PLAY_SPEED)
        applySavedValue(playerSettings, 3, KEY_AFTER_PLAY)
        applySavedValue(playerSettings, 4, KEY_PLAY_FINISH_EXIT_PLAYER)
        applySavedValue(playerSettings, 5, KEY_SHOW_RE_FF)
        applySavedValue(playerSettings, 6, KEY_VIDEO_CODEC)
        applySavedValue(playerSettings, 7, KEY_SHOW_SUBTITLE_DEFAULT)
        applySavedValue(playerSettings, 8, KEY_SUBTITLE_TEXT_SIZE)
        applySavedValue(playerSettings, 9, KEY_SHOW_DEBUG)
        applySavedValue(playerSettings, 10, KEY_SHOW_VIDEO_DETAIL)
        applySavedValue(playerSettings, 11, KEY_SHOW_BOTTOM_PROGRESS_BAR)
        applySavedValue(playerSettings, 12, KEY_SIMPLE_KEY_PRESS)
        applySavedValue(playerSettings, 13, KEY_GIVE_COIN_NUMBER)
        applySavedValue(playerSettings, 14, KEY_SHOW_NEXT_PREVIOUS)
        applySavedValue(playerSettings, 15, KEY_SHOW_DM_SWITCH)
        applySavedValue(playerSettings, 16, KEY_FF_SEEK_SECOND)

        applySavedValue(dmSettings, 0, KEY_DM_SWITCH)
        applySavedValue(dmSettings, 1, KEY_DM_ALPHA)
        applySavedValue(dmSettings, 2, KEY_DM_TEXT_SIZE)
        applySavedValue(dmSettings, 3, KEY_DM_SCREEN_AREA)
        applySavedValue(dmSettings, 4, KEY_DM_SPEED)
        applySavedValue(dmSettings, 5, KEY_DM_ALLOW_TOP)
        applySavedValue(dmSettings, 6, KEY_DM_ALLOW_BOTTOM)
        dmSettings[7].info = normalizeDanmakuSmartFilterValue(
            prefs.getString(KEY_DM_FILTER_WEIGHT, dmSettings[7].info)
        )
        applySavedValue(dmSettings, 8, KEY_DM_ALLOW_VIP_COLORFUL_DM)
        applySavedValue(dmSettings, 9, KEY_DM_SHOW_ADVANCED)
        applySavedValue(dmSettings, 10, KEY_DM_MERGE_DUPLICATE)
    }

    private fun applySavedValue(target: MutableList<SettingModel>, index: Int, key: String) {
        prefs.getString(key, null)?.let { saved ->
            target.getOrNull(index)?.info = saved
        }
    }

    private fun Int.toThemeName(): String {
        return when (this) {
            1 -> "黑色"
            2 -> "白色"
            3 -> "经典主题"
            4 -> "粉色"
            5 -> "蓝色"
            6 -> "紫色"
            7 -> "红色"
            else -> "黑色"
        }
    }

    private fun showCommonChoiceDialog(position: Int, key: String, options: Array<String>) {
        showChoiceDialog(commonSettings[position].title, commonSettings[position].info, options) { value ->
            updateSetting(commonSettings, position, value)
            prefs.edit().putString(key, value).apply()
            if (key == KEY_DEFAULT_START_PAGE) {
                prefs.edit()
                    .putInt("defaultStartPage", HOME_START_PAGE_OPTIONS.indexOf(value).coerceAtLeast(0))
                    .apply()
            } else if (key == KEY_IMAGE_QUALITY) {
                val qualityLevel = when (value) {
                    "低尺寸" -> 0
                    "高尺寸" -> 2
                    else -> 1
                }
                prefs.edit()
                    .putInt("imageQualityLevel", qualityLevel)
                    .apply()
                ImageLoader.invalidateImageQualityCache()
            } else if (key == KEY_THEME) {
                prefs.edit()
                    .putInt("theme", value.toLegacyTheme())
                    .apply()
                activity?.recreate()
            }
        }
    }

    private fun String.toLegacyTheme(): Int {
        return when (this) {
            "黑色" -> 1
            "白色" -> 2
            "经典主题" -> 3
            "粉色" -> 4
            "蓝色" -> 5
            "紫色" -> 6
            "红色" -> 7
            "自动" -> if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                1
            } else {
                2
            }
            else -> 1
        }
    }

    private fun showLiveEntryDialog(position: Int) {
        showChoiceDialog(
            title = commonSettings[position].title,
            currentValue = commonSettings[position].info,
            options = toggleOptions()
        ) { value ->
            updateSetting(commonSettings, position, value)
            prefs.edit().putString(KEY_LIVE_ENTRY, value).apply()
            val activity = activity as? com.tutu.myblbl.ui.activity.MainActivity
            activity?.applyLiveEntryVisibility()
        }
    }

    private fun showPlayerChoiceDialog(position: Int, key: String, options: Array<String>) {
        showChoiceDialog(playerSettings[position].title, playerSettings[position].info, options) { value ->
            updateSetting(playerSettings, position, value)
            prefs.edit().putString(key, value).apply()
        }
    }

    private fun getCurrentCacheSize(): Long {
        val internal = getFolderSize(requireContext().cacheDir)
        val external = requireContext().externalCacheDir?.let { getFolderSize(it) } ?: 0L
        return internal + external
    }

    private fun showDmChoiceDialog(position: Int, key: String, options: Array<String>) {
        showChoiceDialog(dmSettings[position].title, dmSettings[position].info, options) { value ->
            updateSetting(dmSettings, position, value)
            persistDmSetting(key, value)
        }
    }

    private fun showChoiceDialog(
        title: String,
        currentValue: String,
        options: Array<String>,
        onSelected: (String) -> Unit
    ) {
        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_setting_choice)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<android.widget.TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = title

        val choiceAdapter = SettingSelectionDialogAdapter(
            options = options.toList(),
            selectedIndex = options.indexOf(currentValue).coerceAtLeast(0)
        ) { selectedIndex ->
            options.getOrNull(selectedIndex)?.let(onSelected)
            dialog.dismiss()
        }

        val dialogLayoutManager = createExtraSpaceLayoutManager(
            resources.getDimensionPixelSize(R.dimen.px100)
        )
        recyclerView?.layoutManager = dialogLayoutManager
        recyclerView?.adapter = choiceAdapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }

        dialog.setOnShowListener {
            recyclerView?.post {
                choiceAdapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.show()
    }

    private fun showListCategory(settings: MutableList<SettingModel>, animate: Boolean) {
        swapPanels(animate = animate)
        updateSettingsList(settings, animate)
    }

    private fun swapPanels(animate: Boolean) {
        val recyclerView = binding.recyclerViewSetting
        if (recyclerView.visibility == View.VISIBLE) {
            return
        }
        recyclerView.animate().cancel()
        if (!animate) {
            recyclerView.visibility = View.VISIBLE
            recyclerView.alpha = 1f
            recyclerView.translationX = 0f
            return
        }
        val offset = resources.getDimension(R.dimen.px20)
        recyclerView.visibility = View.VISIBLE
        recyclerView.alpha = 0f
        recyclerView.translationX = offset
        recyclerView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(150L)
            .start()
    }

    private fun updateSettingsList(settings: MutableList<SettingModel>, animate: Boolean) {
        val recyclerView = binding.recyclerViewSetting
        val switchVersion = ++categorySwitchVersion
        recyclerView.animate().cancel()
        if (!animate) {
            adapter.setData(settings)
            recyclerView.alpha = 1f
            recyclerView.translationY = 0f
            recyclerView.scrollToPosition(0)
            return
        }
        val offset = resources.getDimension(R.dimen.px8)
        recyclerView.animate()
            .alpha(0.55f)
            .translationY(offset)
            .setDuration(90L)
            .withEndAction {
                if (switchVersion != categorySwitchVersion) {
                    return@withEndAction
                }
                adapter.setData(settings)
                recyclerView.scrollToPosition(0)
                recyclerView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(140L)
                    .start()
            }
            .start()
    }

    private fun updateSetting(target: MutableList<SettingModel>, position: Int, value: String) {
        target.getOrNull(position)?.info = value
        if (isCurrentCategoryList(target)) {
            adapter.notifyItemChanged(position)
        }
    }

    private fun isCurrentCategoryList(target: MutableList<SettingModel>): Boolean {
        return when (currentCategory) {
            CATEGORY_COMMON -> target === commonSettings
            CATEGORY_PLAY -> target === playerSettings
            CATEGORY_DM -> target === dmSettings
            else -> false
        }
    }

    private fun persistDmSetting(key: String, value: String) {
        val persistedValue = if (key == KEY_DM_FILTER_WEIGHT) {
            normalizeDanmakuSmartFilterValue(value)
        } else {
            value
        }
        prefs.edit().putString(key, persistedValue).apply()
    }

    private fun requestInitialCategoryFocus() {
        if (!shouldRequestInitialCategoryFocus) {
            return
        }
        binding.buttonSettingCommon.post {
            if (!isAdded || !shouldRequestInitialCategoryFocus) {
                return@post
            }
            binding.buttonSettingCommon.requestFocus()
            shouldRequestInitialCategoryFocus = false
        }
    }

    private fun toggleOptions(): Array<String> = arrayOf("开", "关")

    private fun createExtraSpaceLayoutManager(extraLayoutSpacePx: Int): LinearLayoutManager {
        return object : LinearLayoutManager(requireContext()) {
            override fun calculateExtraLayoutSpace(
                state: RecyclerView.State,
                extraLayoutSpace: IntArray
            ) {
                extraLayoutSpace[0] = extraLayoutSpacePx
                extraLayoutSpace[1] = extraLayoutSpacePx
            }
        }
    }

}
