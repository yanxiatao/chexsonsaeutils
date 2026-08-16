---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: 配置
  position: 70
categories:
  - chexsonsaeutils configuration
---

# 配置

公共配置文件路径是 `config/chexsonsaeutils-common.toml`。

## 顶层开关

- `craftingContinuationEnabled`
- `processingPatternReplacementEnabled`
- `formalMachineCraftingDispatchEnabled`
- `formalMachinePlanningAggregationEnabled`

这些布尔值都是启动期功能总开关。

## 无阂枢配置节

`[parallelCraftingCpuTool]` 用来控制无阂枢是否启用，
以及无阂 lane、宣告存储、调度预算等参数。

## 径行配置节

`[aeDirectProcessingMachine]` 用来控制径行的配方映射、
执行预算档位，以及通用配方发现开关。

`recipeMappings` 接收显式的机器到配方类型映射。
`budgetProfile` 支持 `normal`、`high`、`benchmark`。
`genericDiscoveryEnabled = false` 时，只走显式适配器与显式映射。

## 辅助文件

- `config/chexsonsaeutils/direct_processing_machines.json`
- `config/chexsonsaeutils/direct_processing_machines.guide.md`
