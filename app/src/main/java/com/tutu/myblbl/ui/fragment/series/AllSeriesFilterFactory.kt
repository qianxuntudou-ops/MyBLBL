package com.tutu.myblbl.ui.fragment.series

import android.content.Context
import com.tutu.myblbl.R
import com.tutu.myblbl.model.series.AllSeriesFilterModel
import com.tutu.myblbl.model.series.AllSeriesFilterOption
import com.tutu.myblbl.model.series.SeriesType

object AllSeriesFilterFactory {

    fun create(context: Context, seasonType: Int): List<AllSeriesFilterModel> {
        return when (seasonType) {
            SeriesType.ANIME -> createAnimeFilters(context)
            SeriesType.CHINA_ANIME -> createChinaAnimeFilters(context)
            SeriesType.MOVIE -> createMovieFilters(context)
            SeriesType.DRAMA -> createDramaFilters(context)
            SeriesType.DOCUMENTARY -> createDocumentaryFilters(context)
            SeriesType.VARIETY -> createVarietyFilters(context)
            else -> createAnimeFilters(context)
        }
    }

    fun createFiltersForUrl(url: String, seasonType: Int): List<AllSeriesFilterModel> {
        val urlParams = parseUrlParams(url)
        val filters = when (seasonType) {
            SeriesType.ANIME -> createAnimeFiltersNoContext()
            SeriesType.CHINA_ANIME -> createChinaAnimeFiltersNoContext()
            SeriesType.MOVIE -> createMovieFiltersNoContext()
            SeriesType.DRAMA -> createDramaFiltersNoContext()
            SeriesType.DOCUMENTARY -> createDocumentaryFiltersNoContext()
            SeriesType.VARIETY -> createVarietyFiltersNoContext()
            else -> createAnimeFiltersNoContext()
        }
        return applyUrlParamsToFilters(filters, urlParams)
    }

    fun applyInitialFilters(
        context: Context,
        seasonType: Int,
        moreUrl: String
    ): List<AllSeriesFilterModel> {
        if (moreUrl.isBlank()) {
            return create(context, seasonType)
        }
        val urlFilters = createFiltersForUrl(moreUrl, seasonType)
        val defaultFilters = create(context, seasonType)
        return defaultFilters.map { defaultFilter ->
            if (shouldIgnoreInitialUrlSelection(seasonType, defaultFilter.key)) {
                return@map defaultFilter
            }
            val urlFilter = urlFilters.find { it.key == defaultFilter.key }
            if (urlFilter != null && urlFilter.currentSelect > 0) {
                val matchedIndex = defaultFilter.options.indexOfFirst { it.value == urlFilter.options[urlFilter.currentSelect].value }
                if (matchedIndex >= 0) {
                    defaultFilter.copy(currentSelect = matchedIndex)
                } else {
                    defaultFilter
                }
            } else {
                defaultFilter
            }
        }
    }

    private fun shouldIgnoreInitialUrlSelection(seasonType: Int, filterKey: String): Boolean {
        return seasonType == SeriesType.ANIME && filterKey == "area"
    }

