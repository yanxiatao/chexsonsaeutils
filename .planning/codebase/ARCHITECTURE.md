# Architecture

**Analysis Date:** 2026-03-30

## Pattern Overview

**Overall:** Forge 模组引导 + AE2 深度集成 + mixin 驱动扩展架构

**Key Characteristics:**
- 用 `git/chexson/chexsonsaeutils/Chexsonsaeutils.java` 做最薄的模组入口，只负责注册 `Item`、`MenuType`、Creative Tab、客户端绑定与可选解码器注入。
- 把业务拆成三个独立功能域：`parts/automation` 负责多物品发信器，`pattern/replacement` 负责加工样板替换规则，`crafting` 负责 AE2 crafting continuation。
- 把对 AE2 的侵入集中在 `mixin/ae2/`，领域代码尽量放在普通包中；新增 AE2 行为时先放域逻辑，再用 mixin 做接线。
- 资源路径与运行时边界保持一致：AE2 风格屏幕样式放在 `src/main/resources/assets/ae2/screens/`，本模组纹理/语言/配方放在 `src/main/resources/assets/chexsonsaeutils/` 与 `src/main/resources/data/chexsonsaeutils/`。
- 两个可选功能包都由 `src/main/java/git/chexson/chexsonsaeutils/config/` 提供启动期开关，`src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java` 负责按功能选择是否应用 mixin。

## Layers

**Bootstrap / Registration:**
- Purpose: 提供 Forge 生命周期入口、注册表装配、客户端屏幕绑定、AE2 模式解码器注入。
- Location: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Contains: `@Mod` 入口、`DeferredRegister`、`MenuScreens.register(...)`、`PatternDetailsHelper` 解码器插桩。
- Depends on: Forge 事件总线、AE2 `Upgrades`/`PatternDetailsHelper`、`menu/implementations`、`parts/automation`、`pattern/replacement`。
- Used by: Forge 模组加载器、客户端 setup、common setup。

**Feature Gates / Compatibility Config:**
- Purpose: 把 continuation 与 processing pattern replacement 作为独立功能包开关，并在 mixin 装配前读取持久化配置。
- Location: `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`
- Contains: `ForgeConfigSpec`、从 `chexsonsaeutils-common.toml` 读取启动期布尔值的兼容逻辑。
- Depends on: Forge `ModConfig`、`FMLPaths`、`mixin/ae2/ChexsonsaeutilsMixinPlugin.java`。
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java`

**Multi-Level Emitter Domain:**
- Purpose: 实现多槽位、多比较模式、模糊匹配、crafting 状态联动、表达式判定的 AE2 部件。
- Location: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/`
- Contains: `MultiLevelEmitterItem.java`, `MultiLevelEmitterPart.java`, `MultiLevelEmitterRuntimePart.java`, `MultiLevelEmitterUtils.java`
- Depends on: AE2 `StorageLevelEmitterPart`、`ConfigInventory`、`IStackWatcher`、`ICraftingProvider`，以及 `parts/automation/expression/`。
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `menu/implementations/MultiLevelEmitterMenu.java`, `client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`

**Menu State / UI Contract:**
- Purpose: 承接服务端容器、客户端动作、屏幕状态快照与输入校验。
- Location: `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/`
- Contains: `MultiLevelEmitterMenu.java` 内的 `RuntimeMenu` 容器；`MultiLevelEmitterScreen.java` 作为 UI 状态/格式化/校验 helper。
- Depends on: AE2 `AEBaseMenu`、`MenuLocators`、`parts/automation/MultiLevelEmitterRuntimePart.java`, `parts/automation/expression/`
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`

**Client GUI Implementations:**
- Purpose: 渲染真实屏幕、处理按钮/输入框/滚动条/子界面交互。
- Location: `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`
- Contains: `MultiLevelEmitterRuntimeScreen.java`, `ProcessingPatternReplacementScreen.java`
- Depends on: AE2 `AEBaseScreen`/`AESubScreen`/样式系统、`menu/implementations/`, `pattern/replacement/`
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java`

**Processing Pattern Replacement Domain:**
- Purpose: 给 AE2 加工样板的输入槽附加“按标签/显式候选物”替换规则，并让规划与执行都感知这些候选集合。
- Location: `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/`
- Contains: 规则载荷、校验、NBT 持久化、候选组查询、AE2 `IPatternDetailsDecoder`、`ReplacementAwareProcessingPattern`
- Depends on: AE2 `IPatternDetails`/`AEProcessingPattern`、Minecraft 物品标签注册表。
- Used by: `mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java`, `client/gui/implementations/ProcessingPatternReplacementScreen.java`, `mixin/ae2/crafting/CraftingTreeProcessReplacementMixin.java`, `mixin/ae2/crafting/CraftingTreeNodeReplacementMixin.java`

