package com.tutu.myblbl.ui.fragment.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.model.episode.EpisodeModel
import com.tutu.myblbl.model.episode.EpisodeStatModel
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.video.VideoPvModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.network.api.ApiService

/**
 * Converts UGC / PGC detail payloads into the episode catalog shape consumed by the player UI.
 */
@OptIn(UnstableApi::class)
class VideoPlayerEpisodeCatalogBuilder(
    private val apiService: ApiService
) {

    suspend fun buildUgcEpisodes(
        detail: VideoDetailModel
    ): List<VideoPlayerViewModel.PlayableEpisode> {
        val view = detail.view ?: return emptyList()

        val pages = view.pages.orEmpty().ifEmpty {
            runCatching { apiService.getVideoPv(view.aid, view.bvid) }
                .getOrNull()
                ?.data
                .orEmpty()
        }

        if (pages.isNotEmpty()) {
            return pages.mapIndexed { index, page ->
                page.toPlayableEpisode(index, view)
            }
        }

        val ugcEpisodes = view.ugcSeason?.sections
            .orEmpty()
            .flatMap { it.episodes.orEmpty() }
            .mapIndexed { index, episode ->
                episode.toPlayableEpisode(
                    index = index,
                    fallbackView = view
                )
            }

        if (ugcEpisodes.isNotEmpty()) {
            return ugcEpisodes
        }

        return pages.mapIndexed { index, page ->
            page.toPlayableEpisode(index, view)
        }
    }

    fun buildPgcEpisodes(
        detail: EpisodesDetailModel
    ): List<VideoPlayerViewModel.PlayableEpisode> {
        val seasonId = detail.seasonId
        val mainEpisodes = detail.episodes.orEmpty().mapIndexed { index, episode ->
            episode.toPlayableEpisode(index, detail.cover, seasonId)
        }
        if (mainEpisodes.isNotEmpty()) {
            return mainEpisodes
        }
        return detail.section.orEmpty()
            .flatMap { section -> section.episodes }
            .mapIndexed { index, episode ->
                VideoPlayerViewModel.PlayableEpisode(
                    cid = episode.cid,
                    title = episode.title.ifBlank { "EP${index + 1}" },
                    panelTitle = episode.title.ifBlank { "EP${index + 1}" },
                    subtitle = episode.desc.ifBlank { episode.typeName },
                    cover = episode.coverUrl.ifBlank { detail.cover },
                    aid = episode.aid,
                    bvid = episode.bvid,
                    epId = episode.epid,
                    seasonId = episode.sid.takeIf { it > 0L } ?: seasonId,
                    source = VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES
                )
            }
    }

    fun resolvePgcEpisodeIndex(
        episodes: List<VideoPlayerViewModel.PlayableEpisode>,
        targetEpId: Long?,
        targetCid: Long,
        targetBvid: String?,
        fallbackIndex: Int
    ): Int {
        if (episodes.isEmpty()) {
            return 0
        }
        return episodes.indexOfFirst { episode ->
            (targetEpId != null && targetEpId > 0L && episode.epId == targetEpId) ||
                (targetCid > 0L && episode.cid == targetCid) ||
                (!targetBvid.isNullOrBlank() && episode.bvid == targetBvid)
        }.takeIf { it >= 0 }
            ?: fallbackIndex.takeIf { it in episodes.indices }
            ?: 0
    }

    fun buildPgcVideoDetail(
        detail: EpisodesDetailModel,
        selectedEpisode: VideoPlayerViewModel.PlayableEpisode?,
        fallbackAid: Long,
        fallbackBvid: String,
        fallbackCid: Long
    ): VideoDetailModel {
        return VideoDetailModel(
            view = com.tutu.myblbl.model.video.detail.VideoView(
                aid = selectedEpisode?.aid ?: fallbackAid,
                bvid = selectedEpisode?.bvid ?: fallbackBvid,
                cid = selectedEpisode?.cid ?: fallbackCid,
                pic = detail.cover.ifBlank { selectedEpisode?.cover.orEmpty() },
                title = detail.title.ifBlank {
                    detail.seasonTitle.ifBlank { selectedEpisode?.title.orEmpty() }
                },
                desc = detail.evaluate.ifBlank { detail.subtitle },
                pubDate = detail.episodes.orEmpty()
                    .firstOrNull { it.id == selectedEpisode?.epId }
                    ?.pubTime
                    ?: 0L,
                stat = detail.stat.toVideoStat()
            )
        )
    }

    private fun VideoPvModel.toPlayableEpisode(
        index: Int,
        view: com.tutu.myblbl.model.video.detail.VideoView
    ): VideoPlayerViewModel.PlayableEpisode {
        return VideoPlayerViewModel.PlayableEpisode(
            cid = cid,
            title = part.takeIf { it.isNotBlank() } ?: "P${page.takeIf { it > 0 } ?: index + 1}",
            panelTitle = part.takeIf { it.isNotBlank() } ?: "P${page.takeIf { it > 0 } ?: index + 1}",
            subtitle = "第 ${page.takeIf { it > 0 } ?: index + 1} P",
            cover = view.pic,
            aid = view.aid,
            bvid = view.bvid,
            source = VideoPlayerViewModel.EpisodeCatalogSource.PAGES
        )
    }

    private fun com.tutu.myblbl.model.video.detail.UgcEpisode.toPlayableEpisode(
        index: Int,
        fallbackView: com.tutu.myblbl.model.video.detail.VideoView
    ): VideoPlayerViewModel.PlayableEpisode {
        val displayPart = pageInfo?.part
            ?.takeIf { it.isNotBlank() }
            ?: pages.orEmpty().firstOrNull()?.part?.takeIf { it.isNotBlank() }
            ?: title.takeIf { it.isNotBlank() }
            ?: arc?.title?.takeIf { it.isNotBlank() }
            ?: "P${displayPage.takeIf { it > 0 } ?: index + 1}"
        return VideoPlayerViewModel.PlayableEpisode(
            cid = displayCid,
            title = title.takeIf { it.isNotBlank() }
                ?: arc?.title?.takeIf { it.isNotBlank() }
                ?: displayPart,
            panelTitle = displayPart,
            subtitle = "第 ${displayPage.takeIf { it > 0 } ?: index + 1} P",
            cover = displayCover.ifBlank { fallbackView.pic },
            aid = displayAid,
            bvid = displayBvid,
            source = VideoPlayerViewModel.EpisodeCatalogSource.UGC_SEASON
        )
    }

    private fun EpisodeModel.toPlayableEpisode(
        index: Int,
        seasonCover: String,
        seasonId: Long
    ): VideoPlayerViewModel.PlayableEpisode {
        val displayTitle = longTitle.takeIf { it.isNotBlank() }
            ?: title.takeIf { it.isNotBlank() }
            ?: "EP${index + 1}"
        val subTitle = buildList {
            title.takeIf { it.isNotBlank() && it != displayTitle }?.let(::add)
            subtitle.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
        return VideoPlayerViewModel.PlayableEpisode(
            cid = cid,
            title = displayTitle,
            panelTitle = buildString {
                append(index + 1)
                append('、')
                val badgeText = badgeInfo?.text?.takeIf { it.isNotBlank() } ?: badge
                if (badgeText.isNotBlank()) {
                    append('(')
                    append(badgeText)
                    append(')')
                }
                append(displayTitle)
            },
            subtitle = subTitle,
            cover = cover.ifBlank { seasonCover },
            aid = aid,
            bvid = bvid,
            epId = id,
            seasonId = seasonId,
            source = VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES
        )
    }

    private fun EpisodeStatModel?.toVideoStat(): com.tutu.myblbl.model.video.Stat? {
        val value = this ?: return null
        return com.tutu.myblbl.model.video.Stat(
            view = value.view.takeIf { it > 0L } ?: value.play,
            danmaku = value.danmaku,
            reply = value.reply,
            share = value.share,
            coin = value.coin
        )
    }
}
