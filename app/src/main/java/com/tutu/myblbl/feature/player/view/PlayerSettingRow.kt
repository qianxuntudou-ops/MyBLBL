package com.tutu.myblbl.feature.player.view

internal sealed interface PlayerSettingRow {
    data class Header(
        val title: String,
        val subTitle: String? = null
    ) : PlayerSettingRow

    data class Item(
        val id: Int,
        val title: String,
        val value: String = "",
        val iconRes: Int? = null,
        val checked: Boolean = false,
        val showArrow: Boolean = true
    ) : PlayerSettingRow
}
