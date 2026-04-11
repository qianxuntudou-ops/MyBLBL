package com.tutu.myblbl.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.fragment.app.commit
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.ActivityPlayerBinding
import com.tutu.myblbl.ui.base.BaseActivity
import com.tutu.myblbl.feature.player.LivePlayerFragment

@OptIn(UnstableApi::class)
class LivePlayerActivity : BaseActivity<ActivityPlayerBinding>() {

    override fun getViewBinding(): ActivityPlayerBinding =
        ActivityPlayerBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1L)
        if (roomId <= 0) return finish()

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.player_container, LivePlayerFragment.newInstance(roomId))
            }
        }
    }

    companion object {
        private const val EXTRA_ROOM_ID = "room_id"

        fun start(context: Context, roomId: Long) {
            context.startActivity(Intent(context, LivePlayerActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
            })
        }
    }
}
