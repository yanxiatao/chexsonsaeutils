# Configured 支持文档

## 概述

Chexson's AE Utils 支持 [Configured](https://www.curseforge.com/minecraft/mc-mods/configured) mod 提供的游戏内配置 UI。

## 安装 Configured

Configured 是**可选依赖**，不影响核心功能。

- **版本要求**: Configured `2.6+` for NeoForge `1.21.1`
- **下载地址**: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/configured)

## 使用方式

### 启动游戏后

1. 主菜单或游戏内按 `ESC` 打开暂停菜单
2. 点击 **Mods** 按钮
3. 在 mod 列表中找到 `Chexson's ae utils`
4. 点击右侧的 **Config** 按钮

### 配置界面特性

- **分类结构**: 配置项按功能分组（Core Features, AEA Migration, Parallel CPU, Direct Processing Machine）
- **范围验证**: 数值配置会自动限制在合理范围内
- **详细注释**: 每个选项都有多行解释说明
- **实时保存**: 修改会立即写入配置文件

## 配置分组

### [features] Core Feature Toggles
- `craftingContinuationEnabled`: AE2 合成续跑功能
- `formalMachineCraftingDispatchEnabled`: Formal machine 快速派发
- `formalMachinePlanningAggregationEnabled`: 大请求 planning 聚合
- `processingPatternReplacementEnabled`: 处理样板替换

### [aeaMigration] AEA Feature Migration
- `dyeablePatternsEnabled`: 染色样板迁移
- `dyeableRecursiveRetainedCatalystAmount`: 递归催化物保留数量
- `enhancedCraftingStatusEnabled`: 增强合成状态
- `buildingGadgets2IntegrationEnabled`: Building Gadgets 2 集成
- `ftbUltimineMemoryCardEnabled`: FTB Ultimine 记忆卡兼容

### [parallelCraftingCpuTool] Parallel Crafting CPU Tool
- `parallelCraftingCpuEnabled`: 启用极限并行 CPU
- `parallelCraftingCpuCoProcessorsPerVirtualCpu`: 虚拟 CPU 协处理器数量
- `parallelCraftingCpuMaxInternalLanesPerBlock`: 每方块最大内部通道数
- `parallelCraftingCpuMaxInternalLanesPerGrid`: 每网格最大内部通道数
- `parallelCraftingCpuMaxSubmissionsPerTickPerGrid`: 每 tick 最大提交数
- `parallelCraftingCpuMaxPatternPushesPerTickPerGrid`: 每 tick 最大样板推送数
- `parallelCraftingCpuMaxProviderChecksPerTickPerGrid`: 每 tick 最大提供者检查数
- `parallelCraftingCpuTickBudgetNanosPerGrid`: 每 tick CPU 调度预算（纳秒）
- `parallelCraftingCpuStorageBytes`: 合成存储字节数
- `parallelCraftingCpuLaneShardCount`: 通道分片数

### [aeDirectProcessingMachine] AE Direct Processing Machine
- `recipeMappings`: 配方映射列表（格式：`machine_id=recipe_type_id=default_ticks`）
- `budgetProfile`: 执行预算配置（`normal`, `high`, `benchmark`）
- `genericDiscoveryEnabled`: 启用通用配方类型发现

## 手动配置

如果未安装 Configured，配置文件位于：

```
config/chexsonsaeutils-common.toml
```

直接编辑后**需要重启游戏**才能生效。

## 配置兼容性

- 所有配置项均在**启动时**读取
- 修改配置后必须重启游戏或服务器
- 配置文件使用 TOML 格式，支持注释（`#` 开头）
- 数值超出范围会自动 clamp 到最小/最大值

## NeoForge 原生支持

本 mod 使用 NeoForge 的 `ModConfigSpec` 系统，Configured 会自动发现并展示配置。无需额外代码或元数据注册。

## 故障排查

### Configured 按钮不显示

1. 确认 Configured 版本为 `2.6+`
2. 确认 NeoForge 版本为 `21.1+`
3. 检查日志是否有 Configured 加载错误

### 配置修改不生效

1. 确认已**重启游戏**
2. 检查 `config/chexsonsaeutils-common.toml` 是否正确更新
3. 查看日志中的配置加载信息

### 数值被自动修改

配置系统会自动 clamp 超出范围的值：
- `parallelCraftingCpuCoProcessorsPerVirtualCpu`: [0, 2147483646]
- `parallelCraftingCpuTickBudgetNanosPerGrid`: [1, 45000000]
- `dyeableRecursiveRetainedCatalystAmount`: [0, 2147483647]

检查日志中的 clamp 警告信息。

## 相关链接

- [Configured Mod 页面](https://www.curseforge.com/minecraft/mc-mods/configured)
- [NeoForge ModConfigSpec 文档](https://docs.neoforged.net/docs/configuration/)
- [本 mod 配置源码](../src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java)
