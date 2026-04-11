package com.tutu.myblbl.feature.player.view

import com.tutu.myblbl.model.dm.SpecialDanmakuModel

class MyPlayerSpecialDanmakuController(
    private val overlayViewProvider: () -> SpecialDanmakuOverlayView?
) {

    private var data: List<SpecialDanmakuModel> = emptyList()
    private var settings = MyPlayerDanmakuController.SettingsSnapshot(
        enabled = true,
        showAdvancedDanmaku = true,
        alpha = 1f,
        textSize = 40,
        speed = 4,
        screenArea = 3,
        allowTop = true,
        allowBottom = true,
        smartFilterLevel = 0,
        mergeDuplicate = true
    )
    private var playbackPositionMs = 0L
    private var isPlaying = false
    private var playbackSpeed = 1f

    fun setData(items: List<SpecialDanmakuModel>) {
        data = items.sortedBy { it.progress }
        overlayViewProvider()?.setData(data)
        syncView()
    }

    fun applySettings(snapshot: MyPlayerDanmakuController.SettingsSnapshot) {
        settings = snapshot
        syncView()
    }

    fun setEnabled(enabled: Boolean) {
        applySettings(settings.copy(enabled = enabled))
    }

    fun updatePlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceAtLeast(0.1f)
        syncView()
    }

    fun pause() {
        isPlaying = false
        syncView()
    }

    fun resume() {
        isPlaying = true
        syncView()
    }

    fun stop() {
        isPlaying = false
        playbackPositionMs = 0L
        syncView()
    }

    fun syncPosition(positionMs: Long, @Suppress("UNUSED_PARAMETER") forceSeek: Boolean = false) {
        playbackPositionMs = positionMs.coerceAtLeast(0L)
        syncView()
    }

    fun release() {
        overlayViewProvider()?.clear()
    }

    private fun syncView() {
        val overlayView = overlayViewProvider() ?: return
        overlayView.setData(data)
        overlayView.setRenderingConfig(
            enabled = settings.enabled && settings.showAdvancedDanmaku,
            alpha = settings.alpha,
            textScale = settings.textSize.toOverlayTextScale(),
            visibleAreaRatio = settings.screenArea.toOverlayVisibleAreaRatio(),
            allowTop = settings.allowTop,
            allowBottom = settings.allowBottom
        )
        overlayView.syncPlayback(
            positionMs = playbackPositionMs,
            playing = isPlaying,
            speed = playbackSpeed
        )
    }

    private fun Int.toOverlayTextScale(): Float {
        return when (this) {
            30 -> 0.7f
            31 -> 0.75f
            32 -> 0.8f
            33 -> 0.85f
            34 -> 0.9f
            35 -> 0.95f
            36 -> 1.0f
            37 -> 1.05f
            38 -> 1.1f
            39 -> 1.15f
            40 -> 1.2f
            41 -> 1.3f
            42 -> 1.4f
            43 -> 1.5f
            44 -> 1.6f
            45 -> 1.7f
            46 -> 1.8f
            47 -> 1.95f
            48 -> 2.1f
            49 -> 2.25f
            50 -> 2.4f
            51 -> 2.55f
            52 -> 2.7f
            53 -> 2.85f
            54 -> 3.0f
            55 -> 3.15f
            else -> 1.2f
        }
    }

    private fun Int.toOverlayVisibleAreaRatio(): Float {
        return when (this) {
            -1 -> 1f / 8f
            0 -> 1f / 6f
            1 -> 1f / 4f
            3 -> 1f / 2f
            7 -> 3f / 4f
            else -> 1f
        }
    }
}
