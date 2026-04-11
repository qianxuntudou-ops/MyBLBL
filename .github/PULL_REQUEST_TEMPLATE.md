## Summary

- 

## Validation

- [ ] `.\gradlew.bat :app:compileDebugKotlin`
- [ ] `.\gradlew.bat :app:testDebugUnitTest`
- [ ] 关键页面已做基础人工验证

## Architecture Guardrails

- [ ] UI 页面 / Fragment / Activity / Dialog 没有直接 `new Repository()`
- [ ] 新页面优先通过 injected `ViewModel` / `repository` / gateway 协作
- [ ] 新功能优先进入 `feature/*`，如果没有，请说明原因
- [ ] 没有新增 `EventBus` 依赖
- [ ] 没有给 `NetworkManager` 增加新的业务职责
- [ ] 如果仍保留 `NetworkManager` 调用，调用点只在启动层、DI 层或 gateway 兼容实现

## Notes / Exceptions

- 如有例外，请说明原因、影响范围和后续治理计划
