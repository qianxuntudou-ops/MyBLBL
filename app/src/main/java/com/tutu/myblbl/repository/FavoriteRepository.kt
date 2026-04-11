package com.tutu.myblbl.repository

import com.tutu.myblbl.repository.remote.FavoriteRepository as NetworkFavoriteRepository

class FavoriteRepository(
    private val delegate: NetworkFavoriteRepository
) {
    suspend fun checkFavorite(aid: Long?) = delegate.checkFavorite(aid)

    suspend fun getFavoriteFolders(upMid: Long) = delegate.getFavoriteFolders(upMid)

    suspend fun getFavoriteFolderInfo(mediaId: Long) = delegate.getFavoriteFolderInfo(mediaId)

    suspend fun getFavoriteFolderDetail(mediaId: Long, page: Int, pageSize: Int) =
        delegate.getFavoriteFolderDetail(mediaId, page, pageSize)

    suspend fun addFavorite(rid: Long, addMediaIds: String) = delegate.addFavorite(rid, addMediaIds)

    suspend fun removeFavorite(rid: Long, delMediaIds: String) = delegate.removeFavorite(rid, delMediaIds)
}
