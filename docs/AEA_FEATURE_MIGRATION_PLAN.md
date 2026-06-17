# AEA 功能迁移计划

## 来源与范围

- 参考项目：`REFactoryTeam/AEA`。
- 本地路径：`reference-sources/AEA`。
- 参考分支：`1.20.1`。
- 参考提交：`65d403f15b6ff62ebc06ba3fe013c492a4a846bc`。
- 参考版本：`v1.0.5`。
- 许可证：MIT，见仓库根目录 `THIRD_PARTY_NOTICES.md`。

本计划只覆盖用户指定的四个功能。

- Dyeable Patterns。
- Enhanced Crafting Status。
- Building Gadgets 2 Integration。
- FTB Ultimine & Memory Card Compatibility。

以下 AEA 功能不纳入本轮迁移。

- Advanced Terminal。
- Mirror Pattern Provider。
- Wireless Connection Provider。
- JEI bookmark forwarding。
- ExtendedAE 彩虹边框。
- Quartz Cutting Knife 复制名称。
- Create / Jade / AdvancedAE / ExtendedAE 的额外集成。

## 总体约束

- 目标平台固定为 `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21`。
- 不直接照搬 Forge 1.20.1 代码；每个注入点必须按 AE2 19.2.17 与 NeoForge API 重新核对。
- 禁止反射。
- 可选联动依赖必须隔离到独立包和独立 mixin gate。
- 配置错误、注入点漂移、协议不匹配必须有日志。
- 核心运行逻辑允许按目标对象跳过失败项，但不能空 `catch`。
- 不引入 AEA 的全量架构，只迁移指定功能的必要类、资源和协议。
- 所有迁移功能必须先通过兼容性设计审查，再进入代码实现。
- 兼容性审查必须覆盖本仓现有功能、AE2 原生链路、可选 mod 缺失场景和联动 mod 同装场景。
- 迁移实现必须在单独分支 `1.21.1-dev` 中进行。
- 每个阶段执行完成且验证记录写入后，必须立即创建一次 commit。
  不得把多个阶段混在同一个提交里。

## 兼容性优先策略

目标：迁移功能从规划阶段就与现有功能共同设计，避免实现完成后再靠回退补兼容。

原则：

- 先保护本仓既有能力，再叠加 AEA 功能。
- AE2 原生 provider、CPU、storage、requester 链路保持主路径。
- AEA 逻辑只能作为明确功能扩展。
  不得替代本仓现有 formal-machine、parallel-cpu、continuation 主链。
- 同一 AE2 类上的多个 mixin 必须先列注入点矩阵。
- 若两个功能需要修改同一方法，优先抽出本仓自有协调层。
  不允许多个 mixin 互相覆盖返回值。
- 可选依赖相关类必须做到未安装时不加载、不解析、不触发 mixin。
- 任何兼容性冲突不得通过静默跳过处理。
  必须记录日志或在验证文档中标出硬边界。

现有功能兼容矩阵：

- Processing Pattern Replacement：
  与 Dyeable Patterns、BG2 Integration 有交集。
  颜色递归规划必须识别 replacement-aware pattern，不得绕过 replacement selector。
  BG2 生成的 processing pattern 后续仍可编辑替换规则。
- Crafting Continuation / Ignore Missing：
  与 Enhanced Crafting Status、Dyeable Patterns 有交集。
  Blocked 与 Waiting 状态要合并展示，不得互相替换 tooltip 或 background。
  染色递归 plan 必须保留 continuation 对 incomplete plan 的处理。
- Formal Machine Planning Aggregation：
  与 Dyeable Patterns、Enhanced Crafting Status 有交集。
  染色递归不得 overwrite `beginCraftingCalculation`。
  只能接入 formal aggregation 返回后的计划协调层或明确前置层。
- Formal Machine Crafting Dispatch：
  与 Dyeable Patterns、Enhanced Crafting Status 有交集。
  不改变 source CPU attribution、completion、requester final output 语义。
- Parallel Crafting CPU Tool：
  与 Enhanced Crafting Status、Dyeable Patterns 有交集。
  只扩展状态数据和规划输入。
  不得新增 final-output buffer、额外回流链路或 CPU completion helper。
- AE Direct Processing Machine：
  与 BG2 Integration、Dyeable Patterns 有交集。
  BG2 只生成 AE2 processing pattern。
  direct-processing discovery / JEI import 仍走本仓现有签名与 guard。
