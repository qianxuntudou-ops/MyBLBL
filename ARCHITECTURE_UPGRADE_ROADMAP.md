# MyBLBL 架构升级后续路线图

## 文档目的

这份文档用于整理 `MyBLBL` 后续所有建议推进的架构升级工作，作为后续开发、拆分 PR、验收阶段成果的统一参考。

当前原则：

- 先做低风险、可回归、可分批提交的治理工作
- 先统一依赖边界，再做模块边界
- 先减少隐式依赖，再考虑多模块
- 不为了“架构好看”而做高风险重构

## 当前状态

本轮已完成的内容：

- 补齐 `Koin` 依赖注入模块，统一了部分缺失的 repository / viewModel 装配
- 移除了 UI 层直接 `new Repository()` 的主要入口
- 将 `SeriesDetailViewModel`、`LivePlayerViewModel` 切到统一注入链路
- 将 repository wrapper 层改为显式接收 delegate，不再默认内部直接 new remote repository
- 引入 `MainNavigationViewModel`，将主 Tab / 子 Tab / 返回 / 菜单等高频导航事件迁出字符串广播
- 引入 `AppEventHub`，将登录态刷新与播放进度同步改为显式 typed event
- 已移除项目中的 `EventBus` 代码依赖与 Gradle 依赖
- 将 `NetworkManager` 重构为兼容 facade，并拆出：
  - `network/session/NetworkSessionStore`
  - `network/ua/DesktopUserAgentStore`
  - `network/http/NetworkClientFactory`
  - `network/security/BiliSecurityCoordinator`
- 当前 `NetworkManager` 已从实现堆叠型大类压缩为以委托为主的统一入口
- 引入 `NetworkSessionGateway` / `NetworkSecurityGateway`，开始把 repository 层对 `NetworkManager` 的静态访问改为显式依赖
- `SearchRepository`、`HomeLaneRepository`、`SeriesRepository`、`FavoriteRepository`、`UserRepository`、`VideoRepository` 等已改为通过构造器声明 session / security / http 依赖
- `ChannelVideoFragment` 已不再直接访问 `NetworkManager.apiService`，改为通过 `VideoRepository` 获取频道视频分页数据
- `MeViewModel`、`SeriesDetailViewModel`、`UserInfoDialog`、`OwnerDetailDialog` 已开始消费 `NetworkSessionGateway`，UI 层登录态读取不再全部直连 `NetworkManager`
- `MainActivity`、`PlayerActionDialog`、`UserSpaceFragment`、`VideoDetailFragment`、`Favorite*`、`DynamicFragment`、`MeFragment`、`MeSeriesFragment`、`SignInFragment` 等界面入口已完成一轮 session/cookie 依赖收口
- 播放器与风控链路已开始消费显式 gateway / runtime 依赖：
  - `GaiaVgateActivity` 改为通过 `NetworkWebGateway` / `NetworkSessionGateway`
  - `LivePlayerFragment` 改为直接注入 `OkHttpClient`
  - `VideoPlayerViewModel` / `VideoPlayerPlayInfoGateway` 已不再直接依赖 `NetworkManager`
