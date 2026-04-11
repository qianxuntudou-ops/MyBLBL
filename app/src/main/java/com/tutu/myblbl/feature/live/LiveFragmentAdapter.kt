package com.tutu.myblbl.feature.live

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.tutu.myblbl.model.live.LiveAreaCategoryParent
import com.tutu.myblbl.utils.AppLog

class LiveFragmentAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    private val categories = mutableListOf<LiveAreaCategoryParent>()
    private val fragments = mutableMapOf<Int, LiveTabPage>()

    fun setCategories(list: List<LiveAreaCategoryParent>) {
        AppLog.e(
            "[BLBL_DIAG]",
            "Adapter.setCategories: list.size=${list.size}, old size=${categories.size}"
        )
        val diffResult = DiffUtil.calculateDiff(LiveFragmentDiff(categories, list))
        categories.clear()
        categories.addAll(list)
        fragments.clear()
        diffResult.dispatchUpdatesTo(this)
        AppLog.e("[BLBL_DIAG]", "Adapter.setCategories done: itemCount=${categories.size}")
    }

    override fun getItemCount(): Int = categories.size

    override fun getItemId(position: Int): Long {
        val category = categories[position]
        return if (position == 0) Long.MIN_VALUE else category.id
    }

    override fun containsItem(itemId: Long): Boolean {
        return categories.anyIndexed { index, category ->
            if (index == 0) {
                itemId == Long.MIN_VALUE
            } else {
                category.id == itemId
            }
        }
    }

    override fun createFragment(position: Int): Fragment {
        val category = categories[position]
        AppLog.e("[BLBL_DIAG]", "Adapter.createFragment: pos=$position, name=${category.name}, areaList=${category.areaList?.size ?: "null"}")
        val fragment: Fragment = if (position == 0) {
            LiveRecommendFragment.newInstance()
        } else {
            LiveAreaFragment.newInstance(category)
        }
        if (fragment is LiveTabPage) {
            fragments[position] = fragment
        }
        return fragment
    }

    fun getPageTitle(position: Int): CharSequence? {
        return if (position < categories.size) categories[position].name else null
    }

    fun getCurrentFragment(position: Int): LiveTabPage? = fragments[position]

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        forEachIndexed { index, item ->
            if (predicate(index, item)) {
                return true
            }
        }
        return false
    }

    private class LiveFragmentDiff(
        private val oldList: List<LiveAreaCategoryParent>,
        private val newList: List<LiveAreaCategoryParent>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
