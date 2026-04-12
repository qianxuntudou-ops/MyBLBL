# 依赖升级后可利用的新特性清单

> 基于当前依赖版本，梳理已引入但未使用的新特性，按实用价值排序。

---

## 高价值（建议采用）

### 1. Media3 1.9.3 — 移除未使用的依赖

| 依赖 | 状态 | 建议 |
|------|------|------|
| `media3-session` | 已引入，零代码引用 | 移除依赖以减小 APK 体积，或用于实现后台播放通知 |
| `media3-exoplayer-dash` | 已引入，无 `DashMediaSource` 使用 | 可移除；项目将 DASH 流拆分为独立 progressive URL |

### 2. Media3 — `PreloadMediaSource` 预加载

| 项目 | 说明 |
|------|------|
| 当前状态 | 无预加载，视频切换时从头缓冲 |
| 新特性 | `PreloadMediaSource` 可在用户浏览时提前缓冲下一个视频 |
| 收益 | 显著减少视频起播时间 |
| 适用场景 | 番剧选集、历史记录连续播放 |

### 3. OkHttp 4.12.0 — `okhttp3.Cache` HTTP 缓存

| 项目 | 说明 |
|------|------|
| 当前状态 | 未配置任何 HTTP 缓存，重复请求相同 URL 每次都走网络 |
| 新特性 | `okhttp3.Cache` 可缓存 HTTP 响应（按 Cache-Control 头） |
| 收益 | 减少重复网络请求，加快页面加载，节省流量 |
| 适用场景 | 番剧详情、用户信息等低频变更接口 |

### 4. OkHttp 4.12.0 — `EventListener` 网络监控

| 项目 | 说明 |
|------|------|
| 当前状态 | 仅使用 `HttpLoggingInterceptor` 做日志 |
| 新特性 | `EventListener` 可监控 DNS 解析、连接建立、请求耗时等全生命周期 |
| 收益 | 精确诊断网络性能瓶颈，CDN 切换效果量化 |

### 5. Glide 4.16.0 — `AppGlideModule` 全局配置

| 项目 | 说明 |
|------|------|
| 当前状态 | 无 `AppGlideModule`，完全使用默认配置 |
| 新特性 | 自定义内存缓存大小、磁盘缓存位置/大小、线程池、解码格式 |
| 收益 | TV 设备上可增大缓存，提升图片加载体验；控制磁盘占用 |

### 6. Lifecycle 2.8.7 — `SavedStateHandle` 状态恢复

| 项目 | 说明 |
|------|------|
| 当前状态 | ViewModel 无 `SavedStateHandle`，进程被杀后状态丢失 |
| 新特性 | `createSavedStateHandle()` 可在 ViewModel 中恢复关键状态 |
| 收益 | 播放进度、当前页面等状态在进程重建后可恢复 |
| 适用场景 | `VideoPlayerViewModel`（播放进度、当前视频信息） |

---

## 中等价值（按需采用）

### 7. Coroutines 1.10.2 — `flowOn` 操作符

| 项目 | 说明 |
|------|------|
| 当前状态 | Flow 上下文切换使用 `withContext`，未使用 `flowOn` |
| 新特性 | `flowOn(Dispatchers.IO)` 在 Flow 构建端自动切换调度器 |
| 收益 | 更优雅的 Flow 管道，调用端无需关心调度器 |

### 8. Coroutines 1.10.2 — `stateIn` / `shareIn`

| 项目 | 说明 |
|------|------|
| 当前状态 | `VideoPlayerViewModel` 仍大量使用 `LiveData`，其他 ViewModel 用 `StateFlow` |
| 新特性 | `stateIn()` 将冷流转为热 StateFlow，`shareIn()` 转为 SharedFlow |
| 收益 | 统一数据流范式，多个观察者共享同一数据源 |
| 适用场景 | 将 `VideoPlayerViewModel` 的 LiveData 迁移到 StateFlow |

### 9. Kotlin 2.1.0 — `value class` 类型安全

