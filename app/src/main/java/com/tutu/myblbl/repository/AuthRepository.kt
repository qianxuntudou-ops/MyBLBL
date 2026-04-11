package com.tutu.myblbl.repository

import com.tutu.myblbl.repository.remote.AuthRepository as NetworkAuthRepository

class AuthRepository(
    private val delegate: NetworkAuthRepository
) {
    suspend fun getQrCode() = delegate.getQrCode()

    suspend fun checkSignInResult(qrcodeKey: String) = delegate.checkSignInResult(qrcodeKey)
}
