package com.tutu.myblbl.model.series

import java.io.Serializable

data class AllSeriesFilterModel(
    val title: String = "",
    val key: String = "",
    val iconResourceId: Int = 0,
    val currentSelect: Int = 0,
    val sortDirection: Int = 0,
    val options: List<AllSeriesFilterOption> = emptyList()
) : Serializable
