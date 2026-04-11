package com.tutu.myblbl.feature.player.view

import android.content.Context
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.model.dm.DmScreenArea
import org.koin.core.context.GlobalContext

internal class MyPlayerSettingPreferenceStore(
    context: Context
) {

    private val appSettings: AppSettingsDataStore get() = GlobalContext.get().get()

    fun loadDanmakuState(
        state: MyPlayerSettingMenuBuilder.PanelState
    ): MyPlayerSettingMenuBuilder.PanelState {
        return state.copy(
            dmEnabled = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ENABLE)?.let { it == "开" } ?: true,
            dmAlpha = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ALPHA)?.toFloatOrNull() ?: 1.0f,
            dmTextSize = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_TEXT_SIZE)?.let {
                when (it) {
                    "小号" -> 35
                    "大号" -> 45
                    else -> it.toIntOrNull() ?: 40
                }
            } ?: 40,
            dmSpeed = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_SPEED)?.toIntOrNull() ?: 4,
            dmArea = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_AREA)?.let {
                when (it) {
                    "1/8" -> DmScreenArea.OneEighth.area
                    "1/6" -> DmScreenArea.OneSixth.area
                    "1/4" -> DmScreenArea.Quarter.area
                    "1/2" -> DmScreenArea.Half.area
                    "3/4" -> DmScreenArea.ThreeQuarter.area
                    "全屏" -> DmScreenArea.Full.area
                    else -> it.toIntOrNull() ?: DmScreenArea.Half.area
                }
            } ?: DmScreenArea.Half.area,
            dmAllowTop = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ALLOW_TOP)?.let { it == "开" } ?: false,
            dmAllowBottom = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ALLOW_BOTTOM)?.let { it == "开" } ?: false,
            dmMergeDuplicate = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_MERGE_DUPLICATE)?.let { it == "开" } ?: true
        )
    }
}
