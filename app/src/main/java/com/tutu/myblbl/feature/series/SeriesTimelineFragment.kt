package com.tutu.myblbl.feature.series

import android.os.Parcelable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentSeriesTimelineBinding
import com.tutu.myblbl.model.series.SeriesType
import com.tutu.myblbl.model.series.timeline.SeriesTimeLineModel
import com.tutu.myblbl.model.series.timeline.TimeLineADayModel
import com.tutu.myblbl.repository.SeriesRepository
import com.tutu.myblbl.ui.adapter.SeriesTimelineAdapter
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import org.koin.android.ext.android.inject

class SeriesTimelineFragment : BaseFragment<FragmentSeriesTimelineBinding>() {

    companion object {
        private const val ARG_SEASON_TYPE = "season_type"

        fun newInstance(seasonType: Int = SeriesType.ANIME): SeriesTimelineFragment {
            return SeriesTimelineFragment().apply {
                arguments = bundleOf(ARG_SEASON_TYPE to seasonType)
            }
        }
    }

    private enum class FocusArea {
        BACK,
        FILTER,
        CONTENT
    }

    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val repository: SeriesRepository by inject()

    private var seasonType: Int = SeriesType.ANIME
    private var timelineDays: List<TimeLineADayModel> = emptyList()
    private var listLayoutState: Parcelable? = null
    private lateinit var adapter: SeriesTimelineAdapter
    private var lastFocusedArea = FocusArea.CONTENT

    override fun initArguments() {
        seasonType = arguments?.getInt(ARG_SEASON_TYPE, SeriesType.ANIME) ?: SeriesType.ANIME
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentSeriesTimelineBinding {
        return FragmentSeriesTimelineBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        adapter = SeriesTimelineAdapter(
            onItemClick = { item ->
                if (item.seasonId > 0 || item.episodeId > 0) {
                    openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = item.seasonId,
                            epId = item.episodeId
                        )
                    )
                }
            },
            onItemFocused = {
                lastFocusedArea = FocusArea.CONTENT
            },
            enableSidebarExit = false,
            onTopEdgeUp = ::focusSelectedFilter
        )

