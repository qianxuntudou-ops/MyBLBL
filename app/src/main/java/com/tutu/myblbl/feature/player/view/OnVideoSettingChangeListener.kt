package com.tutu.myblbl.feature.player.view

interface OnVideoSettingChangeListener {
    fun onVideoQualityClick() {}
    fun onAudioQualityClick() {}
    fun onPlaybackSpeedClick() {}
    fun onSubtitleClick() {}
    fun onVideoCodecClick() {}
    fun onEpisodeClick() {}
    fun onRelatedClick() {}
    fun onUpInfo() {}
    fun onRelated() {}
    fun onRepeat() {}
    fun onSubtitle() {}
    fun onPrevious() {}
    fun onNext() {}
    fun onDmEnableChange(enabled: Boolean) {}
    fun onChooseEpisode() {}
    fun onMore() {}
    fun onVideoInfo() {}
    fun onLiveSettings() {}
    fun onLiveQualityChange(qn: Int) {}
    fun onClose() {}
}
