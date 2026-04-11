package com.tutu.myblbl.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.security.NetworkManagerSecurityGateway
import com.tutu.myblbl.network.security.NetworkManagerWebGateway
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.security.NetworkWebGateway
import com.tutu.myblbl.network.session.NetworkManagerSessionGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.AllSeriesRepository
import com.tutu.myblbl.repository.AuthRepository
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.repository.HomeLaneRepository
import com.tutu.myblbl.repository.LiveRepository
import com.tutu.myblbl.repository.SearchRepository
import com.tutu.myblbl.repository.SeriesRepository
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.feature.category.CategoryViewModel
import com.tutu.myblbl.feature.dynamic.DynamicViewModel
import com.tutu.myblbl.feature.home.HotViewModel
import com.tutu.myblbl.feature.home.RecommendViewModel
import com.tutu.myblbl.feature.live.LiveListViewModel
import com.tutu.myblbl.feature.live.LiveRecommendViewModel
import com.tutu.myblbl.feature.live.LiveViewModel
import com.tutu.myblbl.feature.me.MeListViewModel
import com.tutu.myblbl.feature.me.MeViewModel
import com.tutu.myblbl.feature.search.SearchViewModel
import com.tutu.myblbl.feature.player.LivePlayerViewModel
import com.tutu.myblbl.feature.player.VideoPlayerViewModel
import com.tutu.myblbl.ui.fragment.series.SeriesDetailViewModel
import com.tutu.myblbl.utils.CookieManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

@OptIn(UnstableApi::class)
val networkModule = module {
    single<ApiService> { NetworkManager.apiService }
    single<OkHttpClient> { NetworkManager.getOkHttpClient() }
    single<CookieManager> { NetworkManager.getCookieManager() }
    single<NetworkSessionGateway> { NetworkManagerSessionGateway() }
    single<NetworkSecurityGateway> { NetworkManagerSecurityGateway() }
    single<NetworkWebGateway> { NetworkManagerWebGateway() }
}

@OptIn(UnstableApi::class)
val repositoryModule = module {
    single { com.tutu.myblbl.repository.remote.AllSeriesRepository(get()) }
    single { com.tutu.myblbl.repository.remote.AuthRepository(get(), get()) }
    single { com.tutu.myblbl.repository.remote.FavoriteRepository(get(), get()) }
    single { com.tutu.myblbl.repository.remote.HomeLaneRepository(get(), get(), get(), get()) }
    single { com.tutu.myblbl.repository.remote.LiveRepository(get(), get()) }
    single { com.tutu.myblbl.repository.remote.SearchRepository(get(), get()) }
    single { com.tutu.myblbl.repository.remote.SeriesRepository(get(), get()) }
    single { com.tutu.myblbl.repository.remote.UserRepository(get(), get()) }
    single { com.tutu.myblbl.repository.remote.VideoRepository(get(), get(), get(), get()) }
    single { AllSeriesRepository(get()) }
    single { AuthRepository(get()) }
    single { FavoriteRepository(get()) }
    single { HomeLaneRepository(get()) }
    single { LiveRepository(get()) }
    single { SearchRepository(get()) }
    single { SeriesRepository(get()) }
    single { VideoRepository(get(), get()) }
    single { UserRepository(get(), get()) }
}

@OptIn(UnstableApi::class)
val viewModelModule = module {
    viewModel { RecommendViewModel(get()) }
    viewModel { HotViewModel(get()) }
    viewModel { VideoPlayerViewModel(get(), get(), get(), get(), get(), androidContext()) }
    viewModel { CategoryViewModel(get()) }
    viewModel { DynamicViewModel(get()) }
    viewModel { LiveViewModel(get()) }
    viewModel { LiveListViewModel(get()) }
    viewModel { LiveRecommendViewModel(get()) }
    viewModel { MeListViewModel(get()) }
    viewModel { MeViewModel(get(), get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { LivePlayerViewModel(get()) }
    viewModel { SeriesDetailViewModel(get(), get()) }
}

val eventModule = module {
    single { AppEventHub() }
}

val appModules = listOf(
    networkModule,
    repositoryModule,
    eventModule,
    viewModelModule
)
