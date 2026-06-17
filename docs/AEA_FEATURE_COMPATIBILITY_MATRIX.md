# AEA 功能迁移兼容矩阵

## 第 0 阶段基线

- 迁移分支：`1.21.1-dev`。
- AEA 参考路径：`reference-sources/AEA`。
- AEA 参考分支：`1.20.1`。
- AEA 参考提交：`65d403f15b6ff62ebc06ba3fe013c492a4a846bc`。
- AEA 参考版本：`v1.0.5`。
- 目标平台：`Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21`。

## 配置开关

| 配置键 | 默认值 | 重启 | 影响说明 |
| --- | --- | --- | --- |
| `aeaMigration.dyeablePatternsEnabled` | `true` | 是 | 只允许启用样板染色与颜色规划入口；不得替代现有合成主链。 |
| `aeaMigration.enhancedCraftingStatusEnabled` | `true` | 是 | 只允许追加 Blocked 与 Pattern Times 状态数据；不得覆盖现有 UI 状态。 |
| `aeaMigration.buildingGadgets2IntegrationEnabled` | `true` | 是 | 只有 `buildinggadgets2` 已加载时才允许应用；生成标准 AE2 processing pattern。 |
| `aeaMigration.ftbUltimineMemoryCardEnabled` | `true` | 是 | 只有 `ftbultimine` 和 `ftblibrary` 已加载时才允许应用；不改变单点行为。 |

## 可选依赖矩阵

| 组合 | 预期 gate 行为 | 第 0 阶段状态 |
| --- | --- | --- |
| AE2 only | BG2 与 FTB Ultimine mixin fail-closed；AE2 内部功能只受配置控制。 | 已建立 gate；启动 smoke 待运行。 |
| AE2 + BG2 | BG2 mixin 需配置开启且 `buildinggadgets2` loaded。 | 坐标已登记；API 适配待第 3 阶段。 |
| AE2 + FTB Ultimine | FTB mixin 需配置开启且 `ftbultimine`、`ftblibrary` loaded。 | 坐标已登记；API 适配待第 4 阶段。 |
| AE2 + BG2 + FTB Ultimine | 两组可选 mixin 独立 gate，互不影响。 | gate 独立；组合 smoke 待运行。 |

## Mixin 影响表

| 功能 | 目标类 / 方法 | 既有本仓 mixin | 计划新增 mixin | 冲突处理 |
| --- | --- | --- | --- | --- |
| Dyeable Patterns | `EncodedPatternItem` tooltip / component / item render | 无已登记同方法 mixin | `EncodedPatternItemDyeableCommonMixin`、`EncodedPatternItemDyeableClientMixin` | 只处理颜色数据；隐藏仅颜色 component 的无意义 tooltip。 |
| Dyeable Patterns | `IPatternDetails` 颜色读取 | `PatternDetailsHelperAccessor` | `PatternDetailsDyeablePatternMixin` | 新接口只暴露颜色；不改变 pattern definition。 |
| Dyeable Patterns | `CraftingService.beginCraftingCalculation` | planning / parallel / continuation mixin | `CraftingServiceDyeablePatternMixin` | 禁止 AEA overwrite；必须接入协调层或窄注入。 |
| Dyeable Patterns | `NetworkCraftingProviders` provider 索引 | 无已登记同类 mixin | 待第 1 阶段核对 | 先解析 replacement metadata，再按颜色分组。 |
| Dyeable Patterns | `CraftingCpuLogic` execution / return path | formal-machine CPU mixin | `CraftingCpuLogicDyeablePatternMixin` | 颜色递归只影响 planning；禁止引入内部库存回流。 |
| Enhanced Crafting Status | `CraftingCpuLogic.executeCrafting` / `pushPattern` | formal source-context / scaled pattern mixin | `CraftingCpuLogicEnhancedStatusMixin` | 只记录 push 失败输出；不改 provider 调用和 source CPU。 |
| Enhanced Crafting Status | `CraftingStatus` / `CraftingStatusEntry` serialize | `CraftingStatusFormalMachineMixin` | status / entry enhanced mixin | 序列化字段必须集中；不得多个 mixin 各自追加 buffer 字段。 |
| Enhanced Crafting Status | `CraftingPlanSummary` / entry serialize | 无已登记同类 mixin | summary / entry enhanced mixin | Pattern Times 只追加确认页信息，不改 submit 行为。 |
| Enhanced Crafting Status | `CraftingStatusTableRenderer` / CPU screen | continuation UI mixin | renderer / screen enhanced mixin | Waiting 优先展示；Blocked 作为补充 tooltip 或次级视觉状态。 |
| Enhanced Crafting Status | `CraftConfirmTableRenderer` | continuation / parallel CPU screen mixin | `CraftConfirmTableRendererEnhancedStatusMixin` | Pattern Times 只展示摘要和 tooltip。 |
| Building Gadgets 2 | `TemplateManagerHandler.isItemValid` | 无 | `TemplateManagerHandlerMixin` | 只允许目标槽接受 AE2 blank / processing pattern。 |
| Building Gadgets 2 | BG2 template manager 更新包 | 无 | packet mixin / accessor | 只处理写入模板模式；其他路径交还 BG2。 |
| FTB Ultimine | `FTBUltimine.serverStarting` | 无 | `FtbUltimineMixin` | 只增加 `right_click_memory_card` 配置项。 |
| FTB Ultimine | `PlatformMethodsImpl` right-click callback | 无 | `PlatformMethodsImplMixin` | 只处理 Ultimine 键 + AE2 Memory Card；单目标原生行为不变。 |

## 功能组合矩阵

| 组合 | 兼容要求 | 第 0 阶段状态 |
| --- | --- | --- |
| replacement + dyeable pattern | replacement metadata 先解析，颜色分组后执行。 | 已记录，自动验证待第 1 阶段。 |
| continuation + enhanced status | Waiting 优先，Blocked 作为补充状态。 | 已记录，自动验证待第 2 阶段。 |
| formal-machine + dyeable pattern | 不覆盖 formal aggregation，保留 segment boundary 和 source CPU。 | 已记录，自动验证待第 1 阶段。 |
| formal-machine + enhanced status | formal timing correction 后仍可追加 blocked / pattern times。 | 已记录，自动验证待第 2 阶段。 |
| parallel CPU + dyeable pattern | plan 仍走 AE2 原生 submit contract，不新增 final-output buffer。 | 已记录，自动验证待第 1 阶段。 |
| parallel CPU + enhanced status | CPU visibility、lane 和 status 同步不被替换。 | 已记录，自动验证待第 2 阶段。 |
| direct-processing + BG2 generated pattern | BG2 只生成标准 AE2 processing pattern，后续 replacement UI 可编辑规则。 | 已记录，自动验证待第 3 阶段。 |

## 阶段验证记录

| 阶段 | 验证项 | 状态 |
| --- | --- | --- |
| 第 0 阶段 | `compileJava` | 已随 targeted test 运行通过；仅有既有 deprecation warning。 |
| 第 0 阶段 | `processResources` | 已运行通过。 |
| 第 0 阶段 | `test --tests "*AeaMigrationFeatureGateTest"` | 已运行通过。 |
| 第 0 阶段 | AE2 only `runClient` | 待人工 smoke。 |
| 第 0 阶段 | AE2 + BG2 `runClient` | 待第 3 阶段 API 适配后 smoke。 |
| 第 0 阶段 | AE2 + FTB Ultimine `runClient` | 待第 4 阶段 API 适配后 smoke。 |
| 第 0 阶段 | AE2 + BG2 + FTB Ultimine `runClient` | 待第 3、4 阶段 API 适配后 smoke。 |
