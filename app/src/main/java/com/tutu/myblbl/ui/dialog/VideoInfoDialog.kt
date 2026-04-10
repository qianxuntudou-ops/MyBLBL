package com.tutu.myblbl.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.DialogVideoInfoBinding
import com.tutu.myblbl.utils.ImageLoader

class VideoInfoDialog(
    context: Context,
    private val coverUrl: String,
    private val title: String,
    private val description: String
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogVideoInfoBinding.inflate(LayoutInflater.from(context))

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        bindContent()
        binding.buttonClose.setOnClickListener { dismiss() }
    }

    private fun bindContent() {
        ImageLoader.loadVideoCover(
            imageView = binding.imageView,
            url = coverUrl,
            placeholder = R.drawable.default_video,
            error = R.drawable.default_video
        )
        binding.textTitle.text = title
        binding.textDescription.text = description
    }
}