- Multi-Level Emitter：
  与 Enhanced Crafting Status 有交集。
  新状态不能破坏 emitter 读取 crafting / waiting 状态的语义。
- Mekanism / Applied Mekanistics 兼容：
  与 BG2 Integration、Dyeable Patterns 有交集。
  新通用 `GenericStack` 处理必须保留 item / fluid / chemical 边界。
  不得把非物品栈强行降级。

每阶段兼容性出口：

- 设计阶段产出“影响文件 + 目标 AE2 方法 + 已有 mixin + 新 mixin”表。
- 实现阶段先补或更新兼容性测试，再改代码。
- 验收阶段必须至少运行相关既有 targeted test。
- 若无法自动验证 GUI 或可选 mod 场景，必须写明人工 smoke 步骤和未验证风险。
- 阶段出口必须包含 git 状态检查、阶段内变更清单、验证结果和 commit 哈希。
- 若阶段验证未完成，只能提交为明确标注的 blocked / partial 状态提交。
  提交信息必须写明未完成验证项。

## 第 0 阶段：参考基线与依赖确认

目标：建立可复核的迁移基线，避免后续功能混入无关 AEA 代码。

任务：

- 切换或创建 `1.21.1-dev` 分支，并确认后续迁移都在该分支执行。
- 固定 `reference-sources/AEA` 的提交哈希和分支。
- 为 `build.gradle` 增加 Building Gadgets 2 与 FTB Ultimine 的 `compileOnly` 依赖。
- 只在本地运行验证时增加 `localRuntime`，默认不把可选依赖变成强依赖。
- 在 `ChexsonsaeutilsCompatibilityConfig` 增加四组开关：
  - `dyeablePatternsEnabled`。
  - `enhancedCraftingStatusEnabled`。
  - `buildingGadgets2IntegrationEnabled`。
  - `ftbUltimineMemoryCardEnabled`。
- 扩展 `ChexsonsaeutilsMixinPlugin`，按配置和 mod 是否加载控制可选 mixin。
- 建立迁移 mixin 影响表。
  列出每个新 mixin 的目标类、目标方法、现有本仓 mixin 和冲突处理方式。
- 建立可选依赖矩阵。
  覆盖 AE2 only、AE2 + BG2、AE2 + FTB Ultimine、AE2 + BG2 + FTB Ultimine。
- 建立功能组合矩阵。
  至少覆盖 continuation、replacement、formal-machine、parallel-cpu 与四个迁移功能的交集。

验收：

- 不安装 BG2 / FTB Ultimine 时，mod 能启动。
- 关闭任一开关时，相关 mixin 不应用。
- 缺失可选 mod 时，无 `ClassNotFoundException`。
- 新增影响表经人工审查，确认没有未登记的同方法 mixin 冲突。
- 每个新增配置默认值都有“对现有功能的影响”说明。
- 完成第 0 阶段后创建 commit，提交信息建议：
  `plan: prepare aea migration baseline and compatibility gates`。

## 第 1 阶段：Dyeable Patterns

目标：让 AE2 编码样板可以染色，并保留 AEA 的同色递归样板计算语义。

AEA 参考点：

- `EncodedPatternItemCommonMixin`。
- `EncodedPatternItemClientMixin`。
- `StyleManagerMixin`。
- `IPatternDetailsMixin`。
- `CraftingServiceMixin`。
- `AEACraftingCalculation`。
- `AEACraftingTreeNode`。
- `AEACraftingTreeProcess`。
- `AEANetworkCraftingProviders`。
- `resourcepacks/dyeable_pattern/assets/ae2/models/item/*_pattern.json`。
- `resourcepacks/dyeable_pattern/assets/ae2/textures/item/*_pattern_*.png`。

迁移设计：

- 为 AE2 `EncodedPatternItem` 增加染色能力。
- 1.21.1 侧优先核对 `DataComponents` 与 `DyeableLeatherItem` 的真实契约。
- 若 AE2 19.2.17 仍通过 item model tint 渲染，保留 AEA 的双层 base / led 资源方案。
- 若 1.21.1 渲染链路变更，改为显式注册 item color provider。
- 隐藏只有染色数据的空 tooltip，避免样板显示无意义 `display` 数据。
- 在 `IPatternDetails` 层暴露样板颜色读取接口。
- 按颜色对 crafting provider 的样板集合建立索引。
- 先设计 `DyeablePatternCraftingPlanner` 协调层。
  明确与 replacement、formal aggregation、parallel CPU 的调用顺序。
- 迁移同色递归链计算。
  不能覆盖本仓现有 formal-machine、parallel-cpu、continuation 和 replacement 逻辑。
