package com.tutu.myblbl.feature.series

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
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.base.OnBackPressedHandler
import com.tutu.myblbl.core.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.core.ui.decoration.LinearSpacingItemDecoration
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AllSeriesFragment : BaseFragment<FragmentAllSeriesBinding>(), OnBackPressedHandler {

    private enum class FocusArea {
        BACK,
        FILTER,
        CONTENT
    }

    companion object {
        private const val ARG_SEASON_TYPE = "seasonType"
        private const val ARG_MORE_URL = "moreUrl"
        private const val ARG_ENTRY_TITLE = "entryTitle"
        private const val MIN_CONTENT_SPAN_COUNT = 2
        private const val MAX_CONTENT_SPAN_COUNT = 6
        private const val BASELINE_CARD_WIDTH_PX = 180
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
    private lateinit var gridSpacingDecoration: GridSpacingItemDecoration
    private var filters: List<AllSeriesFilterModel> = emptyList()
    private var lastFocusedArea = FocusArea.CONTENT
    private var originalSpacing = 0

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
                setFilterExpanded(false, animate = false)
            }
        )
        filterAdapter = AllSeriesFilterAdapter(
            onItemClick = ::showFilterOptions,
            onItemFocused = {
                lastFocusedArea = FocusArea.FILTER
                setFilterExpanded(true, animate = false)
            },
            onTopEdgeUp = ::focusBackButton,
            onLeftEdge = { focusContentGrid(it) },
            onRightEdge = { focusContentGrid(it) }
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
            }
        }
        binding.buttonBack1.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusContentGrid() || focusFilterPanel()
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
        originalSpacing = resources.getDimensionPixelSize(R.dimen.px20)
        gridSpacingDecoration = GridSpacingItemDecoration(
            MAX_CONTENT_SPAN_COUNT,
            originalSpacing,
            true
        )
        binding.recyclerView.addItemDecoration(gridSpacingDecoration)
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

        viewLifecycleOwner.lifecycleScope.launch {
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
            selectedIndex = filter.currentSelect,
            sortDirection = if (filter.key == "order") filter.sortDirection else -1
        ) { selectedIndex ->
            if (selectedIndex == filter.currentSelect && filter.key == "order") {
                filters = filters.toMutableList().apply {
                    this[position] = filter.copy(
                        sortDirection = if (filter.sortDirection == 0) 1 else 0
                    )
                }
            } else if (selectedIndex != filter.currentSelect) {
                filters = filters.toMutableList().apply {
                    this[position] = filter.copy(
                        currentSelect = selectedIndex,
                        sortDirection = if (filter.key == "order") 0 else filter.sortDirection
                    )
                }
            }
            filterAdapter.setData(filters)
            filterAdapter.setExpanded(isFilterExpanded)
            currentPage = 1
            hasMore = true
            loadData()
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
        return handled
    }

    private fun focusPrimaryContent(): Boolean {
        return focusFilterPanel() || focusContentGrid()
    }

    private fun focusBackButton(): Boolean {
        val handled = binding.buttonBack1.requestFocus()
        return handled
    }

    private fun focusFilterPanel(): Boolean {
        lastFocusedArea = FocusArea.FILTER
        setFilterExpanded(true, animate = false)
        val restoredNow = filterAdapter.requestSavedItemFocus(binding.recyclerViewFilter)
        if (restoredNow) {
            return true
        }
        val firstHandledNow = requestFirstFilterFocus()
        if (firstHandledNow) {
            return true
        }
        binding.recyclerViewFilter.post {
            if (!isAdded || view == null) {
                return@post
            }
            val restored = filterAdapter.requestSavedItemFocus(binding.recyclerViewFilter)
            if (restored) {
                return@post
            }
            val firstHandled = requestFirstFilterFocus()
        }
        return true
    }

    private fun requestFirstFilterFocus(): Boolean {
        val handled = filterAdapter.requestItemFocus(binding.recyclerViewFilter, 0)
        return handled
    }

    private fun focusContentGrid(sourceView: View? = null): Boolean {
        if (shouldSuppressAutoFocus()) {
            return false
        }
        lastFocusedArea = FocusArea.CONTENT
        setFilterExpanded(false, animate = false)
        if (sourceView != null) {
            val anchorCenterY = getCenterYOnScreen(sourceView)
            binding.recyclerView.post {
                if (!isAdded || view == null) return@post
                if (!focusNearestContentCard(anchorCenterY)) {
                    adapter.requestFocusedView() || requestFirstContentCardFocus()
                }
            }
            return true
        }
        val handled = adapter.requestFocusedView() || requestFirstContentCardFocus()
        return handled
    }

    private fun getCenterYOnScreen(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1] + view.height / 2
    }

    private fun focusNearestContentCard(anchorCenterY: Int): Boolean {
        val recyclerView = binding.recyclerView
        var bestChild: View? = null
        var bestDistance = Int.MAX_VALUE
        var bestX = Int.MIN_VALUE

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            if (!child.isFocusable || child.visibility != View.VISIBLE) continue
            val childLocation = IntArray(2)
            child.getLocationOnScreen(childLocation)
            val childCenterY = childLocation[1] + child.height / 2
            val distance = kotlin.math.abs(childCenterY - anchorCenterY)
            if (distance < bestDistance || (distance == bestDistance && childLocation[0] > bestX)) {
                bestDistance = distance
                bestX = childLocation[0]
                bestChild = child
            }
        }
        return bestChild?.requestFocus() == true
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
            if (expanded) R.dimen.px200 else R.dimen.px85
        )
        val currentWidth = binding.viewFilter.layoutParams.width.takeIf { it > 0 } ?: targetWidth
        if (isFilterExpanded == expanded && currentWidth == targetWidth) {
            return
        }
        isFilterExpanded = expanded
        filterAdapter.setExpanded(expanded)
        binding.viewFilter.animate().cancel()
        if (animate) {
            val startSpacing = gridSpacingDecoration.currentHSpacing
            val endSpacing = calculateSpacing(targetWidth)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220L
                addUpdateListener { animator ->
                    val fraction = animator.animatedFraction
                    val width = (currentWidth + (targetWidth - currentWidth) * fraction).toInt()
                    binding.viewFilter.layoutParams = binding.viewFilter.layoutParams.apply {
                        this.width = width
                    }
                    val spacing = (startSpacing + (endSpacing - startSpacing) * fraction).toInt()
                    gridSpacingDecoration.setHSpacing(spacing)
                    binding.recyclerView.invalidateItemDecorations()
                }
                start()
            }
        } else {
            binding.viewFilter.layoutParams = binding.viewFilter.layoutParams.apply {
                width = targetWidth
            }
            gridSpacingDecoration.setHSpacing(calculateSpacing(targetWidth))
            binding.recyclerView.invalidateItemDecorations()
            updateContentGridMetrics(force = true)
        }
    }

    private fun calculateSpacing(filterWidth: Int): Int {
        if (fixedSpanCount <= 0) return originalSpacing
        val recyclerViewWidth = binding.root.width - filterWidth
        val availableWidth = recyclerViewWidth - binding.recyclerView.paddingStart - binding.recyclerView.paddingEnd
        if (availableWidth <= 0) return originalSpacing
        val gapCount = fixedSpanCount + 1
        val collapsedWidth = binding.root.width - resources.getDimensionPixelSize(R.dimen.px85)
        val collapsedAvailable = collapsedWidth - binding.recyclerView.paddingStart - binding.recyclerView.paddingEnd
        val cardWidth = (collapsedAvailable - originalSpacing * gapCount) / fixedSpanCount
        val requiredTotalSpacing = availableWidth - cardWidth * fixedSpanCount
        val newSpacing = (requiredTotalSpacing / gapCount).coerceAtLeast(0)
        return newSpacing
    }

    private var fixedSpanCount = 0

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
        if (fixedSpanCount <= 0) {
            fixedSpanCount = ((availableWidth + BASELINE_CARD_SPACING_PX) /
                (BASELINE_CARD_WIDTH_PX + BASELINE_CARD_SPACING_PX))
                .coerceIn(MIN_CONTENT_SPAN_COUNT, MAX_CONTENT_SPAN_COUNT)
        }
        val spanCount = fixedSpanCount
        if (force || gridLayoutManager.spanCount != spanCount) {
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
