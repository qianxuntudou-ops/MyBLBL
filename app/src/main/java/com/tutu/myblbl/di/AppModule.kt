package com.tutu.myblbl.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.repository.AllSeriesRepository
import com.tutu.myblbl.repository.AuthRepository
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.repository.HomeLaneRepository
import com.tutu.myblbl.repository.LiveRepository
import com.tutu.myblbl.repository.SearchRepository
import com.tutu.myblbl.repository.SeriesRepository
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.ui.fragment.main.category.CategoryViewModel
import com.tutu.myblbl.ui.fragment.main.dynamic.DynamicViewModel
import com.tutu.myblbl.ui.fragment.main.home.HotViewModel
import com.tutu.myblbl.ui.fragment.main.home.RecommendViewModel
import com.tutu.myblbl.ui.fragment.main.live.LiveListViewModel
import com.tutu.myblbl.ui.fragment.main.live.LiveRecommendViewModel
import com.tutu.myblbl.ui.fragment.main.live.LiveViewModel
import com.tutu.myblbl.ui.fragment.main.me.MeListViewModel
import com.tutu.myblbl.ui.fragment.main.me.MeViewModel
import com.tutu.myblbl.ui.fragment.main.search.SearchViewModel
import com.tutu.myblbl.ui.fragment.player.LivePlayerViewModel
import com.tutu.myblbl.ui.fragment.player.VideoPlayerViewModel
import com.tutu.myblbl.ui.fragment.series.SeriesDetailViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

@OptIn(UnstableApi::class)
val networkModule = module {
    single<ApiService> { NetworkManager.apiService }
}

@OptIn(UnstableApi::class)
val repositoryModule = module {
    single { com.tutu.myblbl.repository.remote.AllSeriesRepository() }
    single { com.tutu.myblbl.repository.remote.AuthRepository(get()) }
    single { com.tutu.myblbl.repository.remote.FavoriteRepository(get()) }
    single { com.tutu.myblbl.repository.remote.HomeLaneRepository(get(), get(), get()) }
    single { com.tutu.myblbl.repository.remote.LiveRepository() }
    single { com.tutu.myblbl.repository.remote.SearchRepository() }
    single { com.tutu.myblbl.repository.remote.SeriesRepository() }
    single { com.tutu.myblbl.repository.remote.VideoRepository(get()) }
    single { AllSeriesRepository(get()) }
    single { AuthRepository(get()) }
    single { FavoriteRepository(get()) }
    single { HomeLaneRepository(get()) }
    single { LiveRepository(get()) }
    single { SearchRepository(get()) }
    single { SeriesRepository(get()) }
    single { VideoRepository(get()) }
    single { UserRepository() }
}

@OptIn(UnstableApi::class)
val viewModelModule = module {
    viewModel { RecommendViewModel(get()) }
    viewModel { HotViewModel(get()) }
    viewModel { VideoPlayerViewModel(get(), androidContext()) }
    viewModel { CategoryViewModel(get()) }
    viewModel { DynamicViewModel(get()) }
    viewModel { LiveViewModel(get()) }
    viewModel { LiveListViewModel(get()) }
    viewModel { LiveRecommendViewModel(get()) }
    viewModel { MeListViewModel(get()) }
    viewModel { MeViewModel(get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { LivePlayerViewModel(get()) }
    viewModel { SeriesDetailViewModel(get()) }
}

val appModules = listOf(
    networkModule,
    repositoryModule,
    viewModelModule
)