- 将 AEA 的 `CraftingService.beginCraftingCalculation` overwrite 改成窄注入或本仓已有计算入口适配。
- 对 CPU 最终产物暂存逻辑做边界复核。
  本仓已有 AE2 原生回流修复，不能重新引入“先写内部库存再回流”的错误路径。
- 颜色递归只影响 planning，不直接改 execution return path。
- 与 processing replacement 同时启用时，先解析 replacement metadata，再执行颜色分组选择。
- 与 formal-machine aggregation 同时启用时，颜色递归不得吞掉 formal segment boundary。
- 与 parallel CPU 同时启用时，生成的 plan 必须仍能被 parallel CPU 按原生 submit contract 接收。

风险：

- AEA 的同色递归计算重写了 AE2 计算树，和本仓的 pattern replacement / formal-machine 规划有重叠。
- `NetworkCraftingProviders` 在 AE2 19.2.17 的字段和生命周期可能已变。
- 颜色样板的自循环计算必须避免环路误判和无限递归。

验证：

- 单元测试：颜色读取、无色样板、染色样板、只有颜色数据时 tooltip 不显示垃圾数据。
- 规划测试：同色递归样板能形成可执行 plan。
- 规划测试：不同颜色样板不被错误合并成递归环。
- 回归测试：processing pattern replacement 与染色样板同时存在时，替换语义仍生效。
- 回归测试：formal-machine aggregation 与染色递归同时存在时，segment boundary 和 source CPU 语义仍正确。
- 回归测试：parallel CPU 接收染色递归 plan 后，不改变 final-output 回流路径。
- 回归测试：continuation 对染色递归 incomplete plan 仍能进入等待分支。
- 运行验证：AE2 样板物品在 JEI、终端、库存中显示正确颜色。
- 完成第 1 阶段后创建 commit，提交信息建议：
  `feat: migrate aea dyeable patterns with compatibility coverage`。

## 第 2 阶段：Enhanced Crafting Status

目标：增强 AE2 合成状态展示，显示 Blocked 状态与 Pattern Times。

AEA 参考点：

- `mixin/ae/crafting/blocked/CraftingCpuLogicMixin`。
- `mixin/ae/crafting/blocked/CraftingStatusMixin`。
- `mixin/ae/crafting/blocked/CraftingStatusEntryMixin`。
- `mixin/ae/crafting/blocked/client/CraftingStatusTableRendererMixin`。
- `mixin/ae/crafting/blocked/client/CraftingCPUScreenMixin`。
- `mixin/ae/crafting/patterntimes/CraftingPlanSummaryMixin`。
- `mixin/ae/crafting/patterntimes/CraftingPlanSummaryEntryMixin`。
- `mixin/ae/crafting/patterntimes/client/CraftConfirmTableRendererMixin`。
- `IMixinBlockedAmountHolder`。
- `IMixinCraftingCpuLogicBlocked`。
- `IMixinPatternTimesHolder`。

迁移设计：

- Blocked 状态：
  - 在 `CraftingCpuLogic.executeCrafting` 捕获当前 pattern。
  - 在 `pushPattern` 返回 false 且即将 reinject 输入时，累计该 pattern 输出为 blocked 数量。
  - 将 blocked 数量写入 `CraftingStatusEntry` 的网络序列化。
  - CPU 屏幕增量更新时保留 blocked 数量。
  - 状态表描述和 tooltip 增加 `Blocked` 文本。
  - 开启 AE2 彩色状态时，blocked 行使用橙色背景。
- Pattern Times：
  - 从 `ICraftingPlan.patternTimes()` 汇总每个输出对应的 pattern 发配次数。
  - 将次数列表写入 `CraftingPlanSummaryEntry` 的网络序列化。
  - Craft Confirm 表格描述展示前几个最大次数，tooltip 展示更多次数。
- 本仓已有 continuation / parallel-cpu 的 CPU UI mixin。
  新增字段必须复用或扩展现有数据对象，避免对同一渲染点重复重定向。
- Blocked 与 continuation Waiting 同时存在时，状态行采用统一优先级。
  Waiting 优先展示等待数量，Blocked 作为补充 tooltip 行。
- Pattern Times 只追加 Craft Confirm 信息。
  不替换 continuation / parallel CPU 的按钮、CPU 选择或状态投影。
- 序列化字段必须集中在单一扩展结构里。
  避免多个 mixin 各自向同一 buffer 追加字段。

风险：

