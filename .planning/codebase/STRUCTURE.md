# Codebase Structure

**Analysis Date:** 2026-03-30

## Directory Layout

```text
[project-root]/
├── build.gradle                                  # ForgeGradle + Mixin + AE2 依赖与运行配置
├── gradle.properties                             # 版本号与构建属性
├── src/
│   ├── main/
│   │   ├── java/git/chexson/chexsonsaeutils/
│   │   │   ├── Chexsonsaeutils.java             # 模组主入口与注册
│   │   │   ├── client/gui/implementations/      # 真实 Screen / AESubScreen 实现
│   │   │   ├── config/                          # 功能开关与启动期配置读取
│   │   │   ├── crafting/                        # continuation 域模型、提交、状态、持久化
│   │   │   ├── menu/implementations/            # 服务端容器与屏幕状态 helper
│   │   │   ├── mixin/ae2/                       # AE2 注入层与 accessor/invoker
│   │   │   ├── parts/automation/                # 多物品发信器部件与纯逻辑
│   │   │   └── pattern/replacement/             # 加工样板替换规则域逻辑
│   │   └── resources/
│   │       ├── META-INF/mods.toml               # Forge 模组元数据
│   │       ├── chexsonsaeutils.mixins.json      # Mixin 清单
│   │       ├── assets/ae2/screens/              # AE2 风格屏幕样式 JSON
│   │       ├── assets/ae2/textures/guis/        # AE2 风格 GUI 贴图
│   │       ├── assets/chexsonsaeutils/          # 语言、模型、本模组 GUI 贴图
│   │       └── data/chexsonsaeutils/            # 配方与 advancement
│   ├── generated/resources/                     # 预留给 data generator 的资源目录
│   └── test/java/git/chexson/chexsonsaeutils/   # 按功能域镜像的测试
└── .planning/codebase/                          # 代码库映射文档输出目录
```

## Directory Purposes

**`src/main/java/git/chexson/chexsonsaeutils/`:**
- Purpose: 所有运行时代码的包根。
- Contains: 模组入口与按功能域拆分的子包。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`

**`src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`:**
- Purpose: 放真实的客户端 GUI 类，不放服务端容器逻辑。
- Contains: `AEBaseScreen` / `AESubScreen` 子类。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`, `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/ProcessingPatternReplacementScreen.java`

**`src/main/java/git/chexson/chexsonsaeutils/config/`:**
- Purpose: 放 feature gate 与 Forge common config 规格。
- Contains: `ForgeConfigSpec`、从配置文件提前读取布尔值的 helper。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`

**`src/main/java/git/chexson/chexsonsaeutils/crafting/`:**
- Purpose: 承载 continuation 的领域逻辑，不直接渲染 GUI。
- Contains: 模式枚举、提交桥接、等待状态快照、`SavedData`。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/crafting/CraftingContinuationMode.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/status/CraftingContinuationStatusService.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/persistence/CraftingContinuationSavedData.java`

**`src/main/java/git/chexson/chexsonsaeutils/menu/implementations/`:**
- Purpose: 放 Multi-Level Emitter 的菜单和屏幕状态 helper。
- Contains: `RuntimeMenu`、客户端动作名、状态快照计算、格式化函数。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`, `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java`

**`src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`:**
- Purpose: 所有 AE2 注入都集中在这里，不把 mixin 与领域逻辑混放。
- Contains: `menu/`、`client/gui/`、`crafting/` 三个注入面向，以及 `ChexsonsaeutilsMixinPlugin.java`
- Key files: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java`

**`src/main/java/git/chexson/chexsonsaeutils/parts/automation/`:**
- Purpose: 放多物品发信器的部件、物品、纯逻辑和 NBT/归一化工具。
- Contains: `PartItem` 工厂、`StorageLevelEmitterPart` 扩展、比较/模糊/红石规则。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterItem.java`, `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterPart.java`, `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`, `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterUtils.java`

**`src/main/java/git/chexson/chexsonsaeutils/parts/automation/expression/`:**
- Purpose: 把多物品发信器表达式解析与格式化独立出去。
- Contains: compiler、formatter、diagnostic、ownership、plan。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/expression/MultiLevelEmitterExpressionCompiler.java`, `src/main/java/git/chexson/chexsonsaeutils/parts/automation/expression/MultiLevelEmitterExpressionFormatter.java`

**`src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/`:**
- Purpose: 放加工样板替换规则的全部领域模型与持久化代码。
- Contains: 规则 record、校验、候选分组服务、decoder、replacement-aware pattern、planning selector。
- Key files: `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementPersistence.java`, `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementDecoder.java`, `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java`

