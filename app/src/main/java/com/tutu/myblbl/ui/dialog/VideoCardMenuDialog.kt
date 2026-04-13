package com.tutu.myblbl.ui.dialog

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.databinding.DialogVideoCardMenuBinding
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class VideoCardMenuDialog(
    context: Context,
    private val video: VideoModel,
    private val onDislikeVideo: (() -> Unit)? = null,
    private val onDislikeUp: ((String) -> Unit)? = null
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    companion object {
        private const val TAG = "VideoCardMenuDialog"
        private const val REASON_ID_NOT_INTERESTED = 1
        private const val REASON_ID_DISLIKE_UP = 4
    }

    private val binding = DialogVideoCardMenuBinding.inflate(LayoutInflater.from(context))
    private val videoRepository: VideoRepository by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isInWatchLater = false
    private var isActionInProgress = false
    private val isLiveFeedbackCard = video.goto.equals("live", ignoreCase = true) ||
        video.roomId > 0L ||
        video.isLive ||
        video.historyBusiness == "live"
    private val supportsWatchLater = !isLiveFeedbackCard &&
        (video.aid > 0L || video.bvid.isNotBlank())

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        configureContent()
        initListeners()
        refreshWatchLaterState()
        setOnShowListener {
            binding.root.post {
                if (supportsWatchLater) {
                    binding.buttonWatchLater.requestFocus()
                } else {
                    binding.buttonDislikeVideo.requestFocus()
                }
            }
        }
    }

    private fun initListeners() {
        binding.root.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
                && event.action == KeyEvent.ACTION_UP
            ) {
                dismiss()
                true
            } else {
                false
            }
        }

        binding.buttonWatchLater.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkLogin()) return@setOnClickListener
            setActionInProgress(true)
            if (isInWatchLater) {
                removeWatchLater()
            } else {
                addWatchLater()
            }
        }

        binding.buttonDislikeVideo.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkLogin()) return@setOnClickListener
            AppLog.d(
                TAG,
                "click dislike video: aid=${video.aid}, bvid=${video.bvid}, title=${video.title.take(30)}, ownerMid=${video.owner?.mid ?: 0L}"
            )
            setActionInProgress(true)
            dislikeVideo(REASON_ID_NOT_INTERESTED)
        }

        binding.buttonDislikeUp.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkLogin()) return@setOnClickListener
            if (!canDislikeUp()) return@setOnClickListener
            AppLog.d(
                TAG,
                "click dislike up: aid=${video.aid}, bvid=${video.bvid}, ownerMid=${video.owner?.mid ?: 0L}, ownerName=${video.authorName}"
            )
            setActionInProgress(true)
            dislikeVideo(REASON_ID_DISLIKE_UP)
        }
    }

    private fun dislikeVideo(reasonId: Int) {
        val csrf = sessionGateway.getCsrfToken()
        AppLog.d(
            TAG,
            "dislikeVideo start: reasonId=$reasonId, loggedIn=${sessionGateway.isLoggedIn()}, hasCsrf=${csrf.isNotBlank()}, aid=${video.aid}, bvid=${video.bvid}, goto=${video.goto}, roomId=${video.roomId}, isLiveCard=$isLiveFeedbackCard, ownerMid=${video.owner?.mid ?: 0L}, trackId=${video.trackId}"
        )
        scope.launch {
            runCatching {
                videoRepository.dislikeFeed(video, reasonId)
            }.onSuccess { response ->
                AppLog.d(
                    TAG,
                    "dislikeVideo response: reasonId=$reasonId, code=${response.code}, message=${response.message}, msg=${response.msg}, success=${response.isSuccess}"
                )
                if (response.isSuccess) {
                    if (reasonId == REASON_ID_DISLIKE_UP) {
                        toast(context.getString(R.string.toast_dislike_up_success))
                        val upName = video.authorName.trim()
                        if (upName.isNotBlank()) {
                            onDislikeUp?.invoke(upName)
                        }
                    } else {
                        toast(
                            context.getString(
                                if (supportsWatchLater) {
                                    R.string.toast_dislike_video_success
                                } else if (isLiveFeedbackCard) {
                                    R.string.toast_dislike_live_success
                                } else {
                                    R.string.toast_dislike_video_success
                                }
                            )
                        )
                        onDislikeVideo?.invoke()
                    }
                    dismiss()
                } else {
                    toast(
                        context.getString(
                            if (reasonId == REASON_ID_DISLIKE_UP) {
                                R.string.toast_dislike_up_failed
                            } else if (isLiveFeedbackCard) {
                                R.string.toast_dislike_live_failed
                            } else if (supportsWatchLater) {
                                R.string.toast_dislike_video_failed
                            } else {
                                R.string.toast_dislike_video_failed
                            }
                        )
                    )
                    setActionInProgress(false)
                }
            }.onFailure {
                AppLog.e(
                    TAG,
                    "dislikeVideo failure: reasonId=$reasonId, aid=${video.aid}, bvid=${video.bvid}, ownerMid=${video.owner?.mid ?: 0L}, message=${it.message}",
                    it
                )
                toast(
                    context.getString(
                        if (reasonId == REASON_ID_DISLIKE_UP) {
                            R.string.toast_dislike_up_failed
                        } else if (isLiveFeedbackCard) {
                            R.string.toast_dislike_live_failed
                        } else if (supportsWatchLater) {
                            R.string.toast_dislike_video_failed
                        } else {
                            R.string.toast_dislike_video_failed
                        }
                    )
                )
                setActionInProgress(false)
            }
        }
    }

    private fun addWatchLater() {
        scope.launch {
            runCatching {
                videoRepository.addWatchLater(video.aid, video.bvid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isInWatchLater = true
                    renderWatchLaterState()
                    toast(context.getString(R.string.toast_add_watch_later_success))
                } else {
                    val msg = response.errorMessage
                    if (msg.contains("90001") || msg.contains("上限") || msg.contains("已满")) {
                        toast(context.getString(R.string.toast_watch_later_full))
                    } else {
                        toast(context.getString(R.string.toast_add_watch_later_failed))
                    }
                }
                setActionInProgress(false)
            }.onFailure {
                toast(context.getString(R.string.toast_add_watch_later_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun removeWatchLater() {
        scope.launch {
            runCatching {
                videoRepository.removeWatchLater(video.aid, video.bvid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isInWatchLater = false
                    renderWatchLaterState()
                    toast(context.getString(R.string.toast_remove_watch_later_success))
                } else {
                    toast(context.getString(R.string.toast_remove_watch_later_failed))
                }
                setActionInProgress(false)
            }.onFailure {
                toast(context.getString(R.string.toast_remove_watch_later_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun refreshWatchLaterState() {
        if (!supportsWatchLater) return
        if (!sessionGateway.isLoggedIn()) return
        scope.launch {
            runCatching {
                videoRepository.checkWatchLater(video.aid, video.bvid)
            }.onSuccess { inList ->
                isInWatchLater = inList
                renderWatchLaterState()
            }
        }
    }

    private fun renderWatchLaterState() {
        if (isInWatchLater) {
            binding.textWatchLaterTitle.text = context.getString(R.string.menu_already_in_watch_later)
            binding.textWatchLaterTitle.setTextColor(
                ContextCompat.getColor(context, R.color.pink)
            )
            binding.textWatchLaterSummary.text = context.getString(R.string.menu_watch_later_added_summary)
        } else {
            binding.textWatchLaterTitle.text = context.getString(R.string.menu_add_watch_later)
            binding.textWatchLaterTitle.setTextColor(
                ContextCompat.getColor(context, R.color.textColor)
            )
            binding.textWatchLaterSummary.text = context.getString(R.string.menu_watch_later_summary)
        }
    }

    private fun configureContent() {
        binding.textTitle.text = video.title.ifBlank { context.getString(R.string.video) }.take(60)
        binding.buttonWatchLater.visibility = if (supportsWatchLater) View.VISIBLE else View.GONE
        binding.textSubtitle.text = context.getString(
            if (isLiveFeedbackCard) {
                R.string.menu_live_card_subtitle
            } else {
                R.string.menu_video_card_subtitle
            }
        )
        binding.textDislikeVideoTitle.text = context.getString(
            if (isLiveFeedbackCard) {
                R.string.menu_dislike_live
            } else {
                R.string.menu_dislike_video
            }
        )
        binding.textDislikeVideoSummary.text = context.getString(
            if (isLiveFeedbackCard) {
                R.string.menu_dislike_live_summary
            } else {
                R.string.menu_dislike_video_summary
            }
        )
        renderWatchLaterState()
        renderDislikeUpState()
    }

    private fun renderDislikeUpState() {
        val upName = video.authorName.trim()
        val hasUpName = upName.isNotBlank()
        binding.textDislikeUpTitle.text = if (hasUpName) {
            context.getString(R.string.menu_dislike_up_title_with_name, upName)
        } else {
            context.getString(R.string.menu_dislike_up)
        }
        binding.textDislikeUpSummary.text = if (hasUpName) {
            context.getString(
                if (isLiveFeedbackCard) {
                    R.string.menu_dislike_live_up_summary
                } else {
                    R.string.menu_dislike_up_summary
                }
            )
        } else {
            context.getString(R.string.menu_dislike_up_unknown_summary)
        }
        val enabled = canDislikeUp() && !isActionInProgress
        binding.buttonDislikeUp.isEnabled = enabled
        binding.buttonDislikeUp.isClickable = enabled
        binding.buttonDislikeUp.isFocusable = enabled
        binding.buttonDislikeUp.alpha = if (canDislikeUp()) 1f else 0.45f
    }

    private fun setActionInProgress(inProgress: Boolean) {
        isActionInProgress = inProgress
        val watchLaterEnabled = supportsWatchLater && !inProgress
        val dislikeVideoEnabled = !inProgress
        binding.buttonWatchLater.isEnabled = watchLaterEnabled
        binding.buttonWatchLater.isClickable = watchLaterEnabled
        binding.buttonWatchLater.isFocusable = watchLaterEnabled
        binding.buttonDislikeVideo.isEnabled = dislikeVideoEnabled
        binding.buttonDislikeVideo.isClickable = dislikeVideoEnabled
        binding.buttonDislikeVideo.isFocusable = dislikeVideoEnabled
        binding.buttonWatchLater.alpha = if (watchLaterEnabled) 1f else 0.6f
        binding.buttonDislikeVideo.alpha = if (dislikeVideoEnabled) 1f else 0.6f
        renderDislikeUpState()
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            AppLog.d(TAG, "checkLogin failed: aid=${video.aid}, bvid=${video.bvid}, ownerMid=${video.owner?.mid ?: 0L}")
            toast(context.getString(R.string.toast_need_login))
            return false
        }
        AppLog.d(TAG, "checkLogin success: hasCsrf=${sessionGateway.getCsrfToken().isNotBlank()}")
        return true
    }

    private fun canDislikeUp(): Boolean {
        return video.owner?.mid?.let { it > 0L } == true
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

}
