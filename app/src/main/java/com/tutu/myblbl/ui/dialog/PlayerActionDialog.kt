package com.tutu.myblbl.ui.dialog

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.DialogActionBinding
import com.tutu.myblbl.model.favorite.FavoriteFolderModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext

class PlayerActionDialog(
    context: Context,
    private val aid: Long,
    private val bvid: String,
    private val ownerMid: Long = 0L
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    private val binding = DialogActionBinding.inflate(LayoutInflater.from(context))
    private val appSettings: AppSettingsDataStore get() = GlobalContext.get().get()
    private val videoRepository: VideoRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isLiked = false
    private var isFavorited = false
    private var isCoined = false
    private var selectedCoinMultiply = loadCoinMultiply()

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        binding.root.setOnClickListener { dismiss() }
        initListeners()
        renderState()
        setOnShowListener {
            binding.buttonLike.requestFocus()
            refreshState()
        }
    }

    private fun initListeners() {
        binding.buttonLike.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            scope.launch {
                runCatching {
                    videoRepository.like(aid, bvid, if (isLiked) 0 else 1)
                }.onSuccess { response ->
                    AppLog.d("PlayerAction", "like response: code=${response.code}, message=${response.message}")
                    if (response.isSuccess) {
                        isLiked = !isLiked
                        renderState()
                        Toast.makeText(
                            context,
                            if (isLiked) context.getString(R.string.liked_) else context.getString(R.string.like),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        if (isRiskControl(response.code, response.message)) {
                            AppLog.w("PlayerAction", "like risk control detected: code=${response.code}, message=${response.message}")
                            showRiskControlHint()
                        } else {
                            toast(response.message)
                        }
                    }
                }.onFailure {
                    AppLog.e("PlayerAction", "like failed", it)
                    toast(it.message ?: "操作失败")
                }
            }
        }

        binding.buttonCoin.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            if (isCoined) {
                toast(context.getString(R.string.give_coin_))
                return@setOnClickListener
            }
            scope.launch {
                runCatching {
                    videoRepository.giveCoin(aid, bvid, multiply = selectedCoinMultiply, selectLike = 0)
                }.onSuccess { response ->
                    AppLog.d("PlayerAction", "coin response: code=${response.code}, message=${response.message}")
                    if (response.isSuccess) {
                        isCoined = true
                        renderState()
                        toast("投币成功")
                    } else {
                        if (isRiskControl(response.code, response.message)) {
                            AppLog.w("PlayerAction", "coin risk control detected: code=${response.code}, message=${response.message}")
                            showRiskControlHint()
                        } else {
                            toast(response.message)
                        }
                    }
                }.onFailure {
                    AppLog.e("PlayerAction", "coin failed", it)
                    toast(it.message ?: "操作失败")
                }
            }
        }
        binding.buttonCoin.setOnLongClickListener {
            if (!checkLogin()) return@setOnLongClickListener true
            if (isCoined) {
                toast(context.getString(R.string.give_coin_))
                return@setOnLongClickListener true
            }
            showCoinCountDialog()
            true
        }

        binding.buttonCollection.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            scope.launch {
                val result = if (isFavorited) {
                    favoriteRepository.removeFavorite(aid, "")
                } else {
                    favoriteRepository.addFavorite(aid, "")
                }
                result.onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = !isFavorited
                        renderState()
                        toast(
                            if (isFavorited) context.getString(R.string.collection_)
                            else context.getString(R.string.collection)
                        )
                    } else {
                        if (isRiskControl(response.code, response.errorMessage)) {
                            showRiskControlHint()
                        } else {
                            toast(response.errorMessage)
                        }
                    }
                }.onFailure { toast(it.message ?: "操作失败") }
            }
        }
        binding.buttonCollection.setOnLongClickListener {
            if (!checkLogin()) return@setOnLongClickListener true
            showFavoriteFolderDialog()
            true
        }

        binding.buttonTriple.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            AppLog.d("PlayerActionDialog", "tripleAction clicked: aid=$aid, bvid=$bvid")
            scope.launch {
                runCatching {
                    // 只传 bvid，不传 aid，避免大 aid 可能导致的问题
                    videoRepository.tripleAction(null, bvid)
                }.onSuccess { response ->
                    AppLog.d("PlayerActionDialog", "tripleAction response: code=${response.code}, message=${response.message}, msg=${response.msg}")
                    if (response.isSuccess) {
                        isLiked = true
                        isCoined = true
                        isFavorited = true
                        renderState()
                        toast(context.getString(R.string.triple_action))
                    } else {
                        if (isRiskControl(response.code, response.message)) {
                            showRiskControlHint()
                        } else {
                            toast(response.errorMessage)
                        }
                    }
                }.onFailure { 
                    AppLog.e("PlayerActionDialog", "tripleAction failed", it)
                    toast(it.message ?: "操作失败")
                }
            }
        }
    }

    private fun refreshState() {
        if (!sessionGateway.isLoggedIn()) {
            return
        }
        scope.launch {
            runCatching { videoRepository.hasLike(aid, bvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isLiked = response.data == 1
                        renderState()
                    }
                }
            runCatching { videoRepository.hasGiveCoin(aid, bvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isCoined = (response.data?.multiply ?: 0) > 0
                        renderState()
                    }
                }
            favoriteRepository.checkFavorite(aid)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = response.data?.favoured == true
                        renderState()
                    }
                }
        }
    }

    private fun renderState() {
        binding.iconLike.alpha = 1f
        binding.textLike.alpha = 1f
        binding.textLike.text = context.getString(if (isLiked) R.string.liked_ else R.string.like)
        binding.iconCoin.alpha = 1f
        binding.textCoin.alpha = 1f
        binding.textCoin.text = context.getString(if (isCoined) R.string.give_coin_ else R.string.give_coin)
        binding.iconCollection.alpha = 1f
        binding.textCollection.alpha = 1f
        binding.textCollection.text = context.getString(
            if (isFavorited) R.string.collection_ else R.string.collection
        )
        updateActionAppearance(
            enabled = isLiked,
            iconView = binding.iconLike,
            textView = binding.textLike
        )
        updateActionAppearance(
            enabled = isCoined,
            iconView = binding.iconCoin,
            textView = binding.textCoin
        )
        updateActionAppearance(
            enabled = isFavorited,
            iconView = binding.iconCollection,
            textView = binding.textCollection
        )
        binding.buttonTriple.isVisible = true
        val allDone = isLiked && isCoined && isFavorited
        binding.buttonTriple.setTextColor(
            ContextCompat.getColor(
                context,
                if (allDone) R.color.pink else R.color.white
            )
        )
    }

    private fun updateActionAppearance(
        enabled: Boolean,
        iconView: androidx.appcompat.widget.AppCompatImageView,
        textView: androidx.appcompat.widget.AppCompatTextView
    ) {
        val color = ContextCompat.getColor(
            context,
            if (enabled) R.color.pink else R.color.textColor
        )
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(color)
        textView.setTextColor(color)
    }

    private fun showCoinCountDialog() {
        val options = context.resources.getStringArray(R.array.give_coin_number)
        if (options.isEmpty()) {
            return
        }
        var selectedIndex = (selectedCoinMultiply - 1).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(context, R.style.DialogTheme)
            .setTitle(R.string.give_coin)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.confirm) { dialog, _ ->
                selectedCoinMultiply = (selectedIndex + 1).coerceAtLeast(1)
                persistCoinMultiply(selectedCoinMultiply)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFavoriteFolderDialog() {
        val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L } ?: ownerMid
        if (currentUserMid <= 0L) {
            toast("收藏夹信息未加载完成")
            return
        }
        scope.launch {
            favoriteRepository.getFavoriteFolders(currentUserMid)
                .onSuccess { response ->
                    if (!response.isSuccess) {
                        toast(response.errorMessage)
                        return@onSuccess
                    }
                    val folders = response.data?.list.orEmpty()
                    if (folders.isEmpty()) {
                        toast("暂无可用收藏夹")
                        return@onSuccess
                    }
                    displayFavoriteFolderChooser(folders)
                }
                .onFailure { toast(it.message ?: "加载收藏夹失败") }
        }
    }

    private fun displayFavoriteFolderChooser(folders: List<FavoriteFolderModel>) {
        val titles = folders.map { folder ->
            "${folder.title} (${folder.mediaCount})"
        }.toTypedArray()
        AlertDialog.Builder(context, R.style.DialogTheme)
            .setTitle(
                if (isFavorited) context.getString(R.string.collection_)
                else context.getString(R.string.collection)
            )
            .setItems(titles) { dialog, which ->
                val folder = folders.getOrNull(which) ?: return@setItems
                scope.launch {
                    val result = if (isFavorited) {
                        favoriteRepository.removeFavorite(aid, folder.id.toString())
                    } else {
                        favoriteRepository.addFavorite(aid, folder.id.toString())
                    }
                    result.onSuccess { response ->
                        if (response.isSuccess) {
                            isFavorited = !isFavorited
                            renderState()
                            toast(
                                if (isFavorited) {
                                    "已收藏到 ${folder.title}"
                                } else {
                                    "已从 ${folder.title} 取消收藏"
                                }
                            )
                        } else {
                            toast(response.errorMessage)
                        }
                    }.onFailure { toast(it.message ?: "操作失败") }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadCoinMultiply(): Int {
        return appSettings.getCachedString(KEY_GIVE_COIN_NUMBER_SETTINGS)?.toIntOrNull()?.coerceAtLeast(1) ?: 2
    }

    private fun persistCoinMultiply(value: Int) {
        val normalized = value.coerceAtLeast(1)
        appSettings.putStringAsync(KEY_GIVE_COIN_NUMBER_SETTINGS, normalized.toString())
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            toast(context.getString(R.string.need_sign_in))
            return false
        }
        return true
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (binding.buttonCoin.isFocused) {
                if (!checkLogin()) {
                    return true
                }
                if (isCoined) {
                    toast(context.getString(R.string.give_coin_))
                    return true
                }
                showCoinCountDialog()
                return true
            }
            if (binding.buttonCollection.isFocused) {
                if (!checkLogin()) {
                    return true
                }
                showFavoriteFolderDialog()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun isRiskControl(code: Int, message: String?): Boolean {
        if (code == -352 || code == -412 || code == -351) return true
        val msg = message.orEmpty()
        return msg.contains("风控") || msg.contains("拦截") || msg.contains("异常") || msg.contains("非法")
    }

    private fun showRiskControlHint() {
        Toast.makeText(context, "账号被风控了，请到B站官方App或网页端完成验证后再试", Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val KEY_GIVE_COIN_NUMBER_SETTINGS = "give_coin_number"
    }
}
