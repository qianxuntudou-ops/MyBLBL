# MyBili

📺 一个专为 Android TV 设计的第三方 Bilibili 客户端应用,像素级对齐BLBL。

## ✨ 主要功能

- 🎬 **视频播放** - 支持番剧、电影、电视剧、UGC 视频等多种内容类型
- 📡 **直播观看** - 支持 Bilibili 直播内容播放
- 💬 **弹幕引擎** - 集成 AkDanmaku 高性能弹幕引擎，支持弹幕渲染与智能过滤
- 🔍 **内容浏览** - 首页推荐、分区浏览、追番追剧、历史记录、稍后观看
- 📱 **交互优化** - 专为 TV 遥控器优化的导航交互，支持 T9/全键盘搜索
- 🎨 **多分辨率适配** - 支持从 480p 到 4K 多种屏幕分辨率
- 🌙 **深色模式** - 内置深色主题支持

## 🛠️ 技术栈

- **语言**: Kotlin
- **架构**: MVVM + Koin 依赖注入
- **网络**: Retrofit + OkHttp + Gson
- **播放器**: ExoPlayer (Media3)
- **图片加载**: Glide
- **弹幕**: 快手 AkDanmaku 引擎 
- **最低版本**: Android 5.0 (API 21)
- **目标版本**: Android 15 (API 35)

## 🧭 开发与架构约束

- UI 页面 / Fragment / Activity / Dialog 默认不直接 `new Repository()`，优先通过 Koin 注入 `ViewModel` / `repository` / gateway
- 新页面优先走 injected `ViewModel` 或显式 injected `repository`，不要继续从 UI 直接访问全局静态入口
- 新功能优先进入 `feature/*` 目录；跨 feature 的公共能力放 `core/*`
- `NetworkManager` 只保留 runtime facade / bootstrap 边界：允许 `Application` 初始化、DI 装配暴露、gateway 兼容委托使用
- 新业务逻辑、新页面、新 repository、新播放器流程不再继续直接依赖或扩展 `NetworkManager`
- 少量不走 Koin 生命周期的 helper / custom view / tool 对象暂不要求一次性清零，但不能继续扩散职责
- 代码评审默认使用 [docs/ARCHITECTURE_REVIEW_CHECKLIST.md](docs/ARCHITECTURE_REVIEW_CHECKLIST.md)

## APP 截图

<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/b746a1dd-8243-4378-a1ea-c023460323b7" />
<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/4a0199e0-64a0-4762-9d45-8ec2ff58524a" />


## 🙏 感谢

- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) - B 站 API 收集整理
- [BBLL](https://github.com/xiaye13579/BBLL) - 优秀的页面设计和操作逻辑，本项目绝大部分页面和操作逻辑都是抄袭 BBLL🥰
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) - 部分关键功能参考了 Piliplus 的逻辑
- [Blbl](https://github.com/cat3399/blbl?tab=readme-ov-file) - 参考部分功能参考了 Blbl 的逻辑
- 其它开源第三方 B 站客户端

## ⚠️ 免责声明

- 不得利用本项目进行任何非法活动
- 不得干扰 B 站的正常运营
- 不得传播恶意软件或病毒

### 🚫 禁止行为

- 🚫 禁止在官方平台（b 站）及官方账号区域（如 b 站微博评论区）宣传本项目
- 🚫 禁止在微信公众号平台宣传本项目
- 🚫 禁止利用本项目牟利，本项目无任何盈利行为，第三方盈利与本项目无关

> 💡 代码由 Codex 和 [智谱 AI](https://bigmodel.cn/) 编写，如有问题请联系 [OpenAI](https://openai.com/) 或 [智谱](https://bigmodel.cn/) 😤