**Crafting Continuation Domain:**
- Purpose: 在 AE2 craft confirm 与 crafting CPU 生命周期中插入 ignore-missing 模式、等待状态追踪、持久化和 GUI 投影。
- Location: `src/main/java/git/chexson/chexsonsaeutils/crafting/`
- Contains: `CraftingContinuationMode.java`, `submit/`, `status/`, `persistence/`
- Depends on: AE2 crafting internals、`SavedData`、多个 accessor mixin。
- Used by: `mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java`, `mixin/ae2/crafting/CraftingServiceContinuationMixin.java`, `mixin/ae2/menu/CraftingCPUMenuContinuationMixin.java`, `mixin/ae2/client/gui/`

**AE2 Integration / Injection Layer:**
- Purpose: 把三个功能域挂接到 AE2 原生菜单、GUI、规划树与 CPU 生命周期。
- Location: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- Contains: `menu/`, `client/gui/`, `crafting/` 下的 mixin、accessor、invoker，以及 `ChexsonsaeutilsMixinPlugin.java`
- Depends on: AE2 内部类与字段名、Sponge Mixin、配置开关。
- Used by: `src/main/resources/chexsonsaeutils.mixins.json`

## Data Flow

**Multi-Level Emitter 配置与运行链路:**

1. Forge 通过 `git/chexson/chexsonsaeutils/Chexsonsaeutils.java` 注册 `multi_level_emitter` 物品与 `MenuType`，配方定义在 `src/main/resources/data/chexsonsaeutils/recipes/network/parts/multi_level_emitter.json`。
2. 玩家激活部件时，`src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java` 的 `onPartActivate(...)` 先把当前 runtime part 放入 `ThreadLocal`，再调用 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java` 打开菜单。
3. `MultiLevelEmitterMenu.RuntimeMenu` 通过 `MenuLocators` 优先解析部件实例，必要时回退到 `MultiLevelEmitterRuntimePart.consumePublishedMenuRuntime()`，然后把客户端动作映射到 `commitThreshold(...)`、`cycleMatchingMode(...)`、`applyExpression(...)` 等服务端变更。
4. `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java` 做真实渲染；纯状态计算、输入同步决策、tooltip 文案和表达式草稿校验交给 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java`。
5. `MultiLevelEmitterRuntimePart` 把阈值、比较关系、模糊模式、crafting 模式、表达式与 `ConfigInventory` 一起写入自身 NBT；读取时通过 `MultiLevelEmitterUtils.java` 和 `parts/automation/expression/` 重新建立运行态。
6. 运行时判断以 `MultiLevelEmitterRuntimePart.evaluateConfiguredOutput(...)` 为中心：库存统计走 storage watcher，crafting 相关条件走 crafting watcher，表达式求值走 `MultiLevelEmitterExpressionCompiler` 生成的 `MultiLevelEmitterExpressionPlan`。

**State Management:**
- 服务端权威状态在 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`。
- 菜单打开期间的运行时绑定依赖 `ThreadLocal`，路径在 `publishForMenuOpen(...)` / `consumePublishedMenuRuntime()`。
- 客户端屏幕不缓存长期状态，所有可持久化字段都回写到 part NBT。

**Processing Pattern Replacement 链路:**

1. `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java` 在 AE2 `PatternEncodingTermScreen` 上拦截 `Ctrl + 左键`，只对 `EncodingMode.PROCESSING` 输入槽打开 `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/ProcessingPatternReplacementScreen.java`。
2. 子屏幕通过 `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleHost.java` 与 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java` 通讯，草稿先在菜单内存中保存，再由 `ProcessingSlotRuleValidation.java` 清洗。
3. 编码加工样板时，`PatternEncodingTermMenuRuleMixin` 把规则写入 `ItemStack` NBT 根标签 `chexsonsaeutils_processing_replacements`，实现位于 `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementPersistence.java`。
4. common setup 阶段，`git/chexson/chexsonsaeutils/Chexsonsaeutils.java` 在 `PatternDetailsHelper` 的 decoder 列表头部插入 `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementDecoder.java`。
5. 解码后的 `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java` 把单槽位输入扩展为候选集合；AE2 规划阶段由 `CraftingTreeProcessReplacementMixin.java` 与 `CraftingTreeNodeReplacementMixin.java` 改写 child node 代表项和模板选择。