- 修复了本轮重构中暴露出的编译问题和测试构造器适配问题
- 已通过：
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`

## 总体目标

后续架构升级的目标不是“把项目改成教科书式 Clean Architecture”，而是解决下面这些实际问题：

- 降低页面和数据层之间的隐式耦合
- 让依赖关系可追踪、可替换、可测试
- 逐步替换字符串式全局事件通信
- 拆解 `NetworkManager` 这种职责过重的单点
- 让包结构更贴近 feature，而不是只按技术分层
- 为未来是否拆多模块保留空间

## 不建议现在立刻做的事

- 不建议马上全项目切成多模块
- 不建议一次性全量引入 use case / domain 层
- 不建议大规模改 UI 逻辑和业务逻辑混在同一个 PR 里
- 不建议一口气移除全部 `EventBus`
- 不建议在没有测试护栏的前提下大拆播放器链路

## 推荐推进顺序

建议按下面顺序推进：

1. `EventBus` 收口与替换
2. `NetworkManager` 拆职责
3. feature-first 包结构调整
4. 测试补强
5. 构建与模块化评估

---

## 阶段 2：EventBus 收口

### 目标

减少字符串事件广播带来的隐式耦合、难追踪、难重构问题。

### 为什么先做这个

当前 `EventBus` 的问题比“是否多模块”更急：

- 依赖是隐式的
- 事件名是字符串，改名风险高
- 页面跳转、刷新、Tab 联动逻辑分散
- 调试时很难定位谁发了事件、谁收了事件

### 需要做的事

- [x] 盘点所有 `EventBus` 事件名，建立事件清单
- [x] 区分三类事件：
  - 全局导航事件
  - 同页面 / 同容器协调事件
  - 一次性动作回调事件
- [x] 为 `MainActivity` 引入共享的 `MainSharedViewModel`
- [x] 用 `SharedFlow` / `StateFlow` 替换首页 Tab、我的页 Tab、返回刷新等高频事件
- [x] 优先迁移 `HomeFragment`、`HomeLaneFragment`、`MeFragment`、`MeSeriesFragment`
- [x] 用 `AppEventHub` 承接登录态刷新与播放器进度同步
- [x] 删除已迁移链路上的 `EventBus.register/unregister/post`

### 推荐产出

- `ui/main/MainSharedViewModel.kt`
- `ui/main/MainEvent.kt`
- `event/AppEventHub.kt`
- 事件迁移说明文档

### 验收标准

- 首页主 Tab 和子 Tab 行为与当前保持一致
- “点击当前 Tab 刷新”“菜单键刷新”“返回后焦点恢复”等行为无回归
- 已迁移页面不再依赖 `EventBus`
- 至少为共享 ViewModel / AppEventHub 的关键事件流补充测试或行为验证说明

---

## 阶段 3：拆解 NetworkManager

### 目标

把当前过重的 `NetworkManager` 拆成清晰职责的服务对象，降低单点膨胀风险。

### 当前问题

`NetworkManager` 目前承担了多种职责：

- API / OkHttp / Retrofit 初始化
- Cookie 管理
- 登录态同步
- UserAgent 管理
- WBI / Web 风控辅助
- 预热逻辑
- 鉴权失败处理

这会带来：

- 难测
- 难替换
- 修改一处容易影响全局
- 代码继续膨胀后会越来越不敢动

### 需要做的事

- [x] 拆出 `SessionManager`
- [x] 拆出 `AuthStateSynchronizer`
- [x] 拆出 `UserAgentProvider`
- [x] 拆出 `BiliSecurityService` 或等价命名组件
- [x] 拆出 `NetworkClientFactory`
- [x] 保留一个轻量 facade，避免一次性改太多调用点
- [x] 冻结 `NetworkManager` 的职责边界，不再给它增加新的业务职责
- [x] 将 repository 层对 `NetworkManager` 的静态访问收口到更明确的 provider / gateway
- [ ] 将剩余业务代码对 `NetworkManager` 的直接依赖继续收口到更明确的 provider / gateway

### 推荐拆分方向

- `network/session/SessionManager`
- `network/auth/AuthStateSynchronizer`
- `network/http/NetworkClientFactory`
- `network/security/BiliWebSecurityService`
- `network/ua/UserAgentProvider`

### 验收标准

- `NetworkManager` 行数和职责明显下降
- repository 不再直接依赖过多 static 全局入口
- 登录态失效、cookie 预热、播放前健康检查行为不回退

### 当前阶段结论

这一阶段已经完成第一轮目标：

- `NetworkManager` 仍保留兼容 API，避免大面积改调用点
- 实现细节已迁出到 `session / ua / http / security` 子职责
- 已补基础单测覆盖 `NetworkSessionStore`

下一步不再建议继续往 `NetworkManager` 本体加代码，而应开始逐步减少业务代码对这个 facade 的静态依赖

本轮新增结论：

- repository 层已经不再直接依赖 `NetworkManager` 的登录态 / WBI / 预热等静态入口，而是通过显式注入的 gateway 协作
- UI 与播放器主链路已经开始消费同一套 gateway / runtime 依赖
- 剩余 `NetworkManager` 静态访问已主要收缩到应用启动根节点、gateway 实现本身，以及少量不走 Koin 生命周期的 helper / view 工具

### 当前判断

这里的“限制 `NetworkManager`”不是指把它从项目里彻底消灭，而是指冻结它的职责边界，防止它重新膨胀成新的全局大类。

当前建议执行口径：

- 允许保留的入口：
  - 应用启动根节点，例如 `Application` 初始化
  - DI 装配层暴露 `ApiService` / `OkHttpClient` / `CookieManager`
  - `Network*Gateway` 的兼容实现内部委托给 `NetworkManager`
- 需要继续避免的用法：
  - 新业务逻辑直接往 `NetworkManager` 增加方法
  - 新页面 / 新仓库 / 新播放器流程直接依赖 `NetworkManager`
  - 为了图省事把临时逻辑堆进 `NetworkManager`
- 暂不要求立刻清零的区域：
  - 少量不走 Koin 生命周期的 helper / custom view / 工具对象
  - 这些区域应保持收敛，等真正膨胀时再专项治理

也就是说，后续限制的重点不是“禁用 `NetworkManager`”，而是“禁止它继续扩散职责和继续向业务代码渗透”。

---

## 阶段 4：Feature-first 包结构调整

### 目标

让目录结构围绕业务 feature 组织，而不是只围绕技术类型组织。

### 建议结构

建议逐步向下面的结构靠拢：

```text
app/src/main/java/com/tutu/myblbl/
  core/
    network/
    session/
    ui/
    common/
  feature/
    home/
    player/
    detail/
    series/
    favorite/
    user/
    search/
    settings/