- 网络序列化改动必须保证客户端和服务端同时加载本 mod。
- 多个 mixin 修改 `CraftingStatusEntry.read/write` 时可能存在字段顺序冲突。
- 本仓 continuation 等待状态已有自定义 UI，可能与 blocked 状态重复表达。

验证：

- 单元测试：blocked holder 和 pattern-times holder 序列化往返。
- 集成测试：pattern push 失败时 blocked 数量进入 status snapshot。
- 客户端 smoke：Crafting CPU 屏幕能显示 Blocked 文案和背景。
- Craft Confirm smoke：Pattern Times 展示与 `patternTimes()` 数据一致。
- 回归测试：continuation 等待状态仍能显示等待分支详情。
- 回归测试：parallel CPU 状态菜单仍能同步 lane / CPU visibility。
- 回归测试：formal-machine timing correction 后仍能显示 blocked / pattern times。
- 回归测试：客户端只显示增强状态，不影响 craft confirm submit 行为。
- 完成第 2 阶段后创建 commit，提交信息建议：
  `feat: migrate aea enhanced crafting status`。

## 第 3 阶段：Building Gadgets 2 Integration

目标：将 BG2 Copy-Paste Tool 的材料列表转换为 AE2 Processing Pattern。

AEA 参考点：

- `EncodedPatternUtil`。
- `TemplateManagerHandlerMixin`。
- `PacketUpdateTemplateManagerMixin`。
- `PacketUpdateTemplateManagerAccessor`。

迁移设计：

- 建立独立包：`integration.buildinggadgets2`。
- 允许 BG2 template manager 的目标槽接受 AE2 blank pattern 与 processing pattern。
- 拦截 BG2 template manager 的更新包。
- 只处理“写入模板”的模式，其他模式完全交还 BG2。
- 从 BG2 保存的 copy-paste build list 读取 `StatePos`。
- 对每个 block state 计算需求：
  - source fluid 记为 1000 mB 的 `AEFluidKey`。
  - 普通方块通过 BG2 drop 工具得到 item drops。
- 合并同类 `AEKey` 数量。
- 按数量降序取前 81 项，写入 AE2 processing pattern 输入。
- 输出使用 BG2 template item，并继承 template name。
- 成功和失败播放不同反馈音效。
- BG2 生成的 pattern 必须是标准 AE2 processing pattern，不携带本仓 replacement metadata。
- 后续玩家在 AE2 Pattern Encoding Terminal 编辑该 pattern 时，仍可按本仓现有 UI 添加 replacement rule。
- 若材料包含本仓 direct-processing 不支持的栈类型，只在 BG2 转换阶段记录不可编码项。
  不影响 direct-processing 注册和 JEI import。

风险：

- BG2 1.21.1 NeoForge 的包名、网络包、template manager 菜单可能已改名。
- 使用 block drops 推导材料需求时，特殊方块可能和 BG2 实际消耗不一致。
- 81 输入上限会截断大型建筑材料列表，需要在 tooltip 或日志中说明。

验证：

- 单元测试：build list 到 `GenericStack[]` 的聚合和排序。
- 单元测试：fluid source 转换为 `AEFluidKey`。
- 集成 smoke：template manager 接受 blank pattern。
- 集成 smoke：含普通方块和流体的 copy-paste 数据能编码成 processing pattern。
- 运行验证：未安装 BG2 时 mod 启动无错误。
- 回归测试：direct-processing JEI import guard 仍能拒绝不支持 recipe type。
- 回归测试：BG2 生成 pattern 再进入 processing replacement UI，规则保存和解码仍有效。
- 回归测试：BG2 可选依赖存在时，不改变 AE2 原生 pattern terminal 的普通编码行为。
- 完成第 3 阶段后创建 commit，提交信息建议：
  `feat: migrate aea building gadgets integration`。

## 第 4 阶段：FTB Ultimine & Memory Card Compatibility

目标：按 FTB Ultimine 选区批量应用 AE2 Memory Card 配置。

AEA 参考点：

- `AEMemoryCardHandler`。
- `FTBUltimineServerConfigBridge`。
- `FTBUltimineMixin`。
- `PlatformMethodsImplMixin`。

迁移设计：

- 建立独立包：`integration.ftbultimine`。
- 在 FTB Ultimine 服务端配置中增加 `right_click_memory_card` 开关。
- 当玩家按住 Ultimine 键并手持 AE2 Memory Card 右击时，接管对应右击回调。
- 读取 FTB Ultimine 缓存的目标坐标集合。
- 对每个坐标：
  - 若是 `IPartHost`，遍历六面和中心 part。
  - 若是 `AEBaseBlockEntity`，直接作为目标。
