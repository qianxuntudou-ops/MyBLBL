package com.tutu.myblbl.feature.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.model.user.UserStatModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MeViewModel(
    private val userRepository: UserRepository,
    private val sessionGateway: NetworkSessionGateway
) : ViewModel() {
    private var lastLoadedAt = 0L

    private val _userInfo = MutableStateFlow<UserDetailInfoModel?>(sessionGateway.getUserInfo())
    val userInfo: StateFlow<UserDetailInfoModel?> = _userInfo.asStateFlow()

    private val _userStat = MutableStateFlow<UserStatModel?>(null)
    val userStat: StateFlow<UserStatModel?> = _userStat.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(sessionGateway.isLoggedIn())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun loadUserInfo() {
        viewModelScope.launch {
            val loggedIn = sessionGateway.isLoggedIn()
            _isLoggedIn.value = loggedIn

            if (!loggedIn) {
                sessionGateway.clearUserSession(reason = "me.loadUserInfo.loggedOut")
                _userInfo.value = null
                _userStat.value = null
                return@launch
            }

            val refreshedUserInfo = userRepository.refreshCurrentUserInfo().getOrNull()
            val stillLoggedIn = sessionGateway.isLoggedIn()
            _isLoggedIn.value = stillLoggedIn
            if (!stillLoggedIn) {
                _userInfo.value = null
                _userStat.value = null
                return@launch
            }
            refreshedUserInfo?.let { info ->
                _userInfo.value = info
                lastLoadedAt = System.currentTimeMillis()
            }

            runCatching { userRepository.getUserStat() }
                .onSuccess { response ->
                    if (!sessionGateway.isLoggedIn()) {
                        _isLoggedIn.value = false
                        _userInfo.value = null
                        _userStat.value = null
                        return@onSuccess
                    }
                    if (response.isSuccess) {
                        _userStat.value = response.data
                    }
                }
        }
    }

    fun resolveCurrentUserMid(onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            val mid = userRepository.resolveCurrentUserMid().getOrNull()
            onResult(mid)
        }
    }

    fun syncWithGateway() {
        val currentLoggedIn = sessionGateway.isLoggedIn()
        if (_isLoggedIn.value != currentLoggedIn) {
            _isLoggedIn.value = currentLoggedIn
            if (!currentLoggedIn) {
                _userInfo.value = null
                _userStat.value = null
            }
        }
    }

    fun shouldRefresh(ttlMs: Long): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            return false
        }
        if (_userInfo.value == null) {
            return true
        }
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }
}
