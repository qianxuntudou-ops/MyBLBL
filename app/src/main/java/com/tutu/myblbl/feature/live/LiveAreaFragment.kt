package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.tutu.myblbl.databinding.FragmentLiveBaseListBinding
import com.tutu.myblbl.model.live.LiveAreaCategory
import com.tutu.myblbl.model.live.LiveAreaCategoryParent
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.common.ext.serializableCompat
import androidx.recyclerview.widget.RecyclerView

class LiveAreaFragment : BaseFragment<FragmentLiveBaseListBinding>(), LiveTabPage {

    companion object {
        private const val TAG = "MainEntryFocus"
        private const val ARG_CATEGORY = "category"

        fun newInstance(category: LiveAreaCategoryParent): LiveAreaFragment {
            return LiveAreaFragment().apply {
                arguments = bundleOf(ARG_CATEGORY to category)
            }
        }
    }

    private lateinit var adapter: LiveAreaAdapter
    private var category: LiveAreaCategoryParent? = null

    override fun initArguments() {
        category = arguments?.serializableCompat(ARG_CATEGORY)
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBaseListBinding {
        return FragmentLiveBaseListBinding.inflate(inflater, container ?: android.widget.FrameLayout(inflater.context))
    }

    override fun initView() {
        adapter = LiveAreaAdapter(
            onItemClick = ::openAreaDetail,
            onTopEdgeUp = ::focusTopTab
        )
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(requireContext(), 8)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
    }

    override fun initData() {
        val areaList = category?.areaList.orEmpty()
        adapter.setData(areaList)
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null || adapter.itemCount == 0) {
            AppLog.d(TAG, "LiveAreaFragment.focusPrimaryContent failed: itemCount=${adapter.itemCount}")
            return false
        }
        val lm = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return false
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            binding.recyclerView.findViewHolderForAdapterPosition(firstVisible)?.itemView?.let {
                val handled = it.requestFocus()
                AppLog.d(TAG, "LiveAreaFragment.focusPrimaryContent firstVisible=$firstVisible handled=$handled")
                return handled
            }
        }
        val result = RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = binding.recyclerView,
            position = 0
        )
        AppLog.d(
            TAG,
            "LiveAreaFragment.focusPrimaryContent deferred: handled=${result.handled} deferred=${result.deferred}"
        )
        return true
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerView,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            AppLog.d(TAG, "LiveAreaFragment.focusPrimaryContent spatialEntry: handled=$handled")
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    private fun openAreaDetail(area: LiveAreaCategory) {
        val areaId = area.areaV2Id.takeIf { it > 0 } ?: area.id
        val parentAreaId = area.areaV2ParentId.takeIf { it > 0 } ?: area.parentId
        val title = area.title.ifBlank { area.name }
        openInHostContainer(
            LiveListFragment.newAreaInstance(
                areaId = areaId,
                parentAreaId = parentAreaId,
                title = title
            )
        )
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? LiveFragment)?.focusCurrentTab() == true
    }
}
