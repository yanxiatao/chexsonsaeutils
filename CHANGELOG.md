# 更新日志

## 1.21.1-dev

- 迁移目标更新为 `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21`。
- 新增 AEA 染色样板迁移。
  支持样板染色、同色样板 planning、递归催化物保留和内置客户端资源包。
- 新增 AEA 增强合成状态迁移。
  Crafting Status 与 Craft Confirm 视图会显示 `Blocked` 和 `Pattern Times`。
- 新增 Building Gadgets 2 模板到 AE2 processing pattern 的集成。
  未安装 Building Gadgets 2 时不会加载对应 mixin。
- 新增 FTB Ultimine 记忆卡兼容。
  未安装 FTB Ultimine 时不会加载对应 mixin。
- 新增 `dyeableRecursiveRetainedCatalystAmount` 配置项。
  用于控制递归染色样板计划完成后保留的催化物数量。
- 修复 `dyeablePatternsEnabled=false` 时客户端仍注册样板染色处理和内置资源包的问题。
  现在关闭该配置会同时关闭相关 mixin、item color handler 和客户端资源包注册。