    private fun createAnimeFilters(context: Context): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilter(context, SeriesType.ANIME))
        add(createVersionTypeFilter(context))
        add(createSpokenLanguageFilter(context))
        add(createAreaFilter(context, SeriesType.ANIME))
        add(createStatusFilter(context))
        add(createPayTypeFilter(context))
        add(createSeasonMonthFilter(context))
        add(createAnimeYearFilter(context))
        add(createStyleFilter(context, SeriesType.ANIME))
    }

    private fun createAnimeFiltersNoContext(): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilterNoContext(SeriesType.ANIME))
        add(createVersionTypeFilterNoContext())
        add(createSpokenLanguageFilterNoContext())
        add(createAreaFilterNoContext(SeriesType.ANIME))
        add(createStatusFilterNoContext())
        add(createPayTypeFilterNoContext())
        add(createSeasonMonthFilterNoContext())
        add(createAnimeYearFilterNoContext())
        add(createStyleFilterNoContext(SeriesType.ANIME))
    }

    private fun createChinaAnimeFilters(context: Context): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilter(context, SeriesType.CHINA_ANIME))
        add(createVersionTypeFilter(context))
        add(createStatusFilter(context))
        add(createPayTypeFilter(context))
        add(createAnimeYearFilter(context))
        add(createStyleFilter(context, SeriesType.CHINA_ANIME))
    }

    private fun createChinaAnimeFiltersNoContext(): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilterNoContext(SeriesType.CHINA_ANIME))
        add(createVersionTypeFilterNoContext())
        add(createStatusFilterNoContext())
        add(createPayTypeFilterNoContext())
        add(createAnimeYearFilterNoContext())
        add(createStyleFilterNoContext(SeriesType.CHINA_ANIME))
    }

    private fun createMovieFilters(context: Context): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilter(context, SeriesType.MOVIE))
        add(createAreaFilter(context, SeriesType.MOVIE))
        add(createStyleFilter(context, SeriesType.MOVIE))
        add(createReleaseDateFilter(context))
        add(createPayTypeFilter(context))
    }

    private fun createMovieFiltersNoContext(): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilterNoContext(SeriesType.MOVIE))
        add(createAreaFilterNoContext(SeriesType.MOVIE))
        add(createStyleFilterNoContext(SeriesType.MOVIE))
        add(createReleaseDateFilterNoContext())
        add(createPayTypeFilterNoContext())
    }

    private fun createDramaFilters(context: Context): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilter(context, SeriesType.DRAMA))
        add(createAreaFilter(context, SeriesType.DRAMA))
        add(createStyleFilter(context, SeriesType.DRAMA))
        add(createReleaseDateFilter(context))
        add(createPayTypeFilter(context))
    }

    private fun createDramaFiltersNoContext(): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilterNoContext(SeriesType.DRAMA))
        add(createAreaFilterNoContext(SeriesType.DRAMA))
        add(createStyleFilterNoContext(SeriesType.DRAMA))
        add(createReleaseDateFilterNoContext())
        add(createPayTypeFilterNoContext())
    }

    private fun createDocumentaryFilters(context: Context): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilter(context, SeriesType.DOCUMENTARY))
        add(createStyleFilter(context, SeriesType.DOCUMENTARY))
        add(createProducerFilter(context))
        add(createReleaseDateFilter(context))
        add(createPayTypeFilter(context))
    }

    private fun createDocumentaryFiltersNoContext(): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilterNoContext(SeriesType.DOCUMENTARY))
        add(createStyleFilterNoContext(SeriesType.DOCUMENTARY))
        add(createProducerFilterNoContext())
        add(createReleaseDateFilterNoContext())
        add(createPayTypeFilterNoContext())
    }

    private fun createVarietyFilters(context: Context): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilter(context, SeriesType.VARIETY))
        add(createPayTypeFilter(context))
        add(createStyleFilter(context, SeriesType.VARIETY))
    }

    private fun createVarietyFiltersNoContext(): List<AllSeriesFilterModel> = buildList {
        add(createOrderFilterNoContext(SeriesType.VARIETY))
        add(createPayTypeFilterNoContext())
        add(createStyleFilterNoContext(SeriesType.VARIETY))
    }

    private fun parseUrlParams(url: String): Map<String, String> {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return emptyMap()
        val query = url.substring(queryStart + 1)
        return query.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun applyUrlParamsToFilters(
        filters: List<AllSeriesFilterModel>,
        urlParams: Map<String, String>
    ): List<AllSeriesFilterModel> {
        return filters.map { filter ->
            val paramValue = urlParams[filter.key] ?: return@map filter
            if (paramValue == "-1" || paramValue.isBlank()) return@map filter
            val matchedIndex = filter.options.indexOfFirst { it.value == paramValue }
            if (matchedIndex >= 0) {
                filter.copy(currentSelect = matchedIndex)
            } else {
                filter
            }
        }
    }

    private fun createAreaFilterNoContext(seasonType: Int): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "地区",
            key = "area",
            iconResourceId = 0,
            options = createAreaOptions(seasonType)
        )
    }

    private fun createStyleFilterNoContext(seasonType: Int): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "风格",
            key = "style_id",
            iconResourceId = 0,
            options = createStyleOptions(seasonType)
        )
    }

    private fun createOrderFilterNoContext(seasonType: Int): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "排序",
            key = "order",
            iconResourceId = 0,
            options = createOrderOptions(seasonType)
        )
    }

    private fun createStatusFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "状态",
            key = "is_finish",
            iconResourceId = 0,
            options = createStatusOptions()
        )
    }

    private fun createPayTypeFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "付费类型",
            key = "season_status",
            iconResourceId = 0,
            options = createPayTypeOptions()
        )
    }

    private fun createReleaseDateFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "年份",
            key = "release_date",
            iconResourceId = 0,
            options = createReleaseDateOptions()
        )
    }

    private fun createAnimeYearFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "年份",
            key = "year",
            iconResourceId = 0,
            options = createAnimeYearOptions()
        )
    }

    private fun createVersionTypeFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "类型",
            key = "season_version",
            iconResourceId = 0,
            options = createVersionTypeOptions()
        )
    }

    private fun createSpokenLanguageFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "配音",
            key = "spoken_language_type",
            iconResourceId = 0,
            options = createSpokenLanguageOptions()
        )
    }

    private fun createSeasonMonthFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "季度",
            key = "season_month",
            iconResourceId = 0,
            options = createSeasonMonthOptions()
        )
    }

    private fun createProducerFilterNoContext(): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = "出品",
            key = "producer_id",
            iconResourceId = 0,
            options = createProducerOptions()
        )
    }

    private fun createAreaFilter(context: Context, seasonType: Int): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_area),
            key = "area",
            iconResourceId = R.drawable.ic_earth,
            options = createAreaOptions(seasonType)
        )
    }

    private fun createStyleFilter(context: Context, seasonType: Int): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_style),
            key = "style_id",
            iconResourceId = R.drawable.tab_dynamic,
            options = createStyleOptions(seasonType)
        )
    }

    private fun createOrderFilter(context: Context, seasonType: Int): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_order),
            key = "order",
            iconResourceId = R.drawable.ic_sort,
            options = createOrderOptions(seasonType)
        )
    }

    private fun createStatusFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_status),
            key = "is_finish",
            iconResourceId = R.drawable.ic_status,
            options = createStatusOptions()
        )
    }

    private fun createPayTypeFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_pay_type),
            key = "season_status",
            iconResourceId = R.drawable.ic_pay,
            options = createPayTypeOptions()
        )
    }

    private fun createReleaseDateFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_year),
            key = "release_date",
            iconResourceId = R.drawable.ic_calendar,
            options = createReleaseDateOptions()
        )
    }

    private fun createAnimeYearFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_year),
            key = "year",
            iconResourceId = R.drawable.ic_calendar,
            options = createAnimeYearOptions()
        )
    }

    private fun createVersionTypeFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_type),
            key = "season_version",
            iconResourceId = R.drawable.tab_recommend,
            options = createVersionTypeOptions()
        )
    }

    private fun createSpokenLanguageFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_spoken),
            key = "spoken_language_type",
            iconResourceId = R.drawable.ic_voice,
            options = createSpokenLanguageOptions()
        )
    }

    private fun createSeasonMonthFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_season),
            key = "season_month",
            iconResourceId = R.drawable.ic_calendar,
            options = createSeasonMonthOptions()
        )
    }

    private fun createProducerFilter(context: Context): AllSeriesFilterModel {
        return AllSeriesFilterModel(
            title = context.getString(R.string.filter_publish),
            key = "producer_id",
            iconResourceId = R.drawable.ic_tv,
            options = createProducerOptions()
        )
    }

    private fun createAreaOptions(seasonType: Int): List<AllSeriesFilterOption> {
        return when (seasonType) {
            SeriesType.ANIME -> listOf(
                option("全部地区", "-1"),
                option("日本", "2"),
                option("美国", "3"),
                option("其他", "1,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70")
            )
            SeriesType.DRAMA -> listOf(
                option("全部地区", "-1"),
                option("中国", "1"),
                option("日本", "2"),
                option("美国", "3"),
                option("英国", "4"),
                option("泰国", "10"),
                option("其他", "5,8,9,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70")
            )
            else -> listOf(
                option("全部地区", "-1"),
                option("中国大陆", "1"),
                option("中国港台", "6,7"),
                option("美国", "3"),
                option("日本", "2"),
                option("韩国", "8"),
                option("法国", "9"),
                option("英国", "4"),
                option("德国", "15"),
                option("泰国", "10"),
                option("意大利", "35"),
                option("西班牙", "13"),
                option("其他", "5,11,12,14,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70")
            )
        }
    }

    private fun createStyleOptions(seasonType: Int): List<AllSeriesFilterOption> {
        return when (seasonType) {
            SeriesType.ANIME, SeriesType.CHINA_ANIME -> listOf(
                option("全部风格", "-1"),
                option("原创", "10010"),
                option("漫画改", "10011"),
                option("小说改", "10012"),
                option("游戏改", "10013"),
                option("特摄", "10102"),
                option("布袋戏", "10015"),
                option("热血", "10016"),
                option("穿越", "10017"),
                option("奇幻", "10018"),
                option("战斗", "10020"),
                option("搞笑", "10021"),
                option("日常", "10022"),
                option("科幻", "10023"),
                option("萌系", "10024"),
                option("治愈", "10025"),
                option("校园", "10026"),
                option("少儿", "10027"),
                option("泡面", "10028"),
                option("恋爱", "10029"),
                option("少女", "10030"),
                option("魔法", "10031"),
                option("冒险", "10032"),
                option("历史", "10033"),
                option("架空", "10034"),
                option("机战", "10035"),
                option("神魔", "10036"),
                option("声控", "10037"),
                option("运动", "10038"),
                option("励志", "10039"),
                option("音乐", "10040"),
                option("推理", "10041"),
                option("社团", "10042"),
                option("智斗", "10043"),
                option("催泪", "10044"),
                option("美食", "10045"),
                option("偶像", "10046"),
                option("乙女", "10047"),
                option("职场", "10048")
            )
            SeriesType.DRAMA -> listOf(
                option("全部风格", "-1"),
                option("搞笑", "10021"),
                option("奇幻", "10018"),
                option("战争", "10058"),
                option("武侠", "10078"),
                option("青春", "10079"),
                option("短剧", "10103"),
                option("都市", "10080"),
                option("古装", "10081"),
                option("谍战", "10082"),
                option("经典", "10083"),
                option("情感", "10084"),
                option("悬疑", "10057"),
                option("励志", "10039"),
                option("神话", "10085"),
                option("穿越", "10017"),
                option("年代", "10086"),
                option("农村", "10087"),
                option("刑侦", "10088"),
                option("剧情", "10050"),
                option("家庭", "10061"),
                option("历史", "10033"),
                option("军旅", "10089")
            )
            SeriesType.MOVIE -> listOf(
                option("全部风格", "-1"),
                option("短片", "10104"),
                option("剧情", "10050"),
                option("喜剧", "10051"),
                option("爱情", "10052"),
                option("动作", "10053"),
                option("恐怖", "10054"),
                option("科幻", "10023"),
                option("犯罪", "10055"),
                option("惊悚", "10056"),
                option("悬疑", "10057"),
                option("奇幻", "10018"),
                option("战争", "10058"),
                option("动画", "10059"),
                option("传记", "10060"),
                option("家庭", "10061"),
                option("歌舞", "10062"),
                option("历史", "10033"),
                option("冒险", "10032"),
                option("纪实", "10063"),
                option("灾难", "10064"),
                option("漫画改", "10011"),
                option("小说改", "10012")
            )
            SeriesType.VARIETY -> listOf(
                option("全部风格", "-1"),
                option("音乐", "10040"),
                option("访谈", "10090"),
                option("脱口秀", "10091"),
                option("真人秀", "10092"),
                option("选秀", "10094"),
                option("美食", "10045"),
                option("旅游", "10095"),
                option("晚会", "10098"),
                option("演唱会", "10096"),
                option("情感", "10084"),
                option("喜剧", "10051"),
                option("亲子", "10097"),
                option("文化", "10100"),
                option("职场", "10048"),
                option("萌宠", "10069"),
                option("养成", "10099")
            )
            else -> listOf(
                option("全部风格", "-1"),
                option("历史", "10033"),
                option("美食", "10045"),
                option("人文", "10065"),
                option("科技", "10066"),
                option("探险", "10067"),
                option("宇宙", "10068"),
                option("萌宠", "10069"),
                option("社会", "10070"),
                option("动物", "10071"),
                option("自然", "10072"),
                option("医疗", "10073"),
                option("军事", "10074"),
                option("灾难", "10064"),
                option("罪案", "10075"),
                option("神秘", "10076"),
                option("旅行", "10077"),
                option("运动", "10038"),
                option("电影", "-10")
            )
        }
    }

    private fun createOrderOptions(seasonType: Int): List<AllSeriesFilterOption> {
        return when (seasonType) {
            SeriesType.ANIME, SeriesType.CHINA_ANIME -> listOf(
                option("追番人数", "3"),
                option("最近更新", "0"),
                option("最高评分", "4"),
                option("播放数量", "2"),
                option("开播时间", "5")
            )
            SeriesType.VARIETY -> listOf(
                option("播放数量", "2"),
                option("最近更新", "0"),
                option("最新上映", "6"),
                option("最高评分", "4"),
                option("弹幕数量", "1")
            )
            SeriesType.MOVIE -> listOf(
                option("播放数量", "2"),
                option("最近更新", "0"),
                option("最新上映", "6"),
                option("最高评分", "4")
            )
            SeriesType.DRAMA -> listOf(
                option("播放数量", "2"),
                option("最近更新", "0"),
                option("弹幕数量", "1"),
                option("追剧人数", "3"),
                option("最高评分", "4")
            )
            SeriesType.DOCUMENTARY -> listOf(
                option("播放数量", "2"),
                option("最高评分", "4"),
                option("最近更新", "0"),
                option("最新上映", "6"),
                option("弹幕数量", "1")
            )
            else -> listOf(option("综合排序", "-1"))
        }
    }

    private fun createStatusOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部状态", "-1"),
            option("完结", "1"),
            option("连载", "0")
        )
    }

    private fun createPayTypeOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部付费类型", "-1"),
            option("免费", "1"),
            option("付费", "2,6"),
            option("大会员", "4,6")
        )
    }

    private fun createVersionTypeOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部类型", "-1"),
            option("正片", "1"),
            option("电影", "2"),
            option("其他", "3")
        )
    }

    private fun createSpokenLanguageOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部配音", "-1"),
            option("原声", "1"),
            option("中文配音", "2")
        )
    }

    private fun createSeasonMonthOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部季度", "-1"),
            option("1月", "1"),
            option("4月", "4"),
            option("7月", "7"),
            option("10月", "10")
        )
    }

    private fun createProducerOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部出品", "-1"),
            option("CCTV", "1"),
            option("BBC", "2"),
            option("Discovery", "3"),
            option("国家地理", "4"),
            option("NHK", "5"),
            option("历史频道", "7"),
            option("卫视", "8"),
            option("自制", "9"),
            option("ITV", "10"),
            option("SKY", "11"),
            option("ZDF", "12"),
            option("合作机构", "13"),
            option("国内其他", "14"),
            option("国外其他", "15")
        )
    }

    private fun createReleaseDateOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部年份", "-1"),
            option("2023", "[2023-01-01 00:00:00,2024-01-01 00:00:00)"),
            option("2022", "[2022-01-01 00:00:00,2023-01-01 00:00:00)"),
            option("2021", "[2021-01-01 00:00:00,2022-01-01 00:00:00)"),
            option("2020", "[2020-01-01 00:00:00,2021-01-01 00:00:00)"),
            option("2019", "[2019-01-01 00:00:00,2020-01-01 00:00:00)"),
            option("2018", "[2018-01-01 00:00:00,2019-01-01 00:00:00)"),
            option("2017", "[2017-01-01 00:00:00,2018-01-01 00:00:00)"),
            option("2016", "[2016-01-01 00:00:00,2017-01-01 00:00:00)"),
            option("2015-2010", "[2010-01-01 00:00:00,2016-01-01 00:00:00)"),
            option("2009-2005", "[2005-01-01 00:00:00,2010-01-01 00:00:00)"),
            option("2004-2000", "[2000-01-01 00:00:00,2005-01-01 00:00:00)"),
            option("90年代", "[1990-01-01 00:00:00,2000-01-01 00:00:00)"),
            option("80年代", "[1980-01-01 00:00:00,1990-01-01 00:00:00)"),
            option("更早", "[,1980-01-01 00:00:00)")
        )
    }

    private fun createAnimeYearOptions(): List<AllSeriesFilterOption> {
        return listOf(
            option("全部年份", "-1"),
            option("2023", "[2023,2024)"),
            option("2022", "[2022,2023)"),
            option("2021", "[2021,2022)"),
            option("2020", "[2020,2021)"),
            option("2019", "[2019,2020)"),
            option("2018", "[2018,2019)"),
            option("2017", "[2017,2018)"),
            option("2016", "[2016,2017)"),
            option("2015", "[2015,2016)"),
            option("2014-2010", "[2010,2015)"),
            option("2009-2005", "[2005,2010)"),
            option("2004-2000", "[2000,2005)"),
            option("90年代", "[1990,2000)"),
            option("80年代", "[1980,1990)"),
            option("更早", "[,1980)")
        )
    }

    private fun option(title: String, value: String): AllSeriesFilterOption {
        return AllSeriesFilterOption(title = title, value = value)
    }
}
