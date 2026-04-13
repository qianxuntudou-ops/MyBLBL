package com.tutu.myblbl.ui.dialog

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.DialogUserInfoBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.model.user.UserStatModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.feature.detail.UserSpaceFragment
import com.tutu.myblbl.feature.user.FollowUserListFragment
import com.tutu.myblbl.core.ui.image.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class UserInfoDialog(context: Context) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    private val binding = DialogUserInfoBinding.inflate(LayoutInflater.from(context))
    private val appEventHub: AppEventHub by inject()
    private val userRepository: UserRepository by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        binding.root.setOnClickListener { dismiss() }
        bindUserInfo(sessionGateway.getUserInfo())
        bindUserStat(null)
        initListeners()
        setOnShowListener {
            binding.buttonViewSpace.requestFocus()
        }
        scope.launch {
            refreshUserInfo()
            refreshUserStat()
        }
    }

    private fun initListeners() {
        binding.buttonViewSpace.setOnClickListener {
            val mid = sessionGateway.getUserInfo()?.mid ?: 0L
            if (mid > 0L) {
                openOverlay(UserSpaceFragment.newInstance(mid), "user_space")
            }
        }
        binding.textFollowing.setOnClickListener {
            val mid = sessionGateway.getUserInfo()?.mid ?: 0L
            if (mid > 0L) {
                openOverlay(
                    FollowUserListFragment.newInstance(mid, FollowUserListFragment.TYPE_FOLLOWING),
                    "following"
                )
            }
        }
        binding.textFollower.setOnClickListener {
            val mid = sessionGateway.getUserInfo()?.mid ?: 0L
            if (mid > 0L) {
                openOverlay(
                    FollowUserListFragment.newInstance(mid, FollowUserListFragment.TYPE_FOLLOWER),
                    "follower"
                )
            }
        }
        binding.buttonSignOut.setOnClickListener {
            sessionGateway.clearUserSession(reason = "userInfoDialog.signOut")
            appEventHub.dispatch(AppEventHub.Event.UserSessionChanged)
            dismiss()
        }
    }

    private suspend fun refreshUserInfo() {
        val info = userRepository.refreshCurrentUserInfo().getOrNull() ?: return
        bindUserInfo(info)
    }

    private suspend fun refreshUserStat() {
        val response = runCatching { userRepository.getUserStat() }.getOrNull() ?: return
        if (response.isSuccess) {
            bindUserStat(response.data)
        }
    }

    private fun bindUserInfo(info: UserDetailInfoModel?) {
        ImageLoader.loadCircle(
            imageView = binding.imageAvatar,
            url = info?.face,
            placeholder = R.drawable.default_avatar,
            error = R.drawable.default_avatar
        )
        if (info != null) {
            val oType = info.officialVerify?.type ?: info.official?.let { if (it.role > 0) it.type else -1 } ?: -1
            val vStatus = info.vipStatus.coerceAtLeast(info.vip?.vipStatus ?: 0)
            val vType = info.vipType.coerceAtLeast(info.vip?.vipType ?: 0)
            binding.imageAvatar.setBadge(
                officialVerifyType = oType,
                vipStatus = vStatus,
                vipType = vType
            )
        } else {
            binding.imageAvatar.setBadge(officialVerifyType = -1)
        }
        binding.textName.text = info?.uname.orEmpty().ifBlank { "Nickname" }
        binding.textVip.text = info?.vipLabel?.text
            .orEmpty()
            .ifBlank { info?.vip?.label?.text.orEmpty() }
            .ifBlank { "普通会员" }
        binding.textLevel.text = context.getString(
            R.string.level_,
            info?.levelInfo?.currentLevel ?: 0
        )
        val coinValue = info?.wallet?.bcoinBalance?.takeIf { it > 0 }?.toDouble()
            ?: info?.money
            ?: 0.0
        binding.textCoin.text = context.getString(R.string.coin_number_, coinValue.toFloat())
    }

    private fun bindUserStat(stat: UserStatModel?) {
        binding.textFollowing.text = context.getString(
            R.string.following_count_,
            stat?.following ?: 0
        )
        binding.textFollower.text = context.getString(
            R.string.follower_count_,
            stat?.follower ?: 0
        )
        binding.textDynamic.text = context.getString(
            R.string.dynamic_count_,
            stat?.dynamicCount ?: 0
        )
    }

    private fun openOverlay(fragment: Fragment, tag: String) {
        dismiss()
        (findHostActivity() as? MainActivity)?.openOverlayFragment(fragment, tag)
    }

    private fun findHostActivity(): FragmentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }
}
