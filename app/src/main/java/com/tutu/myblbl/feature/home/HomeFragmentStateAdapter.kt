package com.tutu.myblbl.feature.home

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.tutu.myblbl.MyBLBLApplication
import com.tutu.myblbl.R

class HomeFragmentStateAdapter(
    private val fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    private val titles = listOf(
        R.string.recommend,
        R.string.hot,
        R.string.animation,
        R.string.film_and_television
    )

    override fun getItemCount(): Int = titles.size

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> RecommendListFragment.newInstance()
            1 -> HotListFragment.newInstance()
            2 -> HomeLaneFragment.newInstance(HomeLaneFragment.TYPE_ANIMATION)
            else -> HomeLaneFragment.newInstance(HomeLaneFragment.TYPE_CINEMA)
        }
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun containsItem(itemId: Long): Boolean = itemId in 0 until titles.size

    fun getCurrentFragment(position: Int): Fragment? {
        if (position < 0 || position >= titles.size) return null
        val fragmentTag = "f${getItemId(position)}"
        return fragmentManager.findFragmentByTag(fragmentTag)
    }

    fun getPageTitle(position: Int): CharSequence? {
        return titles.getOrNull(position)?.takeIf { it != 0 }?.let {
            MyBLBLApplication.instance.getString(it)
        }
    }
}
