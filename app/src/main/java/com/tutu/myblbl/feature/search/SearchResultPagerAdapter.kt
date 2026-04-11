package com.tutu.myblbl.feature.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.PageSearchResultBinding
import com.tutu.myblbl.model.search.SearchCategoryItem
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration

class SearchResultPagerAdapter(
    private val onItemClick: (SearchResultEntry) -> Unit,
    private val onLoadMore: (SearchType) -> Unit,
    private val onTopEdgeUp: ((View) -> Boolean)? = null
) : RecyclerView.Adapter<SearchResultPagerAdapter.ViewHolder>() {

    private val pages = mutableListOf<SearchResultPage>()
    private val holders = mutableMapOf<SearchType, ViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = PageSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = pages[position]
        holders[page.type] = holder
        holder.bind(page)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holders.entries.removeAll { it.value == holder }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size

    fun getPageTitle(position: Int): String = pages[position].title

    fun getPageType(position: Int): SearchType? = pages.getOrNull(position)?.type

    fun setPages(categories: List<SearchCategoryItem>) {
        val existing = pages.associateBy { it.type }
        val newPages = categories.map { category ->
            SearchResultPage(
                type = category.type,
                title = category.showText,
                items = existing[category.type]?.items ?: mutableListOf()
            )
        }
        val diffResult = DiffUtil.calculateDiff(SearchResultPageDiff(pages, newPages))
        pages.clear()
        pages.addAll(newPages)
        holders.clear()
        diffResult.dispatchUpdatesTo(this)
    }

    fun clearResults() {
        pages.forEach { page ->
            page.items.clear()
            page.loading = false
        }
        holders.values.forEach { holder ->
            holder.submitEmpty()
        }
        notifyItemRangeChanged(0, pages.size)
    }

    fun submitResults(type: SearchType, items: List<SearchItemModel>) {
        val page = pages.firstOrNull { it.type == type } ?: return
        page.items.clear()
        page.items.addAll(items)
        holders[type]?.submit(page) ?: notifyItemChanged(pages.indexOf(page))
    }

    fun submitState(type: SearchType, items: List<SearchItemModel>, loading: Boolean, hasMore: Boolean) {
        val page = pages.firstOrNull { it.type == type } ?: return
        page.items.clear()
        page.items.addAll(items)
        page.loading = loading
        page.hasMore = hasMore
        holders[type]?.submit(page) ?: notifyItemChanged(pages.indexOf(page))
    }

    fun scrollToTop(position: Int) {
        val type = getPageType(position) ?: return
        holders[type]?.scrollToTop()
    }

    fun focusPrimaryContent(type: SearchType?, anchorView: View? = null): Boolean {
        return holders[type]?.focusPrimaryContent(anchorView) == true
    }

    inner class ViewHolder(
        private val binding: PageSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentType: SearchType? = null
        private var currentAdapter: SearchItemAdapter? = null
        private var currentPage: SearchResultPage? = null

        fun bind(page: SearchResultPage) {
            currentPage = page
            if (currentType != page.type || currentAdapter == null) {
                currentType = page.type
                currentAdapter = SearchItemAdapter(page.type, onItemClick, onTopEdgeUp)
                val spanCount = when (page.type) {
                    SearchType.Video,
                    SearchType.LiveRoom -> 4

                    SearchType.Animation,
                    SearchType.FilmAndTv,
                    SearchType.User -> 6
                }
                binding.recyclerViewResult.layoutManager =
                    WrapContentGridLayoutManager(binding.root.context, spanCount)
                binding.recyclerViewResult.adapter = currentAdapter
                while (binding.recyclerViewResult.itemDecorationCount > 0) {
                    binding.recyclerViewResult.removeItemDecorationAt(0)
                }
                binding.recyclerViewResult.addItemDecoration(
                    GridSpacingItemDecoration(
                        spanCount,
                        binding.root.resources.getDimensionPixelSize(R.dimen.px20),
                        true
                    )
                )
                binding.recyclerViewResult.clearOnScrollListeners()
                binding.recyclerViewResult.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        super.onScrolled(recyclerView, dx, dy)
                        val layoutManager =
                            recyclerView.layoutManager as? GridLayoutManager ?: return
                        val totalItemCount = layoutManager.itemCount
                        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                        val pageState = currentPage ?: return
                        if (
                            totalItemCount > 0 &&
                            pageState.hasMore &&
                            !pageState.loading &&
                            lastVisibleItem >= totalItemCount - 12
                        ) {
                            pageState.loading = true
                            recyclerView.post {
                                currentType?.let(onLoadMore)
                            }
                        }
                    }
                })
            }
            submit(page)
        }

        fun submit(page: SearchResultPage) {
            val filteredItems = ContentFilter.filterSearchItems(binding.root.context, page.items)
            val applyUiState = {
                currentAdapter?.setItems(filteredItems)
                binding.recyclerViewResult.isVisible = filteredItems.isNotEmpty()
                binding.textEmpty.isVisible = !page.loading && filteredItems.isEmpty()
                binding.textEmpty.setText(R.string.search_empty)
            }
            if (binding.recyclerViewResult.isComputingLayout) {
                binding.recyclerViewResult.post(applyUiState)
            } else {
                applyUiState()
            }
        }

        fun submitEmpty() {
            val applyUiState = {
                currentAdapter?.setItems(emptyList())
                binding.recyclerViewResult.isVisible = false
                binding.textEmpty.isVisible = false
            }
            if (binding.recyclerViewResult.isComputingLayout) {
                binding.recyclerViewResult.post(applyUiState)
            } else {
                applyUiState()
            }
        }

        fun scrollToTop() {
            binding.recyclerViewResult.smoothScrollToPosition(0)
        }

        fun focusPrimaryContent(anchorView: View? = null): Boolean {
            if (currentAdapter?.itemCount.orZero() <= 0) {
                return false
            }
            return TabContentFocusHelper.requestSpatialOrPrimary(
                anchorView = anchorView,
                root = binding.recyclerViewResult,
                direction = View.FOCUS_DOWN
            ) {
                TabContentFocusHelper.requestRecyclerPrimaryFocus(
                    recyclerView = binding.recyclerViewResult,
                    itemCount = currentAdapter?.itemCount.orZero()
                ).resolved
            }
        }
    }

    data class SearchResultPage(
        val type: SearchType,
        val title: String,
        val items: MutableList<SearchItemModel> = mutableListOf(),
        var loading: Boolean = false,
        var hasMore: Boolean = true
    )

    private fun Int?.orZero(): Int = this ?: 0

    private class SearchResultPageDiff(
        private val oldList: List<SearchResultPage>,
        private val newList: List<SearchResultPage>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].type == newList[newItemPosition].type
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