        binding.textTopTitle.text = if (seasonType == SeriesType.ANIME) {
            getString(R.string.new_series_update)
        } else {
            "${SeriesType.titleOf(seasonType)} · 时间线"
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
                focusSelectedFilter()
            } else {
                false
            }
        }
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(requireContext(), 6)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addItemDecoration(
            GridSpacingItemDecoration(
                6,
                resources.getDimensionPixelSize(R.dimen.px20),
                true
            )
        )
        binding.recyclerView.setHasFixedSize(true)
        binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.button_radio_0 -> showTimeline(0)
                R.id.button_radio_1 -> showTimeline(1)
                R.id.button_radio_2 -> showTimeline(2)
                R.id.button_radio_3 -> showTimeline(3)
                R.id.button_radio_4 -> showTimeline(4)
                R.id.button_radio_5 -> showTimeline(5)
                R.id.button_radio_6 -> showTimeline(6)
                R.id.button_radio_7 -> showTimeline(7)
            }
        }
        filterButtons().forEach { button ->
            button.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    lastFocusedArea = FocusArea.FILTER
                }
            }
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DPAD_UP
                ) {
                    focusBackButton()
                } else {
                    false
                }
            }
        }
        if (timelineDays.isNotEmpty()) {
            showTimeline(resolveSelectedDayIndex())
            restoreListState()
        }
    }

    override fun initData() {
        if (timelineDays.isNotEmpty()) {
            showTimeline(resolveSelectedDayIndex())
            restoreListState()
            return
        }
        loadTimeline()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 0) {
                                loadTimeline()
                            }

                        is MainNavigationViewModel.Event.SecondaryTabReselected -> {
                            val matchesHomeTab = event.host == MainNavigationViewModel.SecondaryTabHost.HOME &&
                                (
                                    (event.position == 2 && seasonType == SeriesType.ANIME) ||
                                        (event.position == 3 && seasonType == SeriesType.MOVIE)
                                    )
                            if (matchesHomeTab) {
                                loadTimeline()
                            }
                        }

                        MainNavigationViewModel.Event.BackPressed -> {
                            binding.recyclerView.scrollToPosition(0)
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    private fun loadTimeline() {
        binding.progressBar.visibility = View.VISIBLE
        binding.textInfo.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getSeriesTimeline(seasonType)
                .onSuccess { days ->
                    binding.progressBar.visibility = View.GONE
                    timelineDays = days
                    ensureSelectedDay()
                    showTimeline(resolveSelectedDayIndex())
                    restoreListState()
                    binding.recyclerView.post { restoreFocus() }
                }
                .onFailure { error ->
                    binding.progressBar.visibility = View.GONE
                    showEmpty(error.message ?: getString(R.string.net_error))
                }
        }
    }

    private fun showTimeline(targetDayOfWeek: Int) {
        if (timelineDays.isEmpty()) {
            showEmpty(getString(R.string.empty_data))
            return
        }

        val todayIndex = timelineDays.indexOfFirst { it.isToday == 1 }
            .takeIf { it >= 0 }
            ?: 0

        if (targetDayOfWeek == 0) {
            val recentEpisodes = timelineDays
                .flatMap { it.episodes }
                .filter { it.published == 1 }
                .sortedByDescending { it.pubTs }
            updateEpisodes(recentEpisodes)
            return
        }

        if (timelineDays[todayIndex].dayOfWeek > targetDayOfWeek) {
            for (index in todayIndex downTo 0) {
                if (timelineDays[index].dayOfWeek == targetDayOfWeek) {
                    updateEpisodes(timelineDays[index].episodes)
                    return
                }
            }
        } else {
            for (index in todayIndex until timelineDays.size) {
                if (timelineDays[index].dayOfWeek == targetDayOfWeek) {
                    updateEpisodes(timelineDays[index].episodes)
                    return
                }
            }
        }
        showEmpty(getString(R.string.empty_data))
    }

    private fun updateEpisodes(episodes: List<SeriesTimeLineModel>) {
        if (episodes.isEmpty()) {
            showEmpty(getString(R.string.empty_data))
            return
        }
        binding.imageEmpty.visibility = View.GONE
        binding.textInfo.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        adapter.setData(episodes)
    }

    private fun showEmpty(message: String) {
        adapter.setData(emptyList())
        binding.recyclerView.visibility = View.GONE
        binding.imageEmpty.visibility = View.VISIBLE
        binding.textInfo.visibility = View.VISIBLE
        binding.textInfo.text = message
    }

    private fun restoreFocus(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        return when (lastFocusedArea) {
            FocusArea.CONTENT -> focusContentGrid() || focusSelectedFilter() || focusBackButton()
            FocusArea.FILTER -> focusSelectedFilter() || focusContentGrid() || focusBackButton()
            FocusArea.BACK -> focusBackButton() || focusSelectedFilter() || focusContentGrid()
        }
    }

    private fun focusBackButton(): Boolean {
        return binding.buttonBack1.requestFocus()
    }

    private fun focusSelectedFilter(): Boolean {
        val target = filterButtons().firstOrNull { it.id == binding.radioGroup.checkedRadioButtonId }
            ?: binding.buttonRadio0
        return target.requestFocus()
    }

    private fun focusContentGrid(): Boolean {
        if (binding.recyclerView.visibility != View.VISIBLE) {
            return false
        }
        return adapter.requestStoredItemFocus(binding.recyclerView) || requestFirstTimelineCardFocus()
    }

    private fun requestFirstTimelineCardFocus(): Boolean {
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

    private fun filterButtons(): List<View> {
        return listOf(
            binding.buttonRadio0,
            binding.buttonRadio1,
            binding.buttonRadio2,
            binding.buttonRadio3,
            binding.buttonRadio4,
            binding.buttonRadio5,
            binding.buttonRadio6,
            binding.buttonRadio7
        )
    }

    override fun onDestroyView() {
        listLayoutState = binding.recyclerView.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        binding.recyclerView.post {
            if (!isAdded || view == null) {
                return@post
            }
            restoreFocus()
        }
    }

    private fun restoreListState() {
        val state = listLayoutState ?: return
        binding.recyclerView.post {
            binding.recyclerView.layoutManager?.onRestoreInstanceState(state)
        }
    }

    private fun ensureSelectedDay() {
        val checkedId = binding.radioGroup.checkedRadioButtonId
        if (checkedId == View.NO_ID) {
            binding.buttonRadio0.isChecked = true
        }
    }

    private fun resolveSelectedDayIndex(): Int {
        return when (binding.radioGroup.checkedRadioButtonId) {
            R.id.button_radio_1 -> 1
            R.id.button_radio_2 -> 2
            R.id.button_radio_3 -> 3
            R.id.button_radio_4 -> 4
            R.id.button_radio_5 -> 5
            R.id.button_radio_6 -> 6
            R.id.button_radio_7 -> 7
            else -> 0
        }
    }
}