**State Management:**
- 可持久化规则全部存放在 encoded pattern NBT。
- 终端打开期间的草稿状态只存在 `PatternEncodingTermMenuRuleMixin` 的 `Map<Integer, ProcessingSlotRuleDraft>` 中。

**Crafting Continuation 链路:**

1. `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/CraftConfirmScreenContinuationMixin.java` 在 AE2 craft confirm 界面上增加模式按钮。
2. `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java` 把模式同步到 `CraftConfirmMenu`，并在 `startJob` 时通过 `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationSubmitBridge.java` 把模式塞进一次提交范围内的 `ThreadLocal`。
3. `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java` 拦截 AE2 `submitJob(...)`；当模式为 `IGNORE_MISSING` 时走 `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java`，直接构建部分执行作业并 seed waiting 状态。
4. `src/main/java/git/chexson/chexsonsaeutils/crafting/status/CraftingContinuationStatusService.java` 在 CPU 插入、CPU rebuild、server end tick 三个时机协调等待中的输入，并把等待分支快照写入 `src/main/java/git/chexson/chexsonsaeutils/crafting/persistence/CraftingContinuationSavedData.java`。
5. `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftingCPUMenuContinuationMixin.java` 用 `@GuiSync` 把等待详情同步到菜单；`CraftingCPUScreenContinuationMixin.java` 把它转成屏幕投影；`CraftingStatusTableRendererContinuationMixin.java` 把 AE2 原始表格描述替换为 waiting 文案与底色。

**State Management:**
- 提交模式在一次 `submitJob(...)` 调用链中用 `CraftingContinuationSubmitBridge` 的 `ThreadLocal` 传递。
- 长期状态在 `CraftingContinuationSavedData`，运行态快照在 `CraftingContinuationStatusService` 的 `trackedJobs` 与 `observedAvailableWaitingStacks`。
- GUI 同步通过 `CraftingCPUMenuContinuationMixin` 的 `@GuiSync` 字段完成。

## Key Abstractions

**Mod Bootstrap:**
- Purpose: 把注册、客户端绑定、可选解码器注入控制在一个入口内。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Pattern: 薄入口；不要把业务逻辑直接写进 bootstrap。

**Server-Authoritative Part Runtime:**
- Purpose: 管理多物品发信器的配置、NBT、watcher、AE2 网络评估与菜单打开。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`, `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterPart.java`
- Pattern: `*RuntimePart` 持有运行态；`*Part` 保持纯逻辑/枚举/归一化函数。

**Menu Contract + Screen Helper:**
- Purpose: 分离“容器通信”和“纯 UI 状态计算”。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`, `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java`
- Pattern: `RuntimeMenu` 负责客户端动作与服务端状态写回；`MultiLevelEmitterScreen.java` 不是 `Screen` 子类，而是 view-model / validator。

**Replacement Rule Host:**
- Purpose: 让 AE2 终端 mixin 和子屏幕在不暴露 AE2 细节的前提下交换规则草稿。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleHost.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java`
- Pattern: 用接口隔离 mixin 持有的菜单状态与 GUI。

**Replacement-Aware Pattern:**
- Purpose: 把 encoded pattern 的单输入扩展为候选输入集合，并让规划与执行选择代表项。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java`, `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementDecoder.java`
- Pattern: 先解码为自定义 `IPatternDetails`，再用 mixin 改写 AE2 planning/execution。

**Continuation Bridge + Status Service:**
- Purpose: 把 GUI 选择的 continuation 模式带入提交链路，并在 CPU 生命周期里维护等待快照。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationSubmitBridge.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/status/CraftingContinuationStatusService.java`
- Pattern: 一次提交范围内用 `ThreadLocal`，跨 tick / 重进世界用 `SavedData`。

## Entry Points

**Forge Mod Entry:**
- Location: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Triggers: Forge 加载 `@Mod("chexsonsaeutils")`
- Responsibilities: 注册物品/菜单/Creative Tab、common/client setup、MultiLevelEmitter 绑定、processing pattern decoder 注入。

**Mixin Manifest:**
- Location: `src/main/resources/chexsonsaeutils.mixins.json`
- Triggers: Sponge Mixin 初始化
- Responsibilities: 声明 `menu`、`client/gui`、`crafting` 三类 AE2 注入点，并绑定 `ChexsonsaeutilsMixinPlugin` 作为功能开关分发器。

**Player-Facing Part Entry:**
- Location: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`
- Triggers: 玩家右键激活 AE2 part
- Responsibilities: 打开菜单、发布 runtime part 绑定、持久化配置、运行 watcher 与 emit-to-craft 逻辑。