| 项目 | 说明 |
|------|------|
| 当前状态 | 所有 ID 均为裸 `Long` / `String`，容易混淆 |
| 新特性 | `@JvmInline value class Aid(val value: Long)` 零开销包装 |
| 收益 | 编译期区分 `Aid`/`Cid`/`Mid` 等同类型 ID，避免传参错误 |
| 示例 | `fun getVideoDetail(aid: Aid, bvid: Bvid?)` 代替 `fun getVideoDetail(aid: Long?, bvid: String?)` |

### 10. Kotlin 2.1.0 — Context Parameters（上下文参数）

| 项目 | 说明 |
|------|------|
| 当前状态 | 15+ 文件使用 `GlobalContext.get().get<T>()` 反模式获取依赖 |
| 新特性 | `context(dataStore: AppSettingsDataStore)` 声明式依赖传递 |
| 收益 | 消除全局状态访问，提升可测试性 |
| 适用场景 | `Extensions.kt` 中的设置读取函数 |

### 11. Activity 1.9.3 — 预测性返回手势

| 项目 | 说明 |
|------|------|
| 当前状态 | `AndroidManifest.xml` 已声明 `enableOnBackInvokedCallback="true"` 但无实际适配 |
| 新特性 | Android 14+ 可预览返回目标页面动画 |
| 收益 | 更现代的导航体验 |
| 前置条件 | 需要 `targetSdk 35`（已满足） |

### 12. Fragment 1.8.6 — `FragmentResult` API

| 项目 | 说明 |
|------|------|
| 当前状态 | Fragment 间通信通过 `AppEventHub`（SharedFlow 事件总线） |
| 新特性 | `setFragmentResult()` / `getResult()` 类型安全的父子 Fragment 通信 |
| 收益 | 替代部分事件总线场景，生命周期安全 |
| 适用场景 | 设置页修改配置后通知主页刷新 |

---

## 低价值（了解即可）

### 13. RecyclerView 1.4.0 — `RecycledViewPool` 共享

| 项目 | 说明 |
|------|------|
| 当前状态 | 每个 RecyclerView 使用独立默认池 |
| 新特性 | 多个 RecyclerView 共享 `RecycledViewPool` |
| 收益 | 减少ViewHolder 创建开销 |
| 适用场景 | Tab 切换时多个列表页共享 ViewHolder 池 |

### 14. OkHttp — DNS over HTTPS

| 项目 | 说明 |
|------|------|
| 当前状态 | 使用系统默认 DNS |
| 新特性 | `DnsOverHttps` 防止 DNS 劫持 |
| 收益 | 提升安全性 |
| 注意 | 需要可信 DNS 服务器（如 Google/Cloudflare） |

### 15. Glide — `.override()` 尺寸限制

| 项目 | 说明 |
|------|------|
| 当前状态 | 所有图片按目标 View 尺寸加载 |
| 新特性 | `.override(width, height)` 强制指定解码尺寸 |
| 收益 | TV 首页缩略图可用小尺寸解码，减少内存占用 |

### 16. Koin 3.5.3 — Scope 机制

| 项目 | 说明 |
|------|------|
| 当前状态 | 所有依赖为 `single` 或 `viewModel` |
| 新特性 | `scope { }` 创建短生命周期依赖（如 Fragment 级 Repository） |
| 收益 | 更精细的依赖生命周期控制 |
| 注意 | 当前项目规模不需要 |

---

## 建议执行顺序

```
Phase 1（立即可做）
├── 移除 media3-session、media3-exoplayer-dash 未使用依赖
├── 配置 Glide AppGlideModule（调大 TV 端缓存）
└── 配置 OkHttp Cache（HTTP 响应缓存）

Phase 2（中期优化）
├── SavedStateHandle 接入 VideoPlayerViewModel
├── VideoPlayerViewModel LiveData → StateFlow 统一
└── PreloadMediaSource 预加载

Phase 3（长期改进）
├── value class 类型安全 ID
├── context parameters 替代 GlobalContext
└── FragmentResult 替代部分事件总线
```
