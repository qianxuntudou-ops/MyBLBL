package com.tutu.myblbl.ui.fragment.main.settings

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentSignInBinding
import com.tutu.myblbl.repository.AuthRepository
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.utils.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.koin.android.ext.android.inject

class SignInFragment : BaseFragment<FragmentSignInBinding>() {

    companion object {
        fun newInstance() = SignInFragment()
    }

    private val authRepository: AuthRepository by inject()
    private val userRepository: UserRepository by inject()
    private var qrcodeKey = ""
    private var pollingJob: Job? = null
    private val pollingInterval = 1500L

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSignInBinding {
        return FragmentSignInBinding.inflate(inflater, container, false)
    }

    override fun initView() = Unit

    override fun initData() {
        loadQrCode()
    }

    private fun loadQrCode() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        
        lifecycleScope.launch {
            val result = authRepository.getQrCode()
            binding.progressBar.visibility = android.view.View.GONE
            
            result.onSuccess { response ->
                if (response.isSuccess && response.data != null) {
                    qrcodeKey = response.data.qrcodeKey
                    val qrUrl = response.data.url
                    displayQrCode(qrUrl)
                    startPolling()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.sign_in_qr_failed_format, response.message.orEmpty()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { e ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sign_in_qr_failed_format, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun displayQrCode(url: String) {
        try {
            val bitmap = generateQrCode(url)
            bitmap?.let {
                binding.imageView.setImageBitmap(it)
            }
        } catch (e: Exception) {
            AppLog.e("SignInFragment", "displayQrCode failed", e)
            Toast.makeText(requireContext(), "生成二维码失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            AppLog.e("SignInFragment", "generateQrCode failed", e)
            null
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive && qrcodeKey.isNotEmpty()) {
                delay(pollingInterval)
                checkSignInResultInternal()
            }
        }
    }

    private suspend fun checkSignInResultInternal() {
        if (qrcodeKey.isEmpty()) return
        
        val result = authRepository.checkSignInResult(qrcodeKey)
        result.onSuccess { response ->
            val data = response.data
            if (data != null && data.isSuccess()) {
                pollingJob?.cancel()
                saveCookiesFromLoginUrl(data.url)
                Toast.makeText(requireContext(), "登录成功", Toast.LENGTH_SHORT).show()
                onLoginSuccess()
            } else if (data != null && data.code == 86038) {
                pollingJob?.cancel()
                loadQrCode()
            }
        }
    }

    private fun saveCookiesFromLoginUrl(url: String) {
        if (url.isBlank()) return
        try {
            val cookieNames = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
            val queryPart = url.substringAfter("?", "")
            if (queryPart.isBlank()) return
            val cookieStrings = queryPart.split("&")
                .filter { pair ->
                    val name = pair.substringBefore("=", "")
                    name in cookieNames
                }
                .map { pair ->
                    val name = pair.substringBefore("=", "")
                    val value = pair.substringAfter("=", "")
                    "$name=$value; domain=bilibili.com; path=/; secure"
                }
            if (cookieStrings.isNotEmpty()) {
                NetworkManager.getCookieManager().saveCookies(cookieStrings)
            }
        } catch (e: Exception) {
            AppLog.e("SignInFragment", "saveCookiesFromLoginUrl failed", e)
        }
    }

    private fun onLoginSuccess() {
        lifecycleScope.launch {
            userRepository.refreshCurrentUserInfo()
            parentFragmentManager.popBackStackImmediate()
            EventBus.getDefault().post("signIn")
            EventBus.getDefault().post("updateUserInfo")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingJob?.cancel()
    }
}