**`src/main/resources/assets/ae2/screens/`:**
- Purpose: 放依附 AE2 样式系统的屏幕 JSON。
- Contains: `multi_level_emitter.json`, `processing_pattern_replacement.json`
- Key files: `src/main/resources/assets/ae2/screens/multi_level_emitter.json`, `src/main/resources/assets/ae2/screens/processing_pattern_replacement.json`

**`src/main/resources/assets/chexsonsaeutils/`:**
- Purpose: 放本模组命名空间下的语言、模型、自有 GUI 纹理。
- Contains: `lang/`, `models/item/`, `guis/`
- Key files: `src/main/resources/assets/chexsonsaeutils/lang/zh_cn.json`, `src/main/resources/assets/chexsonsaeutils/lang/en_us.json`, `src/main/resources/assets/chexsonsaeutils/models/item/multi_level_emitter.json`, `src/main/resources/assets/chexsonsaeutils/guis/processing_pattern_replacement.png`

**`src/main/resources/data/chexsonsaeutils/`:**
- Purpose: 放数据包资源。
- Contains: `recipes/`、`advancements/`
- Key files: `src/main/resources/data/chexsonsaeutils/recipes/network/parts/multi_level_emitter.json`, `src/main/resources/data/chexsonsaeutils/advancements/recipes/network/parts/multi_level_emitter.json`

**`src/test/java/git/chexson/chexsonsaeutils/`:**
- Purpose: 按 `parts`、`pattern`、`crafting`、`support` 镜像主包结构。
- Contains: 行为测试、回归测试、结构契约测试。
- Key files: `src/test/java/git/chexson/chexsonsaeutils/support/RepositoryStructureContractTest.java`, `src/test/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterIntegrationTest.java`, `src/test/java/git/chexson/chexsonsaeutils/pattern/ProcessingPatternReplacementExecutionTest.java`

## Key File Locations

**Entry Points:**
- `build.gradle`: 配置 ForgeGradle、Mixin、AE2 依赖、run profiles、`src/generated/resources` source-set。
- `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`: 模组主入口、注册表和客户端绑定。
- `src/main/resources/META-INF/mods.toml`: Forge 模组元数据和依赖声明。
- `src/main/resources/chexsonsaeutils.mixins.json`: mixin 清单与 plugin 入口。

**Configuration:**
- `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`: common config 规格定义。
- `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java`: continuation 启动期开关读取。
- `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`: replacement 启动期开关读取。

**Core Logic:**
- `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`: 多物品发信器服务端权威状态。
- `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`: 发信器容器、动作分发与 openMenu。
- `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java`: 发信器屏幕状态 helper。
- `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`: 发信器真实 GUI。
- `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java`: 样板替换后的 `IPatternDetails` 实现。
- `src/main/java/git/chexson/chexsonsaeutils/crafting/status/CraftingContinuationStatusService.java`: continuation 状态协调中心。

**Testing:**
- `src/test/java/git/chexson/chexsonsaeutils/support/RepositoryStructureContractTest.java`: 结构/路径/构建约束回归。
- `src/test/java/git/chexson/chexsonsaeutils/parts/`: 发信器菜单、运行时、表达式、生命周期测试。
- `src/test/java/git/chexson/chexsonsaeutils/pattern/`: 样板替换规划/持久化/原生回退测试。
- `src/test/java/git/chexson/chexsonsaeutils/crafting/`: continuation 局部提交测试。

## Naming Conventions

