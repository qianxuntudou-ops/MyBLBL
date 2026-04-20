package com.tutu.myblbl.ui.dialog

import android.content.Context
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.databinding.DialogOwnerDetailBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.user.CheckRelationModel
import com.tutu.myblbl.model.video.Owner
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.ui.activity.PlayerActivity
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.image.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class OwnerDetailDialog(
    context: Context,
    private val owner: Owner,
    private val onOpenSpace: (Long) -> Unit,
    private val onPlayVideo: (VideoModel, List<VideoModel>) -> Unit,
    private val currentAid: Long = 0L,
    private val currentVideoId: String = ""
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    private val binding = DialogOwnerDetailBinding.inflate(LayoutInflater.from(context))
    private val userRepository: UserRepository by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val appEventHub: AppEventHub by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val videoAdapter = VideoAdapter()

    private var relationAttribute = 0
    private var currentPage = 1
    private var hasMore = true
    private var isLoading = false

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        binding.root.setOnClickListener { dismiss() }
        initView()
        bindOwnerHeader()
        loadData()
    }

    private fun initView() {
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(context, 3)
        binding.recyclerView.adapter = videoAdapter
        if (binding.recyclerView.itemDecorationCount == 0) {
            binding.recyclerView.addItemDecoration(
                GridSpacingItemDecoration(3, context.resources.getDimensionPixelSize(R.dimen.px20), true)
            )
        }
        videoAdapter.setOnItemClickListener { _, item ->
            dismiss()
            onPlayVideo(
                item,
                PlayerActivity.buildPlayQueue(videoAdapter.getItemsSnapshot(), item)
            )
        }
        binding.buttonFollow.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            toggleFollow()
        }
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager ?: return
                val lastVisibleItem = (layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
                    ?.findLastVisibleItemPosition() ?: return
                val totalItems = recyclerView.adapter?.itemCount ?: return
                if (!isLoading && hasMore && lastVisibleItem >= totalItems - 3) {
                    currentPage++
                    loadOwnerVideos()
                }
            }
        })
    }

    private fun bindOwnerHeader() {
        binding.textName.text = owner.name
        ImageLoader.loadCircle(
            imageView = binding.imageAvatar,
            url = owner.face,
            placeholder = R.drawable.default_avatar,
            error = R.drawable.default_avatar
        )
        binding.imageAvatar.setBadge(
            officialVerifyType = owner.officialVerify?.type ?: -1
        )

        val isSelf = sessionGateway.getUserInfo()?.mid == owner.mid
        binding.buttonFollow.isVisible = !isSelf
        updateFollowUi(
            labelRes = R.string.follow,
            textColorRes = R.color.colorAccent,
            iconRes = R.drawable.ic_plus,
            iconTintRes = R.color.colorAccent
        )
    }

    private fun loadData() {
        loadRelationState()
        loadOwnerVideos()
    }

    private fun loadRelationState() {
        if (!sessionGateway.isLoggedIn() || sessionGateway.getUserInfo()?.mid == owner.mid) {
            return
        }
        scope.launch {
            userRepository.checkUserRelation(owner.mid)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        updateRelationState(response.data)
                    }
                }
        }
    }

    private fun updateRelationState(relation: CheckRelationModel) {
        relationAttribute = relation.attribute
        when {
            relation.isMutualFollow -> updateFollowUi(
                labelRes = R.string.follow_as_friend,
                textColorRes = R.color.grey,
                iconRes = R.drawable.ic_check,
                iconTintRes = R.color.grey
            )
            relationAttribute == 2 -> updateFollowUi(
                labelRes = R.string.followed,
                textColorRes = R.color.grey,
                iconRes = R.drawable.ic_check,
                iconTintRes = R.color.grey
            )
            else -> updateFollowUi(
                labelRes = R.string.follow,
                textColorRes = R.color.colorAccent,
                iconRes = R.drawable.ic_plus,
                iconTintRes = R.color.colorAccent
            )
        }
    }

    private fun loadOwnerVideos() {
        if (isLoading || !hasMore) return
        isLoading = true
        if (currentPage == 1) {
            binding.progressBar.isVisible = true
        }
        scope.launch {
            val result = userRepository.getUserDynamic(owner.mid, page = currentPage, pageSize = 20)
            result.onSuccess { response ->
                binding.progressBar.isVisible = false
                isLoading = false
                if (response.isSuccess) {
                    val videos = ContentFilter.filterVideos(binding.root.context, response.data?.archives.orEmpty())
                    hasMore = response.data?.hasMore ?: false
                    if (currentPage == 1) {
                        videoAdapter.setData(videos)
                        scrollToCurrentVideo(videos)
                    } else {
                        videoAdapter.addData(videos)
                    }
                    if (!hasMore) {
                        videoAdapter.setShowLoadMore(false)
                    }
                } else {
                    toast(response.message)
                }
            }.onFailure {
                binding.progressBar.isVisible = false
                isLoading = false
                currentPage--
                toast(it.message ?: "加载失败")
            }
        }
    }

    private fun scrollToCurrentVideo(videos: List<VideoModel>) {
        val targetIndex = videos.indexOfFirst { video ->
            (currentAid > 0L && video.aid == currentAid) ||
                (currentVideoId.isNotBlank() && video.bvid == currentVideoId)
        }
        if (targetIndex >= 0) {
            binding.recyclerView.post {
                binding.recyclerView.scrollToPosition(targetIndex)
                focusVideoAt(targetIndex)
            }
            return
        }
        binding.recyclerView.post {
            if (binding.buttonFollow.isVisible) {
                binding.buttonFollow.requestFocus()
            } else {
                binding.recyclerView.requestFocus()
            }
        }
    }

    private fun focusVideoAt(targetIndex: Int, retries: Int = 6) {
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(targetIndex)
        if (holder?.itemView != null) {
            holder.itemView.requestFocus()
            return
        }
        if (retries > 0) {
            binding.recyclerView.post { focusVideoAt(targetIndex, retries - 1) }
        }
    }

    private fun updateFollowUi(
        labelRes: Int,
        textColorRes: Int,
        iconRes: Int,
        iconTintRes: Int
    ) {
        binding.textFollow.text = context.getString(labelRes)
        binding.textFollow.setTextColor(ContextCompat.getColor(context, textColorRes))
        binding.iconFollow.setImageResource(iconRes)
        binding.iconFollow.imageTintList =
            ContextCompat.getColorStateList(context, iconTintRes)
    }

    private fun toggleFollow() {
        val action = if (isFollowing()) 2 else 1
        scope.launch {
            userRepository.modifyRelation(owner.mid, action)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        relationAttribute = if (action == 1) 2 else 0
                        updateRelationState(
                            CheckRelationModel(
                                attribute = relationAttribute
                            )
                        )
                        toast(if (action == 1) "关注成功" else "已取消关注")
                    } else {
                        if (sessionGateway.handleResponseAuthError(response.code, response.message)) {
                            toast(context.getString(R.string.login_expired))
                        } else {
                            toast(response.message)
                        }
                    }
                }
                .onFailure {
                    AppLog.e("OwnerDetailDialog", "toggleFollow failed", it)
                    toast(it.message ?: "操作失败")
                }
        }
    }

    private fun isFollowing(): Boolean {
        return relationAttribute == 2 || relationAttribute == 6
    }

    private fun checkLogin(): Boolean = sessionGateway.isLoggedIn()

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }
}