```

### 需要做的事

- [x] 新代码优先放到 `feature/*` 下
- [x] 将 `ui/fragment/main/home` 相关逻辑逐步迁到 `feature/home`
- [x] 将播放器相关逻辑收拢到 `feature/player`
- [ ] 将详情、追番、收藏、用户空间等按 feature 收拢
- [ ] 将 `ui/base`、通用焦点恢复、通用视图能力放入 `core/ui`
- [ ] 将共用 model 中真正跨 feature 的部分保留在 `core/common` 或 `core/model`

### 执行方式

- 每次只迁一个 feature
- 迁目录时不要顺手改行为
- 迁移 PR 与功能 PR 分离

### 验收标准

- 新增功能不再往“巨型 ui 包”里继续堆
- 首页、播放器、详情页的代码归属更清晰
- feature 的入口、状态、数据依赖更容易追踪

### 当前进展

- `home` 相关 Fragment / ViewModel / adapter 已迁入 `feature/home`
- 播放器相关 Fragment、ViewModel、协同对象、自定义 View 已迁入 `feature/player`
- `live` 相关 Fragment / ViewModel / adapter 已迁入 `feature/live`
- `search` 相关 Fragment / ViewModel / adapter / focus 协调对象已迁入 `feature/search`
- `me` 相关 Fragment / ViewModel / adapter / ui state 已迁入 `feature/me`
- 关联的 XML 自定义 View 全限定类名、baseline profile、测试包引用已同步到新的 feature 包名
- 主导航入口里剩余的 `main/category`、`main/dynamic`、`main/settings` 体量较小，可以按后续需求顺手迁移
- `detail / favorite / user / series` 仍值得做，但更适合作为后续按需求逐步推进的批次，而不是马上全量平移

---

## 阶段 5：测试补强

### 目标

为后续继续重构提供护栏。

### 当前重点

优先补最容易回归、最难人工全覆盖的链路。

### 需要做的事

- [ ] 为首页 lane 合并与缓存逻辑补测试
- [ ] 为播放器选流、清晰度回退、音视频轨选择补测试
- [ ] 为登录态失效和鉴权失败同步补测试
- [ ] 为共享 ViewModel 事件迁移后的逻辑补测试
- [ ] 为 repository wrapper 与 remote repository 的边界补基础测试

### 测试优先级

- P0：播放器主链路
- P0：首页数据合并与展示入口
- P1：登录态、用户信息、收藏、追番
- P1：搜索与分页

### 验收标准

- 关键重构点至少有一层自动化校验
- 后续改动不再只靠人工回归

---

## 阶段 6：代码规范与防回退约束

### 目标

避免代码继续向旧模式回退。

### 需要做的事

- [x] 在 README 或独立开发文档中新增架构约束说明
- [x] 明确禁止 UI 直接 new repository
- [x] 明确新页面优先使用 injected ViewModel / repository
- [x] 明确新功能优先进入 feature 目录
- [x] 新增 review checklist
- [ ] 评估并补充 lint 规则
- [x] PR 模板中增加“是否新增 EventBus 依赖”的检查项

### 建议规则

- UI 不直接依赖 remote repository
- 非必要不新增全局单例
- 事件通信优先使用显式 flow / callback
- 大于一定规模的逻辑不直接堆在 Fragment / Activity 中
- `NetworkManager` 只作为 runtime facade / bootstrap 边界存在，不再承接新的业务职责
- 业务代码默认通过 injected `ViewModel` / `repository` / gateway / `ApiService` / `OkHttpClient` 协作
- 启动层、DI 层、gateway 实现可以作为 `NetworkManager` 的保留例外

### 当前进展

- `README.md` 已补充长期架构约束，作为后续开发默认遵循的入口说明
- 文档层面已经明确：UI 不直接 `new Repository()`，新页面优先走 injected `ViewModel` / `repository`
- 文档层面已经明确：新功能优先进入 `feature/*`，`NetworkManager` 采用“冻结边界、避免扩散”策略
- 已新增 `docs/ARCHITECTURE_REVIEW_CHECKLIST.md`，作为 review 阶段的默认检查清单
- 已新增 PR 模板检查项，显式约束 `EventBus` 与 `NetworkManager` 不再回流

---

## 阶段 7：评估是否拆多模块

### 目标

不是“为了高级而多模块”，而是在时机合适时才做。

### 何时值得做

出现以下信号时再进入多模块评估：

- 多人并行开发频繁互相踩代码
- 构建速度明显影响开发效率
- feature 之间边界已经较清晰
- 测试与依赖装配已经基本可控

### 推荐模块候选

如果未来真的要拆，优先考虑：

- `:core:network`
- `:core:ui`
- `:feature:home`
- `:feature:player`
- `:feature:series`

### 不建议的拆法

- 不建议先按“repository / ui / model”生硬切模块
- 不建议在 `EventBus` 和全局单例还很多时就拆

---

## 推荐 PR 拆分方式

后续每轮重构建议按以下粒度拆 PR：

1. 纯依赖装配调整
2. 单个 feature 的事件迁移
3. 单个服务对象从 `NetworkManager` 拆出
4. 单个 feature 的目录迁移
5. 单个重构点的测试补强

每个 PR 尽量满足：

- 目标单一
- 可单独回滚
- 有清晰验证方式

## 建议的近期执行清单

建议下一轮直接做下面这些：

- [x] 建立事件迁移状态文档
- [x] 为 `MainActivity` 增加共享 `MainSharedViewModel`
- [x] 迁移 `Home` / `Me` / `Category` / `Dynamic` / `Live` 的导航事件
- [x] 把 `NetworkManager` 中 session / auth / ua / security 主职责完成首轮拆分
- [x] 将 repository 层的 `NetworkManager` 静态依赖收口为显式注入 gateway
- [x] 清理一个 UI 层直连 `apiService` 的入口（`ChannelVideoFragment`）
- [x] 将大部分 UI 登录态 / 当前用户 / cookie 写入逻辑迁移到 injected gateway 或 runtime dependency
- [x] 将播放器与 Gaia 风控链路中的核心 `NetworkManager` 静态依赖迁移到 injected gateway / `OkHttpClient` / `CookieManager`
- [ ] 为首页与播放器链路补更完整的自动化测试
- [ ] 评估新的 `AppEventHub` / `MainNavigationViewModel` / `Network*Gateway` 组件最终目录归属

## 最终判断

这次架构升级应采用“持续治理”的方式推进，而不是一次性推翻重写。

当前最优策略：

- 继续渐进式重构
- 每次只解决一类架构问题
- 优先处理隐式依赖和单点膨胀
- 等边界清晰后，再决定是否多模块
