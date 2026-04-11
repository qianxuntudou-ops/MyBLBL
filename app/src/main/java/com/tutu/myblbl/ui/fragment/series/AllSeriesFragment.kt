package com.tutu.myblbl.ui.fragment.series

import android.animation.ValueAnimator
import android.os.Parcelable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentAllSeriesBinding
import com.tutu.myblbl.model.series.AllSeriesFilterModel
import com.tutu.myblbl.model.series.SeriesType
import com.tutu.myblbl.repository.AllSeriesRepository
import com.tutu.myblbl.ui.adapter.AllSeriesFilterAdapter
import com.tutu.myblbl.ui.adapter.SeriesAdapter
import com.tutu.myblbl.ui.adapter.SettingSelectionDialogAdapter
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.ui.base.OnBackPressedHandler
import com.tutu.myblbl.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.ui.view.WrapContentGridLayoutManager
import com.tutu.myblbl.ui.widget.GridSpacingItemDecoration
import com.tutu.myblbl.ui.widget.LinearSpacingItemDecoration
import com.tutu.myblbl.utils.AppLog
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AllSeriesFragment : BaseFragment<FragmentAllSeriesBinding>(), OnBackPressedHandler {

    private enum class FocusArea {
        BACK,
        FILTER,
        CONTENT
    }

    companion object {
        private const val TAG = "AllSeriesFocus"
        private const val ARG_SEASON_TYPE = "seasonType"
        private const val ARG_MORE_URL = "moreUrl"
        private const val ARG_ENTRY_TITLE = "entryTitle"
        private const val MIN_CONTENT_SPAN_COUNT = 2
        private const val MAX_CONTENT_SPAN_COUNT = 6
        private const val BASELINE_CARD_WIDTH_PX = 225
        private const val BASELINE_CARD_SPACING_PX = 20

        fun newInstance(
            seasonType: Int,
            moreUrl: String = "",
            entryTitle: String = ""
        ): AllSeriesFragment {
            return AllSeriesFragment().apply {
                arguments = bundleOf(
                    ARG_SEASON_TYPE to seasonType,
                    ARG_MORE_URL to moreUrl,
                    ARG_ENTRY_TITLE to entryTitle
                )
            }
        }
    }

    private val repository: AllSeriesRepository by inject()

    private var seasonType: Int = SeriesType.ANIME
    private var moreUrl: String = ""
    private var entryTitle: String = ""
    private var currentPage = 1
    private var hasMore = true
    private var isLoading = false
    private var listLayoutState: Parcelable? = null
    private var cachedList = emptyList<com.tutu.myblbl.model.series.SeriesModel>()
    private var isFilterExpanded = false
    private var isTouchScrolling = false
    private var lastTouchInteractionAt = 0L

    private lateinit var adapter: SeriesAdapter
    private lateinit var filterAdapter: AllSeriesFilterAdapter
    private lateinit var gridLayoutManager: WrapContentGridLayoutManager
    private var filters: List<AllSeriesFilterModel> = emptyList()
    private var lastFocusedArea = FocusArea.CONTENT

    override fun initArguments() {
        seasonType = arguments?.getInt(ARG_SEASON_TYPE, SeriesType.ANIME) ?: SeriesType.ANIME
        moreUrl = arguments?.getString(ARG_MORE_URL).orEmpty()
        entryTitle = arguments?.getString(ARG_ENTRY_TITLE).orEmpty()
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentAllSeriesBinding {
        return FragmentAllSeriesBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        filters = AllSeriesFilterFactory.applyInitialFilters(
            context = requireContext(),
            seasonType = seasonType,
            moreUrl = moreUrl
        )
        adapter = SeriesAdapter(
            onItemClick = { series ->
                if (series.seasonId > 0) {
                    openInHostContainer(SeriesDetailFragment.newInstance(series.seasonId))
                }
            },
            enableSidebarExit = false,
            onTopEdgeUp = ::focusBackButton,
            onRightEdge = ::focusFilterPanel,
            onItemFocused = {
                lastFocusedArea = FocusArea.CONTENT
                AppLog.d(TAG, "content onItemFocused: currentFocus=${describeView(binding.root.findFocus())}")
                setFilterExpanded(false, animate = false)
            }
        )
        filterAdapter = AllSeriesFilterAdapter(
            onItemClick = ::showFilterOptions,
            onItemFocused = {
                lastFocusedArea = FocusArea.FILTER
                AppLog.d(TAG, "filter onItemFocused: currentFocus=${describeView(binding.root.findFocus())}")
                setFilterExpanded(true, animate = false)
            },
            onTopEdgeUp = ::focusBackButton,
            onLeftEdge = ::focusContentGrid
        )

        binding.textTopTitle.text = entryTitle.ifBlank {
            getString(
                R.string.all_series_title_format,
                SeriesType.titleOf(seasonType)
            )
        }
        binding.buttonBack1.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonBack1.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedArea = FocusArea.BACK
                AppLog.d(TAG, "back focused: currentFocus=${describeView(binding.root.findFocus())}")
            }
        }
        binding.buttonBack1.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                AppLog.d(TAG, "back key DOWN: currentFocus=${describeView(binding.root.findFocus())}")
                focusFilterPanel() || focusContentGrid()
            } else {
                false
            }
        }
        binding.viewFilter.visibility = View.VISIBLE

        gridLayoutManager = WrapContentGridLayoutManager(requireContext(), MAX_CONTENT_SPAN_COUNT)
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    lastTouchInteractionAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> Unit
            }
            false
        }
        binding.recyclerView.addItemDecoration(
            GridSpacingItemDecoration(
                MAX_CONTENT_SPAN_COUNT,
                resources.getDimensionPixelSize(R.dimen.px20),
                true
            )
        )
        binding.recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if ((right - left) != (oldRight - oldLeft)) {
                updateContentGridMetrics()
            }
        }
        binding.recyclerViewFilter.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewFilter.adapter = filterAdapter
        binding.recyclerViewFilter.itemAnimator = null
        filterAdapter.setData(filters)
        filterAdapter.setExpanded(false)
        setFilterExpanded(false, animate = false)
        binding.recyclerView.post { updateContentGridMetrics(force = true) }
        if (cachedList.isNotEmpty()) {
            adapter.setData(cachedList)
            binding.recyclerView.visibility = View.VISIBLE
            binding.viewInfo.visibility = View.GONE
            restoreListState()
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                isTouchScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                val preloadThreshold = layoutManager.spanCount * 2
                if (!isLoading && hasMore && lastVisibleItem >= totalItemCount - preloadThreshold) {
                    currentPage++
                    loadData()
                }
            }
        })
    }

    override fun initData() {
        if (cachedList.isNotEmpty()) {
            binding.recyclerView.visibility = View.VISIBLE
            binding.viewInfo.visibility = View.GONE
            restoreListState()
            return
        }
        loadData()
    }

    override fun onResume() {
        super.onResume()
        binding.recyclerView.post {
            if (!isAdded || view == null || shouldSuppressAutoFocus()) {
                return@post
            }
            restoreFocus()
        }
    }

    private fun loadData() {
        if (isLoading) return

        isLoading = true
        if (currentPage == 1) {
            binding.viewInfo.visibility = View.GONE
        }

        lifecycleScope.launch {
            repository.getAllSeries(seasonType, currentPage, filters)
                .onSuccess { page ->
                    isLoading = false
                    hasMore = page.hasMore
                    if (currentPage == 1) {
                        adapter.setData(page.list)
                        cachedList = page.list
                    } else {
                        adapter.addData(page.list)
                        cachedList = cachedList + page.list
                    }
                    if (currentPage == 1 && page.list.isEmpty()) {
                        showInfo(R.drawable.empty, getString(R.string.empty_data))
                    } else {
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.viewInfo.visibility = View.GONE
                        if (currentPage == 1) {
                            restoreListState()
                            binding.recyclerView.post {
                                if (!shouldSuppressAutoFocus()) {
                                    restoreFocus()
                                }
                            }
                        }
                    }
                }
                .onFailure { error ->
                    isLoading = false
                    if (currentPage > 1) {
                        currentPage--
                        if (cachedList.isNotEmpty()) {
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.viewInfo.visibility = View.GONE
                            return@onFailure
                        }
                    }
                    showInfo(R.drawable.net_error, error.message ?: getString(R.string.net_error))
                }
        }
    }

    private fun showFilterOptions(position: Int) {
        val filter = filters.getOrNull(position) ?: return
        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_setting_choice)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<android.widget.TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = filter.title

        val optionAdapter = SettingSelectionDialogAdapter(
            options = filter.options.map { it.title },
            selectedIndex = filter.currentSelect
        ) { selectedIndex ->
            if (selectedIndex != filter.currentSelect) {
                filters = filters.toMutableList().apply {
                    this[position] = filter.copy(currentSelect = selectedIndex)
                }
                filterAdapter.setData(filters)
                filterAdapter.setExpanded(isFilterExpanded)
                currentPage = 1
                hasMore = true
                loadData()
            }
            dialog.dismiss()
        }

        recyclerView?.layoutManager = createDialogLayoutManager()
        recyclerView?.adapter = optionAdapter
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
                optionAdapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.show()
    }

    private fun showInfo(imageRes: Int, message: String) {
        binding.recyclerView.visibility = View.GONE
        binding.viewInfo.visibility = View.VISIBLE
        if (imageRes != 0) {
            binding.imageInfo.visibility = View.VISIBLE
            binding.imageInfo.setImageResource(imageRes)
        } else {
            binding.imageInfo.setImageDrawable(null)
            binding.imageInfo.visibility = View.GONE
        }
        binding.textInfo.text = message
    }

    private fun restoreFocus(): Boolean {
        if (!isAdded || view == null || shouldSuppressAutoFocus()) {
            return false
        }
        val handled = when (lastFocusedArea) {
            FocusArea.CONTENT -> focusContentGrid() || focusFilterPanel() || focusBackButton()
            FocusArea.FILTER -> focusFilterPanel() || focusContentGrid() || focusBackButton()
            FocusArea.BACK -> focusBackButton() || focusContentGrid() || focusFilterPanel()
        }
        AppLog.d(
            TAG,
            "restoreFocus: area=$lastFocusedArea handled=$handled currentFocus=${describeView(binding.root.findFocus())}"
        )
        return handled
    }

    private fun focusPrimaryContent(): Boolean {
        return focusFilterPanel() || focusContentGrid()
    }

    private fun focusBackButton(): Boolean {
        val handled = binding.buttonBack1.requestFocus()
        AppLog.d(TAG, "focusBackButton: handled=$handled currentFocus=${describeView(binding.root.findFocus())}")
        return handled
    }

    private fun focusFilterPanel(): Boolean {
        lastFocusedArea = FocusArea.FILTER
        AppLog.d(
            TAG,
            "focusFilterPanel start: expanded=$isFilterExpanded currentFocus=${describeView(binding.root.findFocus())}"
        )
        setFilterExpanded(true, animate = false)
        val restoredNow = filterAdapter.requestSavedItemFocus(binding.recyclerViewFilter)
        AppLog.d(TAG, "focusFilterPanel immediate requestSavedItemFocus: handled=$restoredNow")
        if (restoredNow) {
            return true
        }
        val firstHandledNow = requestFirstFilterFocus()
        AppLog.d(TAG, "focusFilterPanel immediate requestFirstFilterFocus: handled=$firstHandledNow")
        if (firstHandledNow) {
            return true
        }
        binding.recyclerViewFilter.post {
            if (!isAdded || view == null) {
                return@post
            }
            AppLog.d(
                TAG,
                "focusFilterPanel post: currentFocus=${describeView(binding.root.findFocus())}"
            )
            val restored = filterAdapter.requestSavedItemFocus(binding.recyclerViewFilter)
            AppLog.d(TAG, "focusFilterPanel requestSavedItemFocus: handled=$restored")
            if (restored) {
                return@post
            }
            val firstHandled = requestFirstFilterFocus()
            AppLog.d(TAG, "focusFilterPanel requestFirstFilterFocus: handled=$firstHandled")
        }
        return true
    }

    private fun requestFirstFilterFocus(): Boolean {
        val handled = filterAdapter.requestItemFocus(binding.recyclerViewFilter, 0)
        AppLog.d(TAG, "requestFirstFilterFocus: handled=$handled")
        return handled
    }

    private fun focusContentGrid(): Boolean {
        if (shouldSuppressAutoFocus()) {
            return false
        }
        lastFocusedArea = FocusArea.CONTENT
        setFilterExpanded(false, animate = false)
        val handled = adapter.requestFocusedView() || requestFirstContentCardFocus()
        AppLog.d(TAG, "focusContentGrid: handled=$handled currentFocus=${describeView(binding.root.findFocus())}")
        return handled
    }

    private fun requestFirstContentCardFocus(): Boolean {
        if (shouldSuppressAutoFocus()) {
            return false
        }
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
        if (holder?.itemView?.requestFocus() == true) {
            return true
        }
        val result = RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = binding.recyclerView,
            position = 0
        )
        AppLog.d(TAG, "requestFirstContentCardFocus deferred: handled=${result.handled} deferred=${result.deferred}")
        return true
    }

    override fun onDestroyView() {
        listLayoutState = binding.recyclerView.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
    }

    private fun restoreListState() {
        val state = listLayoutState ?: return
        binding.recyclerView.post {
            binding.recyclerView.layoutManager?.onRestoreInstanceState(state)
        }
    }

    private fun setFilterExpanded(expanded: Boolean, animate: Boolean = true) {
        val targetWidth = resources.getDimensionPixelSize(
            if (expanded) R.dimen.px300 else R.dimen.px85
        )
        val currentWidth = binding.viewFilter.layoutParams.width.takeIf { it > 0 } ?: targetWidth
        if (isFilterExpanded == expanded && currentWidth == targetWidth) {
            AppLog.d(
                TAG,
                "setFilterExpanded skip: expanded=$expanded animate=$animate currentFocus=${describeView(binding.root.findFocus())}"
            )
            return
        }
        isFilterExpanded = expanded
        AppLog.d(
            TAG,
            "setFilterExpanded: expanded=$expanded animate=$animate currentFocus=${describeView(binding.root.findFocus())}"
        )
        filterAdapter.setExpanded(expanded)
        binding.viewFilter.animate().cancel()
        if (animate) {
            ValueAnimator.ofInt(currentWidth, targetWidth).apply {
                duration = 220L
                addUpdateListener { animator ->
                    val width = animator.animatedValue as Int
                    binding.viewFilter.layoutParams = binding.viewFilter.layoutParams.apply {
                        this.width = width
                    }
                    updateContentGridMetrics()
                }
                start()
            }
        } else {
            binding.viewFilter.layoutParams = binding.viewFilter.layoutParams.apply {
                width = targetWidth
            }
            updateContentGridMetrics(force = true)
        }
    }

    private fun updateContentGridMetrics(force: Boolean = false) {
        if (!::gridLayoutManager.isInitialized) {
            return
        }
        val availableWidth = binding.recyclerView.width
            .minus(binding.recyclerView.paddingStart)
            .minus(binding.recyclerView.paddingEnd)
        if (availableWidth <= 0) {
            return
        }
        val spanCount = ((availableWidth + BASELINE_CARD_SPACING_PX) /
            (BASELINE_CARD_WIDTH_PX + BASELINE_CARD_SPACING_PX))
            .coerceIn(MIN_CONTENT_SPAN_COUNT, MAX_CONTENT_SPAN_COUNT)
        if (force || gridLayoutManager.spanCount != spanCount) {
            AppLog.d(
                TAG,
                "updateContentGridMetrics: width=$availableWidth spanCount=${gridLayoutManager.spanCount}->$spanCount"
            )
            gridLayoutManager.spanCount = spanCount
        }
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

    private fun shouldSuppressAutoFocus(): Boolean {
        return isTouchScrolling || (SystemClock.uptimeMillis() - lastTouchInteractionAt) < 600L
    }

    private fun createDialogLayoutManager(): LinearLayoutManager {
        val extraLayoutSpacePx = resources.getDimensionPixelSize(R.dimen.px100)
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

    override fun onBackPressed(): Boolean {
        if (!isFilterExpanded || !isAdded || view == null) {
            return false
        }
        lastFocusedArea = FocusArea.CONTENT
        setFilterExpanded(false)
        binding.recyclerView.post {
            if (isAdded && view != null) {
                focusContentGrid()
            }
        }
        return true
    }

}