**Files:**
- `*Mixin.java`: 放到 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/.../`，文件名直接反映 AE2 目标类和功能，例如 `CraftingServiceContinuationMixin.java`、`PatternEncodingTermScreenRuleMixin.java`。
- `*Accessor.java`: 只做字段暴露或 invoker，不承载业务逻辑，例如 `CraftingCpuLogicAccessor.java`、`CraftingCPUMenuAccessor.java`。
- `*RuntimePart.java`: 表示真正挂在 AE2 网络上的 part 实现，例如 `MultiLevelEmitterRuntimePart.java`。
- `*Part.java`: 保持纯规则、枚举、Nbt 归一化或计算 helper，例如 `MultiLevelEmitterPart.java`。
- `*Screen.java` 在 `client/gui/implementations/` 下表示真实 GUI；`src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java` 例外，它是 view-model/helper，不是 `Screen` 子类。
- `ProcessingPattern*`、`CraftingContinuation*`、`MultiLevelEmitterExpression*` 用前缀区分功能域，新增文件也应沿用这个域前缀。

**Directories:**
- `implementations/`: 放 concrete menu/screen，不放共享 domain model。
- `mixin/ae2/client/gui/`, `mixin/ae2/menu/`, `mixin/ae2/crafting/`: 按 AE2 目标表面分层；新注入点按目标表面归类，不按功能名单独开顶层目录。
- `parts/automation/expression/`: 发信器表达式专属子域；新增 parser、formatter、plan、diagnostic 放这里，不混入 `menu/` 或 `client/`。
- `pattern/replacement/`: 样板替换的所有纯逻辑都留在这里，mixin 只负责桥接 AE2 生命周期。

## Where to Add New Code

**New Feature:**
- Primary code: 如果是新 AE2 part，先在 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/` 放运行时 part / item / 纯逻辑；菜单放 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/`；真实 GUI 放 `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`。
- Tests: 对应测试放到 `src/test/java/git/chexson/chexsonsaeutils/parts/`，按现有 `MultiLevelEmitter*Test.java` 命名镜像主功能。

**New Component/Module:**
- Implementation: 如果只是扩展 AE2 原生屏幕或菜单，优先把业务逻辑放到 `parts/automation/`、`pattern/replacement/` 或 `crafting/`，然后只在 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/` 写最薄注入层。
- Implementation: 如果新增 processing pattern 规则 UI，子屏幕放 `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`，AE2 终端接线放 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/` 与 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/`。
- Implementation: 如果新增 continuation 状态字段，领域状态和持久化放 `src/main/java/git/chexson/chexsonsaeutils/crafting/status/` 与 `src/main/java/git/chexson/chexsonsaeutils/crafting/persistence/`，GUI 投影仍通过 `mixin/ae2/menu/` + `mixin/ae2/client/gui/`。

**Utilities:**
- Shared helpers: 发信器专属工具放 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterUtils.java` 或 `parts/automation/expression/`；样板替换专属工具放 `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/`；不要把域工具塞进 `Chexsonsaeutils.java`。
- Shared helpers: 需要 AE2 内部字段访问时先判断能否做成 `*Accessor.java`；只有 accessor 不够时再放反射逻辑。

## Special Directories

**`src/generated/resources/`:**
- Purpose: data generator 产物 source-set。
- Generated: Yes
- Committed: 未检测到已提交文件

**`src/main/resources/assets/ae2/`:**
- Purpose: 使用 AE2 命名空间注册自定义屏幕样式和纹理，便于复用 AE2 风格系统。
- Generated: No
- Committed: Yes

**`src/main/resources/data/chexsonsaeutils/`:**
- Purpose: 数据包资源根目录；新物品/部件要在这里补 recipe / advancement。
- Generated: No
- Committed: Yes

**`src/test/java/git/chexson/chexsonsaeutils/support/`:**
- Purpose: 结构契约和测试辅助目录；当主目录迁移时先更新这里的拓扑断言。
- Generated: No
- Committed: Yes

## 1.21 + NeoForge Impact Zones

**Loader / Registry 层:**
- 重点路径: `build.gradle`, `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `src/main/resources/META-INF/mods.toml`
- Guidance: 迁移时先适配注册、事件总线和菜单类型创建，再继续改 mixin；不要先动业务域。

**Forge 专用配置与开屏网络:**
- 重点路径: `src/main/java/git/chexson/chexsonsaeutils/config/`, `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`
- Guidance: `ForgeConfigSpec`、`FMLPaths`、`NetworkHooks.openScreen(...)` 都需要和 NeoForge API 对齐后再验证发信器菜单链路。

**AE2 内部类 / Mixin ABI:**
- 重点路径: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`, `src/main/resources/chexsonsaeutils.mixins.json`
- Guidance: 逐个对照 AE2 1.21 的目标类、字段、构造器、方法签名；accessor/invoker 与 `@Redirect` 通常是首批失效点。

**Reflection Hotspots:**
- 重点路径: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`, `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java`
- Guidance: 这些文件依赖私有字段名、obfuscated 字段名和私有构造器；迁移时优先寻找新的正式 API 或新的 accessor 目标，再决定是否保留反射。

**AE2 GUI Style 资源:**
- 重点路径: `src/main/resources/assets/ae2/screens/`, `src/main/resources/assets/ae2/textures/guis/`, `src/main/resources/assets/chexsonsaeutils/guis/`
- Guidance: 屏幕样式 JSON、纹理尺寸、AE2 `StyleManager`/`AESubScreen` 路径约定需要一起验证；不要只改 Java 代码不改资源。

---

*Structure analysis: 2026-03-30*
