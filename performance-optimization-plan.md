# MyBili 性能优化执行计划

> 生成日期：2026-04-20
> 预计总工期：4-5 周

---

## 第一阶段：网络与数据层优化（第1周）

### 1.1 prewarmWebSession 请求并行化

- **文件**：`app/src/main/java/com/tutu/myblbl/network/security/BiliSecurityCoordinator.kt:91-125`
- **问题**：所有预热请求串行执行
- **方案**：使用 `coroutineScope + async` 并行发起独立请求
- **验证**：对比优化前后 prewarm 耗时日志
- **预期效果**：prewarm 速度提升 50-70%

### 1.2 OkHttp 连接池配置

- **文件**：`app/src/main/java/com/tutu/myblbl/network/http/NetworkClientFactory.kt`
- **问题**：未配置 ConnectionPool，连接复用效率低
- **方案**：添加 `ConnectionPool(5, 5, TimeUnit.MINUTES)`
- **验证**：抓包确认连接复用率
- **预期效果**：并发性能提升 20-30%

### 1.3 Cookie 刷新机制优化

- **文件**：`app/src/main/java/com/tutu/myblbl/network/security/BiliSecurityCoordinator.kt:383-480`
- **问题**：Cookie 刷新可能阻塞每次请求
- **方案**：后台定时刷新 + Mutex 防并发，请求时只读缓存
- **验证**：连续发起请求，确认无刷新阻塞
- **预期效果**：减少因刷新导致的延迟 50%

### 1.4 N+1 查询修复

- **文件**：`app/src/main/java/com/tutu/myblbl/repository/UserRepository.kt:219-250`
- **问题**：为每个视频单独请求详情
- **方案**：限制并发数（`Semaphore`）+ 添加详情缓存 + 批量查询
- **验证**：监控日志中请求数量，对比优化前后
- **预期效果**：减少网络请求 80%

### 1.5 WBI 签名 mixinKey 缓存

- **文件**：`app/src/main/java/com/tutu/myblbl/network/WbiGenerator.kt:41-50`
- **问题**：每次签名都重新计算 mixinKey
- **方案**：缓存 mixinKey，仅在 imgKey/subKey 变化时重算
- **验证**：多次调用签名方法，确认命中缓存
- **预期效果**：签名生成速度提升 40%

---

## 第二阶段：播放器与弹幕优化（第2周）

### 2.1 ExoPlayer 缓冲策略调优

- **文件**：`app/src/main/java/com/tutu/myblbl/feature/player/engine/ExoPlayerEngine.kt`
- **问题**：使用默认 90 秒缓冲，内存浪费
- **方案**：
  ```kotlin
  DefaultLoadControl.Builder()
      .setBufferForPlayback(2000)
      .setBufferForPlaybackAfterRebuffer(3000)
      .setTargetBufferBytes(10 * 1024 * 1024)
      .build()
  ```
- **验证**：弱网环境下测试播放流畅度，监控内存占用
- **预期效果**：内存占用减少 20-30%

### 2.2 弹幕数据上限与淘汰

- **文件**：`app/src/main/java/com/tutu/myblbl/feature/player/PlayerActivity.kt:263-264`
- **问题**：弹幕数据无限累积
- **方案**：已播放片段的弹幕定时清理，保留当前+前后各1个片段
- **验证**：长时间播放（>1小时），监控内存曲线
- **预期效果**：减少内存占用 40%

### 2.3 弹幕缓存池动态调整

- **文件**：`app/src/main/java/com/tutu/myblbl/feature/player/danmaku/CacheManager.kt:51-52`
- **问题**：缓存池固定 50MB/72个
- **方案**：根据屏幕分辨率和弹幕密度动态调整，增加单次释放数量到 48
- **验证**：高弹幕密度视频测试，确认无 OOM
- **预期效果**：峰值内存减少 50%

### 2.4 播放器资源提前释放

- **文件**：`app/src/main/java/com/tutu/myblbl/feature/player/PlayerActivity.kt`
- **问题**：资源在 onDestroy 才释放
- **方案**：onStop 中释放解码器等重资源，onDestroy 做最终兜底
- **验证**：退出播放器后 Profiler 确认内存回收
- **预期效果**：减少 80% 内存泄漏风险

---

## 第三阶段：UI 与列表优化（第3周）

### 3.1 VideoAdapter diff 计算移至后台

- **文件**：`app/src/main/java/com/tutu/myblbl/ui/adapter/VideoAdapter.kt:131`
- **问题**：DiffUtil 在主线程计算，大数据集卡顿
- **方案**：使用 `DiffUtil.calculateDiff` 在 `Dispatchers.Default` 上执行
- **验证**：1000+ 条数据列表，Systrace 确认无卡顿帧
- **预期效果**：列表流畅度提升 50%

### 3.2 Glide 内存缓存动态调整

- **文件**：`app/src/main/java/com/tutu/myblbl/core/ui/image/MyBLBLGlideModule.kt:21`
- **问题**：固定 256MB 缓存
- **方案**：根据 `ActivityManager.getMemoryClass()` 动态设置为可用内存的 15%
- **验证**：低端设备测试无 OOM
- **预期效果**：减少内存占用 100-150MB

