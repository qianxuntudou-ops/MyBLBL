package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.common.CollectionResultModel
import com.tutu.myblbl.model.favorite.CheckFavoriteModel
import com.tutu.myblbl.model.favorite.FavoriteFolderDetailWrapper
import com.tutu.myblbl.model.favorite.FavoriteFolderModel
import com.tutu.myblbl.model.favorite.FavoriteFoldersWrapper
import com.tutu.myblbl.model.favorite.FolderDetailModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.session.NetworkSessionGateway

class FavoriteRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {

    suspend fun checkFavorite(aid: Long?): Result<BaseResponse<CheckFavoriteModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.checkFavorite(aid),
                source = "favorite.checkFavorite"
            )
        }

    suspend fun getFavoriteFolders(upMid: Long): Result<BaseResponse<FavoriteFoldersWrapper>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getFavoriteFolders(upMid),
                source = "favorite.getFavoriteFolders"
            )
        }

    suspend fun getFavoriteFolderInfo(mediaId: Long): Result<BaseResponse<FolderDetailModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getFavoriteFolderInfo(mediaId),
                source = "favorite.getFavoriteFolderInfo"
            )
        }

    suspend fun getFavoriteFolderDetail(
        mediaId: Long,
        page: Int,
        pageSize: Int
    ): Result<BaseResponse<FavoriteFolderDetailWrapper>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getFavoriteFolderDetail(mediaId, page, pageSize),
                source = "favorite.getFavoriteFolderDetail"
            )
        }

    suspend fun addFavorite(rid: Long, addMediaIds: String): Result<BaseResponse<CollectionResultModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.dealFavorite(
                rid = rid,
                addMediaIds = addMediaIds,
                delMediaIds = null,
                csrf = sessionGateway.getCsrfToken()
                ),
                source = "favorite.addFavorite"
            )
        }

    suspend fun removeFavorite(rid: Long, delMediaIds: String): Result<BaseResponse<CollectionResultModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.dealFavorite(
                rid = rid,
                addMediaIds = null,
                delMediaIds = delMediaIds,
                csrf = sessionGateway.getCsrfToken()
                ),
                source = "favorite.removeFavorite"
            )
        }
}
