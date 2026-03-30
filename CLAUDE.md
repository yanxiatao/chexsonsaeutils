<!-- GSD:project-start source:PROJECT.md -->
## Project

**Chexson's AE Utils 1.21 NeoForge Migration**

这是一个已有的 brownfield Minecraft AE2 附属模组迁移项目。当前代码库已经在 `Minecraft 1.20.1 + Forge` 上实现了多物品发信器、加工样板替换规则和 crafting continuation，本轮工作的目标是把这些能力迁移到 `1.21.x + NeoForge`，并尽量保持现有行为、配置和持久化语义不退化。

**Core Value:** 在迁移到 `1.21.x + NeoForge` 后，现有全部核心功能必须继续可用，且不能靠牺牲行为一致性来换取“能编译通过”。

### Constraints

- **Tech stack**: 目标必须落在 `Minecraft 1.21.x + NeoForge`，并依赖与之兼容的 AE2 版本
- **Compatibility**: 迁移完成后必须保留当前用户可见功能，不接受“删功能换过编译”
- **Verification**: 现有回归测试和新增迁移契约必须一起作为验收基础
- **Branching**: 迁移工作必须在独立分支进行，保持 `master` 可回退
- **Dependency coupling**: 迁移顺序必须服从 AE2 目标版本 API 可用性，不能盲目先改下游功能
- **Repository hygiene**: 保持 UTF-8 + CRLF 约定，不把编码/换行问题引入迁移噪音
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Java 17 - 模组主逻辑位于 `src/main/java/git/chexson/chexsonsaeutils/`，主入口是 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Groovy DSL - Gradle 构建逻辑位于 `build.gradle`
- Properties - 版本与构建参数集中在 `gradle.properties` 与 `gradle/wrapper/gradle-wrapper.properties`
- TOML - 模组元数据在 `src/main/resources/META-INF/mods.toml`，运行期公共配置文件实例在 `run/config/chexsonsaeutils-common.toml`
- JSON - 资源包、数据包、Mixin 和 GUI 布局定义位于 `src/main/resources/pack.mcmeta`, `src/main/resources/chexsonsaeutils.mixins.json`, `src/main/resources/assets/`, `src/main/resources/data/`
## Runtime
- JVM + Minecraft Forge 用户开发环境 - 目标平台由 `gradle.properties` 固定为 Minecraft `1.20.1`、Forge `47.4.10`、Java `17`
- Mod loader: `javafml` - 由 `src/main/resources/META-INF/mods.toml` 声明，`loaderVersion` 使用 `loader_version_range=[47,)`
- Gradle Wrapper `8.8` - 入口文件为 `gradlew`, `gradlew.bat`，发行版定义在 `gradle/wrapper/gradle-wrapper.properties`
- Lockfile: missing - 仓库中未检测到 Gradle dependency lockfile
## Frameworks
- Minecraft ForgeGradle plugin `[6.0.16,6.2)` - 在 `build.gradle` 中提供 Forge userdev、run 配置和 reobf 打包
- Minecraft Forge `1.20.1-47.4.10` - 主 Mod API 与生命周期宿主，入口注册写在 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Applied Energistics 2 Forge `15.4.5` - 核心集成对象；部件、菜单、GUI、合成与 Pattern API 广泛出现在 `src/main/java/git/chexson/chexsonsaeutils/parts/`, `src/main/java/git/chexson/chexsonsaeutils/menu/`, `src/main/java/git/chexson/chexsonsaeutils/client/gui/`, `src/main/java/git/chexson/chexsonsaeutils/crafting/`, `src/main/java/git/chexson/chexsonsaeutils/pattern/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- GuideME `20.1.7` - 在 `build.gradle` 中作为实现依赖声明，用于补齐 AE2 相关运行环境
- Mojang official mappings `1.20.1` - 映射配置在 `gradle.properties`
- JUnit Jupiter `5.10.2` - 依赖与 `useJUnitPlatform()` 配置写在 `build.gradle`，测试源码位于 `src/test/java/git/chexson/chexsonsaeutils/`
- Gradle Wrapper `8.8` - 本地构建入口
- Sponge Mixingradle `0.7-SNAPSHOT` - 在 `build.gradle` 的 `buildscript` 中接入
- Sponge Mixin `0.8.5` - 注解处理器与运行时依赖；配置文件为 `src/main/resources/chexsonsaeutils.mixins.json`
- Foojay toolchain resolver `0.7.0` - 在 `settings.gradle` 中配置 Java toolchain 解析
- IntelliJ/Eclipse integration plugins - 在 `build.gradle` 的 `plugins` 块中启用 `idea` 与 `eclipse`
## Minecraft Mod Targets
- 单目标平台是 Forge 生态，不是多 Loader 工程；未检测到 `src/main/resources/fabric.mod.json`、`quilt.mod.json` 或 `src/main/resources/META-INF/neoforge.mods.toml`
- 运行依赖链是 `Minecraft 1.20.1` + `Forge 47.x` + `AE2 15.x`，其版本源分别位于 `gradle.properties` 和 `src/main/resources/META-INF/mods.toml`
- 主配置文件是 `src/main/resources/chexsonsaeutils.mixins.json`，声明 `required=true`、`compatibilityLevel=JAVA_17`、`refmap=chexsonsaeutils.refmap.json`
- 条件注入插件是 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java`，启动期依据 `craftingContinuationEnabled` 与 `processingPatternReplacementEnabled` 控制 AE2 Mixin 是否启用
- 已检测到 16 个面向 AE2 的 mixin/accessor，分布在 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/`
- 模组元数据与资源包根位于 `src/main/resources/META-INF/mods.toml` 和 `src/main/resources/pack.mcmeta`
- 自有命名空间资源位于 `src/main/resources/assets/chexsonsaeutils/`，包括 `lang/`, `models/item/`, `guis/`
- 直接覆盖/挂接 AE2 命名空间的 GUI 资源位于 `src/main/resources/assets/ae2/screens/` 与 `src/main/resources/assets/ae2/textures/guis/`
- 数据包内容位于 `src/main/resources/data/chexsonsaeutils/recipes/` 和 `src/main/resources/data/chexsonsaeutils/advancements/`
- 数据生成输出目录已在 `build.gradle` 配置为 `src/generated/resources/`，目录存在但当前为空
- Mod 主入口：`src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Client GUI 绑定：`MenuScreens.register(...)` 位于 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Menu 网络打开入口：`NetworkHooks.openScreen(...)` 位于 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`
- Gradle run 配置位于 `build.gradle`：`client`, `server`, `gameTestServer`, `data`
- JUnit 测试入口是 Gradle `test` 任务；未在 `src/main/java/` 或 `src/test/java/` 中检测到实际 `@GameTest` 用例
## Key Dependencies
- `net.minecraftforge:forge:${minecraft_version}-${forge_version}` - 由 `build.gradle` 声明，是整个 mod 的 loader、注册表、网络与配置宿主
- `appeng:appliedenergistics2-forge:${ae2_version}` - 由 `build.gradle` 声明，是全部功能的外部 API 与被扩展目标
- `org.spongepowered:mixin:0.8.5` - 由 `build.gradle` 声明，支持 `src/main/resources/chexsonsaeutils.mixins.json` 和 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- `org.appliedenergistics:guideme:${guideme_version}` - 由 `build.gradle` 声明，补充 AE2 运行时依赖
- `mezz.jei:jei-${minecraft_version}-forge:${jei_version}` - 在 `build.gradle` 中声明为 `runtimeOnly`，用于开发运行时的 JEI 联动
- `org.junit.jupiter:junit-jupiter-api:5.10.2` / `org.junit.jupiter:junit-jupiter-engine:5.10.2` - 在 `build.gradle` 中声明测试运行时
- Repo-local exploded references - `lib/appliedenergistics2-forge-15.4.5_mapped_official_1.20.1.jar/`, `lib/forge-1.20.1-47.4.10_mapped_official_1.20.1-recomp.jar/`, `lib/Applied-Energistics-2-forge-1.20.1/` 为迁移时可直接对照的本地参考材料
## Configuration
- 构建期版本与坐标统一来自 `gradle.properties`；仓库中未检测到 `.env` 或其他环境变量文件
- Forge 公共配置规范定义在 `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`
- 运行期公共配置文件实例位于 `run/config/chexsonsaeutils-common.toml`，当前包含 `craftingContinuationEnabled` 和 `processingPatternReplacementEnabled`
- 启动期 Feature Gate 会优先读取 `chexsonsaeutils.configDir` 系统属性指定的目录，否则回落到 `FMLPaths.CONFIGDIR`; 实现在 `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java` 与 `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`
- 主要构建文件是 `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`
- `.editorconfig` 明确要求 `charset = utf-8` 与 `end_of_line = crlf`
- `build.gradle` 为 Mixin refmap 启用了 `mixin.env.remapRefMap=true` 和 `${projectDir}/build/createSrgToMcp/output.srg`
- `build.gradle` 配置了 `accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')`，但该文件当前未检测到
- `processResources` 会对 `src/main/resources/META-INF/mods.toml` 与 `src/main/resources/pack.mcmeta` 做属性展开
## Platform Requirements
- 需要 JDK `17`；`build.gradle` 使用 Java toolchain，`settings.gradle` 使用 Foojay resolver
- Gradle 运行默认禁用 daemon，并在 `gradle.properties` 中要求 `org.gradle.jvmargs=-Xmx3G`
- 本地开发运行目录使用 `run/`; 当前已检测到 `run/config/`, `run/logs/`, `run/crash-reports/`, `run/saves/`
- 常用入口命令与 `README.md` 一致：`gradlew.bat build`, `gradlew.bat test`, `gradlew.bat runClient`, `gradlew.bat runServer`
- 产物是经 `reobfJar` 处理的 Forge mod JAR；打包流程定义在 `build.gradle`
- 生产侧需要 `Minecraft 1.20.1`, Forge/FML `47+`, AE2 `15+`; 依赖边界写在 `src/main/resources/META-INF/mods.toml`
- 资源包版本 `pack_format=15` 写在 `src/main/resources/pack.mcmeta`
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## 命名模式
- Java 源文件统一使用 `PascalCase.java`，按职责放在小写包路径下，例如 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`
- 测试文件统一使用 `*Test.java`，回归/契约/集成语义会直接编码进文件名，例如 `MultiLevelEmitterLifecycleIntegrationTest.java`、`ProcessingPatternReplacementConfigGateTest.java`、`RepositoryStructureContractTest.java`
- 资源文件使用 AE2 / Minecraft 约定的小写蛇形或路径式命名，例如 `src/main/resources/assets/ae2/screens/multi_level_emitter.json`、`src/main/resources/data/chexsonsaeutils/recipes/network/parts/multi_level_emitter.json`
- 生产代码方法使用 `lowerCamelCase`，名称尽量直陈行为，例如 `registerProcessingPatternReplacementDecoder()`、`updateThresholdFromUi()`、`evaluateConfiguredOutput()`
- 测试方法也使用 `lowerCamelCase`，但命名更偏行为说明句，例如 `defaultsBackfillThresholdAndComparisonForMissingSlotMetadata()`、`extractsBothPlannedAndPreviouslyMissingInitialInputs()`
- 事件/动作型方法通常带动词前缀，如 `register*`、`apply*`、`cycle*`、`read*`、`write*`、`resolve*`
- 局部变量与字段统一使用 `lowerCamelCase`
- 常量使用 `UPPER_SNAKE_CASE`，尤其集中在 GUI 和 menu 协议常量里，例如 `ACTION_COMMIT_THRESHOLD`、`GROUPED_CONTENT_HEIGHT`
- 布尔值命名偏可读语义，例如 `cardCapabilityStateInitialized`、`processingPatternReplacementEnabled`
- 类、枚举、record、接口统一使用 `PascalCase`
- 接口不会加 `I` 前缀，遵循上游生态已有命名即可，例如 `ProcessingSlotRuleHost`
- 小型跨层载荷优先用 `record` 表达，例如 `ThresholdPayload`、`ExpressionPayload`
## 代码风格
- Java 使用 4 空格缩进，花括号与声明同行
- 字符串统一使用双引号
- 语句结尾保留分号
- 代码中广泛使用 `final`、`static final`、不可变 `List.copyOf(...)` / `Map.of(...)`
- `.editorconfig` 明确要求 UTF-8 与 CRLF，见 `/.editorconfig`
- 纯逻辑优先抽成 `final` 工具类 + `static` 方法，例如 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterPart.java`
- 和 AE2 运行态强耦合的状态放进 runtime 类，例如 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`
- UI 状态整形与容器协议拆层处理：菜单类负责服务端动作转发，屏幕辅助类负责显示层格式化与校验
## 导入组织
- 组与组之间通常留一个空行
- 同组内基本按语义聚集，不强依赖机械字母序
- 只有在文件足够复杂时才保留多个内部导入分组；小文件通常保持单组
## 错误处理
- 持久化数据、旧版本字段、用户输入优先做“安全降级”而不是抛异常，例如 `ComparisonMode.fromPersisted(...)`、`MatchingMode.fromPersisted(...)`
- 兼容层或反射层一旦无法继续，应立即抛出 `IllegalStateException` 暴露 ABI 断裂，例如 `CraftingContinuationPartialSubmit.java` 与 `MultiLevelEmitterRuntimePart.java`
- 菜单与 GUI 输入先规范化再写回运行态，例如 `sanitizeAndClampThreshold(...)`
- 反射字段、构造器、私有方法签名失效
- 关键运行时绑定缺失，例如菜单类型供应器为空
- 无法维持服务器权威状态时
- 读取旧 NBT / 非法配置 / 失效枚举值
- 特性卡片缺失后将功能模式回退到安全默认值
- 客户端侧仅用于展示的状态无法解析时
## 日志
- 模组启动层使用 `SLF4J` / `LogUtils.getLogger()`，见 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- 极少数 AE2 内部态异常使用 `AELog.warn(...)`，见 `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java`
- 日志主要用于注册和异常态观测，不在正常热路径刷屏
- 生产逻辑更偏向可测试的返回值与状态同步，而不是大量日志驱动
## 注释
- 解释“为什么必须这样兼容”而不是“代码在做什么”
- 标记 AE2/Forge 兼容背景、ThreadLocal bridge、Mixin 限制、历史兼容语义
- 在反射或显式 workaround 处说明原因
- 注释短而直接，不写样板废话
- Javadoc 只在需要强调职责边界时使用，例如 `MultiLevelEmitterRuntimePart` 的类注释
## 函数设计
- 对外入口方法通常短小，复杂细节拆到私有 helper
- 倾向显式守卫式返回，减少深层嵌套
- 状态变更通常围绕“标准化 -> 写入 -> refresh/reconfigure”这一固定节奏
- 输入校验优先在边界完成
- 小载荷优先 `record`，复杂返回优先显式 value object，例如 `AggregationResult`、`SlotEvaluation`
- 在纯逻辑层避免隐藏副作用，便于测试直接调用
## 模块设计
- bootstrap 只负责注册，不沉淀业务细节，见 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- `parts/automation/`、`pattern/replacement/`、`crafting/` 是三个核心功能域
- `mixin/ae2/` 只做接线、注入、accessor；真正业务尽量放回普通包，减少迁移时的 blast radius
- 新的纯规则判断优先进入普通 Java 类，不要先写进 mixin
- 新的 GUI 动作先在 menu 协议层定义动作常量与 payload，再接到 screen
- 新的兼容开关优先进入 `config/` 与 `ChexsonsaeutilsMixinPlugin`
- 新的测试优先写行为回归或结构契约，而不是只做“存在性”断言
## 1.21 + NeoForge 迁移时应保持的风格
- 保留“业务逻辑在普通类、平台绑定在外层”的结构，不要把 NeoForge 适配代码扩散到纯逻辑域
- 若必须替换 Forge / AE2 API，优先改 bootstrap、menu 网络桥、config、mixin 接缝，不要先改测试契约描述
- 迁移后继续维持 UTF-8 + CRLF 和现有包拓扑，避免让功能迁移和仓库重组耦合在同一次变更中
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## Pattern Overview
- 用 `git/chexson/chexsonsaeutils/Chexsonsaeutils.java` 做最薄的模组入口，只负责注册 `Item`、`MenuType`、Creative Tab、客户端绑定与可选解码器注入。
- 把业务拆成三个独立功能域：`parts/automation` 负责多物品发信器，`pattern/replacement` 负责加工样板替换规则，`crafting` 负责 AE2 crafting continuation。
- 把对 AE2 的侵入集中在 `mixin/ae2/`，领域代码尽量放在普通包中；新增 AE2 行为时先放域逻辑，再用 mixin 做接线。
- 资源路径与运行时边界保持一致：AE2 风格屏幕样式放在 `src/main/resources/assets/ae2/screens/`，本模组纹理/语言/配方放在 `src/main/resources/assets/chexsonsaeutils/` 与 `src/main/resources/data/chexsonsaeutils/`。
- 两个可选功能包都由 `src/main/java/git/chexson/chexsonsaeutils/config/` 提供启动期开关，`src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java` 负责按功能选择是否应用 mixin。
## Layers
- Purpose: 提供 Forge 生命周期入口、注册表装配、客户端屏幕绑定、AE2 模式解码器注入。
- Location: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Contains: `@Mod` 入口、`DeferredRegister`、`MenuScreens.register(...)`、`PatternDetailsHelper` 解码器插桩。
- Depends on: Forge 事件总线、AE2 `Upgrades`/`PatternDetailsHelper`、`menu/implementations`、`parts/automation`、`pattern/replacement`。
- Used by: Forge 模组加载器、客户端 setup、common setup。
- Purpose: 把 continuation 与 processing pattern replacement 作为独立功能包开关，并在 mixin 装配前读取持久化配置。
- Location: `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`
- Contains: `ForgeConfigSpec`、从 `chexsonsaeutils-common.toml` 读取启动期布尔值的兼容逻辑。
- Depends on: Forge `ModConfig`、`FMLPaths`、`mixin/ae2/ChexsonsaeutilsMixinPlugin.java`。
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java`
- Purpose: 实现多槽位、多比较模式、模糊匹配、crafting 状态联动、表达式判定的 AE2 部件。
- Location: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/`
- Contains: `MultiLevelEmitterItem.java`, `MultiLevelEmitterPart.java`, `MultiLevelEmitterRuntimePart.java`, `MultiLevelEmitterUtils.java`
- Depends on: AE2 `StorageLevelEmitterPart`、`ConfigInventory`、`IStackWatcher`、`ICraftingProvider`，以及 `parts/automation/expression/`。
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `menu/implementations/MultiLevelEmitterMenu.java`, `client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`
- Purpose: 承接服务端容器、客户端动作、屏幕状态快照与输入校验。
- Location: `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/`
- Contains: `MultiLevelEmitterMenu.java` 内的 `RuntimeMenu` 容器；`MultiLevelEmitterScreen.java` 作为 UI 状态/格式化/校验 helper。
- Depends on: AE2 `AEBaseMenu`、`MenuLocators`、`parts/automation/MultiLevelEmitterRuntimePart.java`, `parts/automation/expression/`
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`
- Purpose: 渲染真实屏幕、处理按钮/输入框/滚动条/子界面交互。
- Location: `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`
- Contains: `MultiLevelEmitterRuntimeScreen.java`, `ProcessingPatternReplacementScreen.java`
- Depends on: AE2 `AEBaseScreen`/`AESubScreen`/样式系统、`menu/implementations/`, `pattern/replacement/`
- Used by: `git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java`
- Purpose: 给 AE2 加工样板的输入槽附加“按标签/显式候选物”替换规则，并让规划与执行都感知这些候选集合。
- Location: `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/`
- Contains: 规则载荷、校验、NBT 持久化、候选组查询、AE2 `IPatternDetailsDecoder`、`ReplacementAwareProcessingPattern`
- Depends on: AE2 `IPatternDetails`/`AEProcessingPattern`、Minecraft 物品标签注册表。
- Used by: `mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java`, `client/gui/implementations/ProcessingPatternReplacementScreen.java`, `mixin/ae2/crafting/CraftingTreeProcessReplacementMixin.java`, `mixin/ae2/crafting/CraftingTreeNodeReplacementMixin.java`
- Purpose: 在 AE2 craft confirm 与 crafting CPU 生命周期中插入 ignore-missing 模式、等待状态追踪、持久化和 GUI 投影。
- Location: `src/main/java/git/chexson/chexsonsaeutils/crafting/`
- Contains: `CraftingContinuationMode.java`, `submit/`, `status/`, `persistence/`
- Depends on: AE2 crafting internals、`SavedData`、多个 accessor mixin。
- Used by: `mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java`, `mixin/ae2/crafting/CraftingServiceContinuationMixin.java`, `mixin/ae2/menu/CraftingCPUMenuContinuationMixin.java`, `mixin/ae2/client/gui/`
- Purpose: 把三个功能域挂接到 AE2 原生菜单、GUI、规划树与 CPU 生命周期。
- Location: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- Contains: `menu/`, `client/gui/`, `crafting/` 下的 mixin、accessor、invoker，以及 `ChexsonsaeutilsMixinPlugin.java`
- Depends on: AE2 内部类与字段名、Sponge Mixin、配置开关。
- Used by: `src/main/resources/chexsonsaeutils.mixins.json`
## Data Flow
- 服务端权威状态在 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`。
- 菜单打开期间的运行时绑定依赖 `ThreadLocal`，路径在 `publishForMenuOpen(...)` / `consumePublishedMenuRuntime()`。
- 客户端屏幕不缓存长期状态，所有可持久化字段都回写到 part NBT。
- 可持久化规则全部存放在 encoded pattern NBT。
- 终端打开期间的草稿状态只存在 `PatternEncodingTermMenuRuleMixin` 的 `Map<Integer, ProcessingSlotRuleDraft>` 中。
- 提交模式在一次 `submitJob(...)` 调用链中用 `CraftingContinuationSubmitBridge` 的 `ThreadLocal` 传递。
- 长期状态在 `CraftingContinuationSavedData`，运行态快照在 `CraftingContinuationStatusService` 的 `trackedJobs` 与 `observedAvailableWaitingStacks`。
- GUI 同步通过 `CraftingCPUMenuContinuationMixin` 的 `@GuiSync` 字段完成。
## Key Abstractions
- Purpose: 把注册、客户端绑定、可选解码器注入控制在一个入口内。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Pattern: 薄入口；不要把业务逻辑直接写进 bootstrap。
- Purpose: 管理多物品发信器的配置、NBT、watcher、AE2 网络评估与菜单打开。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`, `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterPart.java`
- Pattern: `*RuntimePart` 持有运行态；`*Part` 保持纯逻辑/枚举/归一化函数。
- Purpose: 分离“容器通信”和“纯 UI 状态计算”。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`, `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java`
- Pattern: `RuntimeMenu` 负责客户端动作与服务端状态写回；`MultiLevelEmitterScreen.java` 不是 `Screen` 子类，而是 view-model / validator。
- Purpose: 让 AE2 终端 mixin 和子屏幕在不暴露 AE2 细节的前提下交换规则草稿。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleHost.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java`
- Pattern: 用接口隔离 mixin 持有的菜单状态与 GUI。
- Purpose: 把 encoded pattern 的单输入扩展为候选输入集合，并让规划与执行选择代表项。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java`, `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementDecoder.java`
- Pattern: 先解码为自定义 `IPatternDetails`，再用 mixin 改写 AE2 planning/execution。
- Purpose: 把 GUI 选择的 continuation 模式带入提交链路，并在 CPU 生命周期里维护等待快照。
- Examples: `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationSubmitBridge.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/status/CraftingContinuationStatusService.java`
- Pattern: 一次提交范围内用 `ThreadLocal`，跨 tick / 重进世界用 `SavedData`。
## Entry Points
- Location: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Triggers: Forge 加载 `@Mod("chexsonsaeutils")`
- Responsibilities: 注册物品/菜单/Creative Tab、common/client setup、MultiLevelEmitter 绑定、processing pattern decoder 注入。
- Location: `src/main/resources/chexsonsaeutils.mixins.json`
- Triggers: Sponge Mixin 初始化
- Responsibilities: 声明 `menu`、`client/gui`、`crafting` 三类 AE2 注入点，并绑定 `ChexsonsaeutilsMixinPlugin` 作为功能开关分发器。
- Location: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`
- Triggers: 玩家右键激活 AE2 part
- Responsibilities: 打开菜单、发布 runtime part 绑定、持久化配置、运行 watcher 与 emit-to-craft 逻辑。
- Location: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java`
- Triggers: AE2 processing terminal 中 `Ctrl + 左键` 点击输入槽
- Responsibilities: 打开 `ProcessingPatternReplacementScreen`、绘制状态 badge、附加 tooltip。
- Location: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java`
- Triggers: AE2 craft confirm 提交作业
- Responsibilities: 模式同步、允许 ignore-missing 提交、协调等待状态。
## Error Handling
- 配置开关读取失败时走默认值，不阻塞模组启动，逻辑位于 `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java` 与 `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`。
- UI 输入先清洗再提交：阈值由 `MultiLevelEmitterMenu.sanitizeAndClampThreshold(...)` 归一化，表达式由 `MultiLevelEmitterExpressionCompiler.compile(...)` 返回诊断而不是抛异常。
- pattern replacement 草稿用 `ProcessingSlotRuleValidation.java` 清洗非法标签与候选物，部分失效通过 `ProcessingSlotRuleVisualState.PARTIALLY_INVALID` 反馈。
- 反射/AE2 内部访问在 `MultiLevelEmitterRuntimePart.java`, `MultiLevelEmitterRuntimeScreen.java`, `CraftingContinuationPartialSubmit.java` 中直接抛出 `IllegalStateException`，这些点需要优先跟进 AE2 版本变化。
## Cross-Cutting Concerns
## 1.21 + NeoForge Migration Touchpoints
- 需要优先检查 `build.gradle`, `src/main/resources/META-INF/mods.toml`, `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`。
- 原因: 这里直接依赖 ForgeGradle、`@Mod` 生命周期、`DeferredRegister`、`RegistryObject`、`IForgeMenuType`、`ModLoadingContext`、`FMLJavaModLoadingContext`。
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java`, `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`。
- 原因: 这些类直接依赖 `ForgeConfigSpec` 与 `FMLPaths.CONFIGDIR`；NeoForge 对配置装配与加载时机的兼容性需要单独验证。
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`。
- 原因: 当前用的是 `NetworkHooks.openScreen(...)` 与 AE2 `MenuLocators`；1.21 + NeoForge 如果容器同步协议或 part locator 包名变化，这里会先断。
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/`。
- 原因: 这些 mixin 锁定了 `PatternEncodingTermMenu`, `CraftConfirmMenu`, `CraftingCPUMenu`, `CraftingService`, `CraftingTreeProcess`, `CraftingTreeNode` 等 1.20.1/AE2 15.x 的内部签名。
- 需要优先检查 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`, `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java`, `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java`。
- 原因: 这里依赖 `StorageLevelEmitterPart.storageWatcher/craftingWatcher`、obfuscated 字段名 `f_40220_` / `f_40221_` / `f_94102_`、以及 `ExecutingCraftingJob` 私有构造器/私有方法，迁移时最容易因字段名或构造签名变更失效。
- 需要优先检查 `src/main/resources/assets/ae2/screens/multi_level_emitter.json`, `src/main/resources/assets/ae2/screens/processing_pattern_replacement.json`, `src/main/resources/assets/ae2/textures/guis/multi_level_emitter.png`, `src/main/resources/assets/chexsonsaeutils/guis/processing_pattern_replacement.png`。
- 原因: 当前 GUI 明确依附 AE2 样式系统与资源查找路径；如果 1.21 版 AE2 改样式 schema 或屏幕加载入口，这些资源需要一起迁。
<!-- GSD:architecture-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
