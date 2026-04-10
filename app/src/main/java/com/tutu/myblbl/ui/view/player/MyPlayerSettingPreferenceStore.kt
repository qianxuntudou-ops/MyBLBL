package com.tutu.myblbl.ui.view.player

import android.content.Context
import com.tutu.myblbl.model.dm.DmScreenArea

/**
 * Keeps legacy SharedPreferences parsing isolated from the setting panel UI flow.
 */
internal class MyPlayerSettingPreferenceStore(
    context: Context
) {

    private val prefs = context.getSharedPreferences(
        MyPlayerSettingView.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun loadDanmakuState(
        state: MyPlayerSettingMenuBuilder.PanelState
    ): MyPlayerSettingMenuBuilder.PanelState {
        return state.copy(
            dmEnabled = prefs.getString(MyPlayerSettingView.KEY_DM_ENABLE, null)?.let { it == "开" } ?: true,
            dmAlpha = prefs.getString(MyPlayerSettingView.KEY_DM_ALPHA, null)?.toFloatOrNull() ?: 1.0f,
            dmTextSize = prefs.getString(MyPlayerSettingView.KEY_DM_TEXT_SIZE, null)?.let {
                when (it) {
                    "小号" -> 35
                    "大号" -> 45
                    else -> it.toIntOrNull() ?: 40
                }
            } ?: 40,
            dmSpeed = prefs.getString(MyPlayerSettingView.KEY_DM_SPEED, null)?.toIntOrNull() ?: 4,
            dmArea = prefs.getString(MyPlayerSettingView.KEY_DM_AREA, null)?.let {
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
            dmAllowTop = prefs.getString(MyPlayerSettingView.KEY_DM_ALLOW_TOP, null)?.let { it == "开" } ?: false,
            dmAllowBottom = prefs.getString(MyPlayerSettingView.KEY_DM_ALLOW_BOTTOM, null)?.let { it == "开" } ?: false,
            dmMergeDuplicate = prefs.getString(MyPlayerSettingView.KEY_DM_MERGE_DUPLICATE, null)?.let { it == "开" } ?: true
        )
    }
}
