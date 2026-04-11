package com.tutu.myblbl.feature.me

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.tutu.myblbl.R
import com.tutu.myblbl.ui.fragment.favorite.FavoriteFragment

class MeFragmentAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    private val fragmentManagerRef = fragmentManager
    private val tabs = listOf(
        R.string.history,
        R.string.collection,
        R.string.following_animation,
        R.string.following_series,
        R.string.later_watch
    )

    override fun getItemCount(): Int = tabs.size

    override fun getItemId(position: Int): Long = position.toLong()

    override fun containsItem(itemId: Long): Boolean = itemId in 0 until tabs.size

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MeListFragment.newInstance(MeListFragment.TYPE_HISTORY)
            1 -> FavoriteFragment.newEmbeddedInstance()
            2 -> MeSeriesFragment.newInstance(MeSeriesFragment.TYPE_ANIMATION)
            3 -> MeSeriesFragment.newInstance(MeSeriesFragment.TYPE_SERIES)
            4 -> MeListFragment.newInstance(MeListFragment.TYPE_LATER)
            else -> MeListFragment.newInstance(MeListFragment.TYPE_HISTORY)
        }
    }

    fun getPageTitle(position: Int): Int? {
        return tabs.getOrNull(position)
    }

    fun getFragment(position: Int): Fragment? {
        val tag = "f${getItemId(position)}"
        return fragmentManagerRef.findFragmentByTag(tag)
    }

    fun getFragments(): Collection<Fragment> = fragmentManagerRef.fragments.filter { it is MeTabPage }
}