**Pattern Rule UI Entry:**
- Location: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java`
- Triggers: AE2 processing terminal 中 `Ctrl + 左键` 点击输入槽
- Responsibilities: 打开 `ProcessingPatternReplacementScreen`、绘制状态 badge、附加 tooltip。

**Craft Continuation Entry:**
- Location: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java`
- Triggers: AE2 craft confirm 提交作业
- Responsibilities: 模式同步、允许 ignore-missing 提交、协调等待状态。

## Error Handling

**Strategy:** 领域输入尽量做校验与降级；一旦 AE2 内部字段、构造器或 obfuscated 名称不可用，则快速抛出 `IllegalStateException` 暴露兼容性问题。

**Patterns:**
- 配置开关读取失败时走默认值，不阻塞模组启动，逻辑位于 `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java` 与 `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`。
- UI 输入先清洗再提交：阈值由 `MultiLevelEmitterMenu.sanitizeAndClampThreshold(...)` 归一化，表达式由 `MultiLevelEmitterExpressionCompiler.compile(...)` 返回诊断而不是抛异常。
- pattern replacement 草稿用 `ProcessingSlotRuleValidation.java` 清洗非法标签与候选物，部分失效通过 `ProcessingSlotRuleVisualState.PARTIALLY_INVALID` 反馈。
- 反射/AE2 内部访问在 `MultiLevelEmitterRuntimePart.java`, `MultiLevelEmitterRuntimeScreen.java`, `CraftingContinuationPartialSubmit.java` 中直接抛出 `IllegalStateException`，这些点需要优先跟进 AE2 版本变化。

## Cross-Cutting Concerns

**Logging:** 仅在 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java` 做注册级别日志；业务域默认不刷屏。

**Validation:** `src/main/java/git/chexson/chexsonsaeutils/parts/automation/expression/`, `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleValidation.java`, `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java` 共同承担输入校验。

**Authentication:** Not applicable；所有交互都依赖 Minecraft/AE2 既有菜单权限与服务端权威容器，不存在自建身份系统。

## 1.21 + NeoForge Migration Touchpoints

**Bootstrap / Loader API:**
- 需要优先检查 `build.gradle`, `src/main/resources/META-INF/mods.toml`, `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`。
- 原因: 这里直接依赖 ForgeGradle、`@Mod` 生命周期、`DeferredRegister`、`RegistryObject`、`IForgeMenuType`、`ModLoadingContext`、`FMLJavaModLoadingContext`。

**Config / Filesystem API:**
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`。
- 原因: 这些类直接依赖 `ForgeConfigSpec` 与 `FMLPaths.CONFIGDIR`；NeoForge 对配置装配与加载时机的兼容性需要单独验证。

**Menu Opening / Networking:**
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`。
- 原因: 当前用的是 `NetworkHooks.openScreen(...)` 与 AE2 `MenuLocators`；1.21 + NeoForge 如果容器同步协议或 part locator 包名变化，这里会先断。

**AE2 ABI-Sensitive Mixins:**
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/`。
- 原因: 这些 mixin 锁定了 `PatternEncodingTermMenu`, `CraftConfirmMenu`, `CraftingCPUMenu`, `CraftingService`, `CraftingTreeProcess`, `CraftingTreeNode` 等 1.20.1/AE2 15.x 的内部签名。

**Reflection Hotspots:**
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`, `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java`。
- 原因: 这里依赖 `StorageLevelEmitterPart.storageWatcher/craftingWatcher`、obfuscated 字段名 `f_40220_` / `f_40221_` / `f_94102_`、以及 `ExecutingCraftingJob` 私有构造器/私有方法，迁移时最容易因字段名或构造签名变更失效。

**AE2 Style / Resource Layout:**
- 需要优先检查 `src/main/resources/assets/ae2/screens/multi_level_emitter.json`, `src/main/resources/assets/ae2/screens/processing_pattern_replacement.json`, `src/main/resources/assets/ae2/textures/guis/multi_level_emitter.png`, `src/main/resources/assets/chexsonsaeutils/guis/processing_pattern_replacement.png`。
- 原因: 当前 GUI 明确依附 AE2 样式系统与资源查找路径；如果 1.21 版 AE2 改样式 schema 或屏幕加载入口，这些资源需要一起迁。

---

*Architecture analysis: 2026-03-30*
