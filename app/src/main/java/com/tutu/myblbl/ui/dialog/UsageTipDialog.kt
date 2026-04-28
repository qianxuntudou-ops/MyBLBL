package com.tutu.myblbl.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.DialogUsageTipBinding

class UsageTipDialog(context: Context) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogUsageTipBinding.inflate(LayoutInflater.from(context))

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.6).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        binding.buttonConfirm.setOnClickListener { dismiss() }
        setOnShowListener {
            binding.buttonConfirm.requestFocus()
        }
    }
}
