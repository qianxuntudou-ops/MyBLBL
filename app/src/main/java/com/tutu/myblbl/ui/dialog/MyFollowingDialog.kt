package com.tutu.myblbl.ui.dialog

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.core.ui.focus.RecyclerViewLoadMoreFocusController
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.databinding.DialogMyFollowingBinding
import com.tutu.myblbl.model.series.SeriesModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.SeriesRepository
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.ui.adapter.SeriesAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MyFollowingDialog(
    context: Context,
    private val type: Int,
    private val onSeriesClick: (SeriesModel) -> Unit
) : AppCompatDialog(context, R.style.FullScreenDialogTheme), KoinComponent {

    companion object {
        const val TYPE_ANIMATION = 1
        const val TYPE_CINEMA = 2
        private const val PAGE_SIZE = 20
        private const val SPAN_COUNT = 6
    }

    private val binding = DialogMyFollowingBinding.inflate(LayoutInflater.from(context))
    private val repository: SeriesRepository by inject()
    private val userRepository: UserRepository by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = SeriesAdapter(
        onItemClick = { series ->
            dismiss()
            onSeriesClick(series)
        },
        enableSidebarExit = false,
        onTopEdgeUp = {
            binding.buttonBack.requestFocus()
            true
        },
        onBottomEdgeDown = {
            !hasMore
        }
    )

    private var currentPage = 1
    private var hasMore = true
    private var isLoading = false
    private var loadMoreFocusController: RecyclerViewLoadMoreFocusController? = null

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        initView()
        loadData()
    }

    private fun initView() {
        binding.textTitle.text = if (type == TYPE_CINEMA) {
            context.getString(R.string.following_series_title)
        } else {
            context.getString(R.string.following_animation_title)
        }
        binding.buttonBack.setOnClickListener { dismiss() }
        binding.buttonBack.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                requestFirstItemFocus()
            } else {
                false
            }
        }
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(context, SPAN_COUNT)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        if (binding.recyclerView.itemDecorationCount == 0) {
            binding.recyclerView.addItemDecoration(
                GridSpacingItemDecoration(SPAN_COUNT, context.resources.getDimensionPixelSize(R.dimen.px20), true)
            )
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager ?: return
                val lastVisibleItem = (layoutManager as? WrapContentGridLayoutManager)
                    ?.findLastVisibleItemPosition() ?: return
                val totalItems = recyclerView.adapter?.itemCount ?: return
                val preloadThreshold = SPAN_COUNT * 2
                if (!isLoading && hasMore && lastVisibleItem >= totalItems - preloadThreshold) {
                    currentPage++
                    loadData()
                }
            }
        })
        installLoadMoreFocusController()
        showLoading()
    }

    private fun loadData() {
        if (isLoading) return
        if (!sessionGateway.isLoggedIn()) {
            showInfo(R.drawable.net_error, context.getString(R.string.need_sign_in))
            return
        }
        isLoading = true
        scope.launch {
            val mid = userRepository.resolveCurrentUserMid().getOrNull() ?: 0L
            if (mid <= 0) {
                isLoading = false
                showInfo(R.drawable.net_error, context.getString(R.string.need_sign_in))
                return@launch
            }
            repository.getMyFollowingSeries(type, currentPage, PAGE_SIZE, mid)
                .onSuccess { result ->
                    isLoading = false
                    val list = result.list
                    if (currentPage == 1) {
                        adapter.setData(list)
                    } else if (list.isNotEmpty()) {
                        adapter.addData(list)
                    }
                    loadMoreFocusController?.consumePendingFocusAfterLoadMore()
                    hasMore = list.size >= PAGE_SIZE
                    if (currentPage == 1 && list.isEmpty()) {
                        val emptyMsg = if (type == TYPE_CINEMA) {
                            context.getString(R.string.following_series_empty)
                        } else {
                            context.getString(R.string.following_animation_empty)
                        }
                        showInfo(R.drawable.net_error, emptyMsg)
                    } else {
                        showContent()
                        if (currentPage == 1) {
                            binding.recyclerView.post { requestFirstItemFocus() }
                        }
                    }
                }
                .onFailure {
                    isLoading = false
                    loadMoreFocusController?.clearPendingFocusAfterLoadMore()
                    if (currentPage > 1) {
                        currentPage--
                        if (adapter.itemCount > 0) {
                            return@onFailure
                        }
                    }
                    showInfo(R.drawable.net_error, it.message ?: context.getString(R.string.net_error))
                }
        }
    }

    private fun showLoading() {
        binding.recyclerView.isVisible = false
        binding.viewInfo.isVisible = true
        binding.progressBar.isVisible = true
        binding.imageInfo.isVisible = false
        binding.textInfo.isVisible = false
    }

    private fun showContent() {
        binding.recyclerView.isVisible = true
        binding.viewInfo.isVisible = false
    }

    private fun showInfo(imageRes: Int, message: String) {
        binding.recyclerView.isVisible = false
        binding.viewInfo.isVisible = true
        binding.progressBar.isVisible = false
        binding.imageInfo.isVisible = true
        binding.imageInfo.setImageResource(imageRes)
        binding.textInfo.isVisible = true
        binding.textInfo.text = message
    }

    private fun requestFirstItemFocus(): Boolean {
        val vh = binding.recyclerView.findViewHolderForLayoutPosition(0)
        if (vh?.itemView != null) {
            return vh.itemView.requestFocus()
        }
        return false
    }

    private fun installLoadMoreFocusController() {
        loadMoreFocusController?.release()
        loadMoreFocusController = RecyclerViewLoadMoreFocusController(
            recyclerView = binding.recyclerView,
            callbacks = object : RecyclerViewLoadMoreFocusController.Callbacks {
                override fun canLoadMore(): Boolean = !isLoading && hasMore

                override fun loadMore() {
                    if (!canLoadMore()) {
                        return
                    }
                    currentPage++
                    loadData()
                }
            }
        ).also { it.install() }
    }

    override fun dismiss() {
        loadMoreFocusController?.release()
        loadMoreFocusController = null
        scope.cancel()
        super.dismiss()
    }

    override fun onBackPressed() {
        dismiss()
    }
}