- 根据 Memory Card 保存的 settings name 判断：
  - 同名目标调用目标自身 `importSettings(SettingsFrom.MEMORY_CARD, ...)`。
  - 不同名目标尝试 AE2 generic settings 导入。
- 成功至少一个目标后，向玩家发送 AE2 `SETTINGS_LOADED` 提示。
- 每个目标失败时记录 debug 日志并继续处理其他目标。
  禁止 AEA 里的空 `catch` 行为。
- 只处理 AE2 memory card 的右击批量应用。
  不改变 FTB Ultimine 原有挖掘、选区和非 memory card 行为。
- 对本仓新增方块和 AE2 原生 block entity / part 统一使用 AE2 settings contract。
- 不对 formal-machine、parallel-cpu、direct-processing 方块引入自定义批量配置兜底。
  若目标没有 AE2 settings contract，记录并跳过。

风险：

- FTB Ultimine 1.21.1 NeoForge 的 `PlatformMethodsImpl` 与 `ShapeContext` 可能发生签名变化。
- AE2 19.2.17 的 Memory Card generic import 入口可能已改名。
- 对 part 和 block entity 的 settings id 映射必须按 AE2 当前 item 定义重新核对。

验证：

- 单元测试：settings id 映射，包含 interface part 和 pattern provider part。
- 单元测试：空 memory card、空选区、非 AE 目标均不应用。
- 集成 smoke：Ultimine 选中多个 AE block entity 后批量导入设置。
- 集成 smoke：Ultimine 选中 cable bus 上多个 part 后批量导入设置。
- 运行验证：未安装 FTB Ultimine 时 mod 启动无错误。
- 回归测试：不按 Ultimine 键时，AE2 Memory Card 原生单点粘贴行为不变。
- 回归测试：选区内混有本仓方块、AE2 原生方块和非 AE 方块时，只对可导入目标生效。
- 回归测试：导入失败目标写日志，但不阻断其他目标应用。
- 完成第 4 阶段后创建 commit，提交信息建议：
  `feat: migrate aea ftb ultimine memory card compatibility`。

## 第 5 阶段：文档、资源与验收收口

目标：把迁移结果做成可维护功能，而不是一次性补丁。

任务：

- 更新 README 的功能列表和可选依赖说明。
- 为新增配置项补充默认值、说明和重启要求。
- 将 AEA 许可证说明保留在 `THIRD_PARTY_NOTICES.md`。
- 若复制 AEA 贴图或模型资源，确认发布包内包含 AEA MIT notice。
- 为四个功能分别补中英文翻译键。
- 为每个可选 mod 建立一条“不安装也能启动”的 smoke 记录。
- 对 mixin 注入点建立最小编译保护或结构测试。
- 增加 `docs/AEA_FEATURE_COMPATIBILITY_MATRIX.md` 或等价章节，记录所有组合验证结果。
- 每项功能的发布说明必须写明与现有功能共存的已验证范围。
- 完成第 5 阶段后创建 commit，提交信息建议：
  `docs: finalize aea migration compatibility evidence`。

最终验收命令：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat processResources
.\gradlew.bat test
.\gradlew.bat runClient
```

运行边界：

- `runClient` 需分别覆盖：
  - 只安装 AE2。
  - 安装 AE2 + BG2。
  - 安装 AE2 + FTB Ultimine。
  - 安装 AE2 + BG2 + FTB Ultimine。
- 功能组合需分别覆盖：
  - replacement + dyeable pattern。
  - continuation + enhanced status。
  - formal-machine + dyeable pattern。
  - formal-machine + enhanced status。
  - parallel CPU + dyeable pattern。
  - parallel CPU + enhanced status。
  - direct-processing + BG2 generated pattern。

## 建议执行顺序

1. 先完成第 0 阶段。
2. 再做 Enhanced Crafting Status。
3. 再做 FTB Ultimine & Memory Card。
4. 再做 Building Gadgets 2。
5. 最后做 Dyeable Patterns。

原因：

- Enhanced Crafting Status 和 FTB Ultimine 边界清楚，适合先建立 mixin gate 与可选依赖模板。
- BG2 依赖外部 API，但核心逻辑与 AE2 crafting 规划解耦。
- Dyeable Patterns 涉及 AE2 crafting calculation，和本仓现有合成优化重叠最大，应最后处理。