### 3.3 搜索输入防抖

- **文件**：`app/src/main/java/com/tutu/myblbl/feature/search/SearchNewFragment.kt:177-220`
- **问题**：每次输入变化都发起请求
- **方案**：`flow.debounce(300)` 或自定义 DebounceHelper
- **验证**：快速输入测试，确认只在停止后发一次请求
- **预期效果**：减少无效请求 90%

### 3.4 ViewPager2 offscreenPageLimit 调整

- **文件**：HomeFragment、SearchNewFragment 等
- **问题**：offscreenPageLimit=1 导致频繁重建
- **方案**：根据 Tab 数量调整为 2，或按需预加载
- **验证**：Tab 切换测试，确认无卡顿
- **预期效果**：切换流畅度提升 30-40%

### 3.5 ViewPager2 回调泄漏修复

- **文件**：CategoryFragment、LiveFragment 等
- **问题**：注册回调未在 onDestroyView 注销
- **方案**：onDestroyView 中 `unregisterOnPageChangeCallback` + `removeOnTabSelectedListener`
- **验证**：LeakCanary 确认无泄漏
- **预期效果**：消除内存泄漏

---

## 第四阶段：核心框架与数据层深度优化（第4周）

### 4.1 FileCacheManager 并发优化

- **文件**：`app/src/main/java/com/tutu/myblbl/core/common/cache/FileCacheManager.kt:152`
- **问题**：synchronizedMap + 全遍历淘汰
- **方案**：改用 `LinkedHashMap` 实现 LRU + 文件 IO 移至 `Dispatchers.IO`
- **验证**：并发读写压力测试
- **预期效果**：缓存操作性能提升 60%

### 4.2 VideoModel computed properties 缓存

- **文件**：`app/src/main/java/com/tutu/myblbl/model/video/VideoModel.kt:157-231`
- **问题**：isChargingExclusive、playbackSeasonId 等每次重新计算
- **方案**：使用 `lazy` 或在对象创建时预计算，缓存解析结果
- **验证**：列表滚动 Profiler 对比 CPU 使用
- **预期效果**：CPU 使用降低 50%

### 4.3 DataStore 读取优化

- **文件**：`app/src/main/java/com/tutu/myblbl/core/common/settings/AppSettingsDataStore.kt:55`
- **问题**：每次 `data.first()` 触发完整读取
- **方案**：启动时预加载到内存缓存，后续读缓存，写时双写
- **验证**：高频读取场景性能对比
- **预期效果**：读取响应速度提升 40%

### 4.4 watchLaterCache 并发安全

- **文件**：`app/src/main/java/com/tutu/myblbl/repository/remote/VideoRepository.kt:26-49`
- **问题**：缓存字段无 volatile，多线程可见性不保证
- **方案**：添加 `@Volatile` 或改用 `AtomicReference`
- **验证**：并发读写测试
- **预期效果**：消除潜在的数据竞争

### 4.5 Dynamic ViewModel 数据清理

- **文件**：`app/src/main/java/com/tutu/myblbl/feature/dynamic/DynamicViewModel.kt`
- **问题**：大列表在 ViewModel 中不释放
- **方案**：onCleared 中清空列表，或使用 Paging 3
- **验证**：LeakCanary + 内存 Profiler
- **预期效果**：内存占用减少 20-30%

---

## 第五阶段：收尾与微优化（第5周）

### 5.1 Cookie 过期清理优化

- **文件**：`app/src/main/java/com/tutu/myblbl/network/cookie/CookieManager.kt:268-281`
- **方案**：改为定时清理（每5分钟），而非每次请求都遍历

### 5.2 Koin 初始化异步化

- **文件**：`app/src/main/java/com/tutu/myblbl/di/AppModule.kt:46`
- **方案**：非核心模块延迟加载

### 5.3 Series 模块引入 DiffUtil

- **文件**：`app/src/main/java/com/tutu/myblbl/feature/series/AllSeriesFragment.kt`
- **方案**：替换 notifyDataSetChanged 为 DiffUtil

### 5.4 Gson 单例化与配置优化

- **文件**：`NetworkClientFactory.kt:60-67`、`FileCacheManager.kt:36`
- **方案**：统一 Gson 实例，复用 TypeAdapters

### 5.5 搜索结果 HTML 解析缓存

- **文件**：`app/src/main/java/com/tutu/myblbl/repository/remote/SearchRepository.kt:196-206`
- **方案**：缓存 `HtmlCompat.fromHtml` 结果

---

## 验收标准

| 指标 | 优化前基线 | 目标值 |
|------|-----------|--------|
| 冷启动时间 | 待测量 | 减少 200-300ms |
| 内存峰值 | 待测量 | 减少 200-300MB |
| 列表滚动掉帧率 | 待测量 | 降低 50% |
| 播放器内存占用 | 待测量 | 减少 40% |
| 首页加载完成时间 | 待测量 | 减少 30% |

> 建议在优化前先用 Android Profiler 建立基线数据，每阶段完成后对比测量。
