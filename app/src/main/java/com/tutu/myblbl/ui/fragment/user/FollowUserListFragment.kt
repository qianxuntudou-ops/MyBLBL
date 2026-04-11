package com.tutu.myblbl.ui.fragment.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentFollowUserListBinding
import com.tutu.myblbl.model.user.FollowingModel
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.ui.adapter.FollowUserAdapter
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.detail.UserSpaceFragment
import com.tutu.myblbl.ui.view.WrapContentGridLayoutManager
import com.tutu.myblbl.ui.widget.GridSpacingItemDecoration
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.koin.android.ext.android.inject

class FollowUserListFragment : BaseFragment<FragmentFollowUserListBinding>() {

    companion object {
        private const val SPAN_COUNT = 8
        const val TYPE_FOLLOWING = 0
        const val TYPE_FOLLOWER = 1

        private const val ARG_USER_ID = "user_id"
        private const val ARG_TYPE = "type"

        fun newInstance(userId: Long, type: Int): FollowUserListFragment {
            return FollowUserListFragment().apply {
                arguments = bundleOf(
                    ARG_USER_ID to userId,
                    ARG_TYPE to type
                )
            }
        }
    }

    private val userRepository: UserRepository by inject()

    private lateinit var adapter: FollowUserAdapter
    private var userId: Long = 0L
    private var type: Int = TYPE_FOLLOWING
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var totalCount = 0
    private val pageSize = 50
    private var lastFocusedPosition = RecyclerView.NO_POSITION
    private var hasRequestedInitialFocus = false

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentFollowUserListBinding {
        return FragmentFollowUserListBinding.inflate(inflater, container, false)
    }

    override fun initArguments() {
        userId = arguments?.getLong(ARG_USER_ID) ?: 0L
        type = arguments?.getInt(ARG_TYPE, TYPE_FOLLOWING) ?: TYPE_FOLLOWING
    }

    override fun initView() {
        binding.tvTitle.text = getString(
            if (type == TYPE_FOLLOWER) R.string.user_follower else R.string.user_following
        )
        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedPosition = RecyclerView.NO_POSITION
            }
        }

        adapter = FollowUserAdapter(
            onItemClick = ::onUserClick,
            onItemFocused = { position -> lastFocusedPosition = position }
        )
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(requireContext(), SPAN_COUNT)
        binding.recyclerView.adapter = adapter
        if (binding.recyclerView.itemDecorationCount == 0) {
            binding.recyclerView.addItemDecoration(
                GridSpacingItemDecoration(
                    SPAN_COUNT,
                    resources.getDimensionPixelSize(R.dimen.px20),
                    includeEdge = true
                )
            )
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                if (!isLoading && hasMore && layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 5) {
                    currentPage++
                    loadUsers()
                }
            }
        })
    }

    override fun initData() {
        loadUsers()
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        restoreFocus()
    }

    override fun onPause() {
        super.onPause()
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: String) {
        if (isHidden || !isVisible) return
        if (event in setOf("signIn", "updateUserInfo", "clickTab4")) {
            currentPage = 1
            hasMore = true
            totalCount = 0
            loadUsers()
        }
    }

    private fun loadUsers() {
        if (userId <= 0L || isLoading || !hasMore) {
            return
        }

        isLoading = true
        if (currentPage == 1 && adapter.itemCount == 0) {
            binding.progressBar.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val result = if (type == TYPE_FOLLOWER) {
                userRepository.getFollower(userId, currentPage, pageSize)
            } else {
                userRepository.getFollowing(userId, currentPage, pageSize)
            }

            binding.progressBar.visibility = View.GONE
            isLoading = false

            result.onSuccess { response ->
                val wrapper = response.data
                val users = wrapper?.list.orEmpty()
                if (response.isSuccess) {
                    totalCount = wrapper?.total ?: totalCount
                    hasMore = when {
                        totalCount > 0 -> currentPage * pageSize < totalCount
                        else -> users.size >= pageSize
                    }
                    renderUsers(users)
                } else {
                    rollbackPage()
                    handleLoadError(response.errorMessage)
                }
            }.onFailure { throwable ->
                rollbackPage()
                handleLoadError(throwable.message ?: getString(R.string.net_error))
            }
        }
    }

    private fun renderUsers(users: List<FollowingModel>) {
        if (currentPage == 1) {
            adapter.setData(users)
        } else if (users.isNotEmpty()) {
            adapter.addData(users)
        }

        val isEmpty = currentPage == 1 && users.isEmpty()
        if (isEmpty) {
            showEmpty(getString(R.string.empty))
        } else {
            showListContent()
            if (!hasRequestedInitialFocus && currentPage == 1) {
                hasRequestedInitialFocus = true
                requestBackFocus()
            } else if (lastFocusedPosition != RecyclerView.NO_POSITION) {
                restoreFocus()
            }
        }
    }

    private fun showListContent() {
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
    }

    private fun showEmpty(message: String) {
        if (currentPage == 1 && adapter.itemCount == 0) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = message
            binding.recyclerView.visibility = View.GONE
            requestBackFocus()
        }
    }

    private fun handleLoadError(message: String) {
        if (currentPage == 1 && adapter.itemCount == 0) {
            showEmpty(message)
            return
        }
        showListContent()
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun rollbackPage() {
        if (currentPage > 1) {
            currentPage--
        }
    }

    private fun onUserClick(user: FollowingModel) {
        if (user.mid > 0) {
            lastFocusedPosition = adapter.getFocusedPosition()
            openInHostContainer(UserSpaceFragment.newInstance(user.mid))
        }
    }

    private fun restoreFocus() {
        if (!isAdded) return
        binding.recyclerView.post {
            if (!isAdded) return@post
            if (binding.recyclerView.isVisible && adapter.itemCount > 0 && lastFocusedPosition != RecyclerView.NO_POSITION) {
                val targetPosition = lastFocusedPosition.coerceIn(0, adapter.itemCount - 1)
                binding.recyclerView.scrollToPosition(targetPosition)
                requestItemFocus(targetPosition)
            } else {
                requestBackFocus()
            }
        }
    }

    private fun requestBackFocus() {
        if (!isAdded) return
        binding.buttonBack.post {
            if (isAdded && !binding.buttonBack.hasFocus()) {
                binding.buttonBack.requestFocus()
            }
        }
    }

    private fun requestItemFocus(position: Int, retries: Int = 6) {
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(position)
        if (holder?.itemView?.requestFocus() == true) {
            return
        }
        if (retries > 0) {
            binding.recyclerView.post { requestItemFocus(position, retries - 1) }
        }
    }
}
