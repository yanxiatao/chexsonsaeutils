<!-- GSD:project-start source:PROJECT.md -->
## Project

**Chexson's AE Utils 1.21 NeoForge Migration**

这是一个已有代码基础的 brownfield Minecraft AE2 附属模组迁移项目，不是从零开始的新模组开发。仓库已经在 `Minecraft 1.20.1 + Forge` 上实现多级发信器、加工样板替换规则和 crafting continuation，本轮目标是把这些能力迁移到 `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21`，并尽量保持既有行为、配置与持久化语义不退化。

### 核心价值

迁移后的目标不是“只是能编译通过”，而是在目标平台上继续保留现有全部核心能力与用户可见语义。

### 当前状态

- Phase 01 到 Phase 04 已闭环，目标平台基线、多级发信器、processing pattern replacement 与 crafting continuation 都已完成迁移。
- 当前正在执行 Phase 06，用于统一仓库文档语言与编码基线。
- 功能层面的下一项工程目标仍是 Phase 05：刷新回归、补平台验证并收口 verification debt。

### 项目约束

- **技术栈：** 目标必须落在 `Minecraft 1.21.x + NeoForge`，并依赖兼容 AE2 版本。
- **兼容性：** 迁移完成后必须保留当前用户可见功能，不接受“删功能换过编译”。
- **验证：** 现有回归测试和迁移契约必须一起作为验收基础。
- **分支策略：** 迁移工作必须在独立分支推进，保持 `master` 可回退。
- **依赖耦合：** 迁移顺序必须服从 AE2 目标版本 API 可用性。
- **仓库卫生：** 保持 UTF-8 无 BOM + CRLF，不把编码与换行问题引入迁移噪音。

### 已验证能力

- Multi-level emitter 已具备多槽位监控、阈值/比较模式、模糊匹配、crafting 联动与表达式判定能力。
- Processing pattern replacement 已具备终端规则编辑、规则持久化与 planning / execution 侧替换语义。
- Crafting continuation 已具备确认界面模式切换、部分提交、等待分支跟踪与 CPU 状态投影。
- continuation / processing replacement 的启动期 feature gate 已恢复。
- Phase 02、Phase 03、Phase 04 都已经拥有自动回归门禁与 execute-phase 验证证据。
<!-- GSD:project-end -->


<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

### 语言

- **主语言：** Java 21。模组主逻辑位于 `src/main/java/git/chexson/chexsonsaeutils/`，主入口是 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`。
- **构建脚本：** Groovy DSL，核心文件是 `build.gradle`。
- **版本与构建参数：** 由 `gradle.properties` 与 `gradle/wrapper/gradle-wrapper.properties` 集中管理。
- **元数据与配置：** `src/main/templates/META-INF/neoforge.mods.toml` 负责 NeoForge 元数据模板，`run/config/chexsonsaeutils-common.toml` 提供运行期公共配置实例。
- **资源与数据：** JSON 资源、数据包、Mixin 与 GUI 布局位于 `src/main/resources/pack.mcmeta`, `src/main/resources/chexsonsaeutils.mixins.json`, `src/main/resources/assets/`, `src/main/resources/data/`。

### 运行时

- **目标平台：** `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21`。
- **Mod Loader：** `javafml`，声明写在 `src/main/templates/META-INF/neoforge.mods.toml`。
- **构建入口：** Gradle Wrapper `8.8`，入口文件为 `gradlew` 与 `gradlew.bat`。
- **锁文件状态：** 当前未检测到 Gradle dependency lockfile。
- **本地运行目录：** 使用 `run/`，当前已检测到 `run/config/`, `run/logs/`, `run/crash-reports/`, `run/saves/`。

### 框架与工具

- **NeoForge ModDev plugin `2.0.141`：** 提供 userdev、run 配置、单元测试启用与生成元数据集成。
- **NeoForge `21.1.222`：** 作为主 Mod API 与生命周期宿主。
- **Applied Energistics 2 `19.2.17`：** 核心集成对象，覆盖部件、菜单、GUI、合成与 Pattern API。
- **GuideME `21.1.1`：** 通过 `localRuntime` 补齐 AE2 运行环境。
- **JUnit Jupiter `5.10.2`：** 当前测试基线。
- **Sponge Mixin `0.8.5`：** 支撑 `src/main/resources/chexsonsaeutils.mixins.json` 与 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`。
- **Foojay toolchain resolver `0.7.0`：** 负责 Java toolchain 解析。
- **IDE 集成：** `build.gradle` 启用了 `idea` 与 `eclipse` 插件。

### Mod 目标与资源触点

- 单目标平台是 NeoForge，不是多 Loader 工程；仓库已存在 `src/main/templates/META-INF/neoforge.mods.toml`。
- 运行依赖链固定为 `Minecraft 1.21.1` + `NeoForge 21.1.222` + `AE2 19.2.17`。
- 主 Mixin 配置文件是 `src/main/resources/chexsonsaeutils.mixins.json`。
- `ChexsonsaeutilsMixinPlugin.java` 依据 `craftingContinuationEnabled` 与 `processingPatternReplacementEnabled` 控制 AE2 Mixin 是否启用。
- 当前已检测到 16 个面向 AE2 的 mixin/accessor，分布在 `mixin/ae2/menu/`, `mixin/ae2/client/gui/`, `mixin/ae2/crafting/`。
- 自有命名空间资源位于 `src/main/resources/assets/chexsonsaeutils/`。
- 直接覆盖或挂接 AE2 命名空间的屏幕资源位于 `src/main/resources/assets/ae2/screens/` 与 `src/main/resources/assets/ae2/textures/guis/`。
- 数据包内容位于 `src/main/resources/data/chexsonsaeutils/recipes/` 与 `src/main/resources/data/chexsonsaeutils/advancements/`。
- 数据生成输出目录已在 `build.gradle` 中配置为 `src/generated/resources/`。

### 关键依赖

- `net.neoforged:neoforge:${neo_version}`：loader、注册表、网络与配置宿主。
- `org.appliedenergistics:appliedenergistics2:${ae2_version}`：全部功能的外部 API 与被扩展目标。
- `org.spongepowered:mixin:0.8.5`：Mixin 运行时与注解处理器。
- `org.appliedenergistics:guideme:${guideme_version}`：AE2 运行时补充依赖。
- `org.junit.jupiter:junit-jupiter-api:5.10.2` / `org.junit.jupiter:junit-jupiter-engine:5.10.2`：测试运行时。
- 本地 exploded references：`lib/appliedenergistics2-forge-15.4.5_mapped_official_1.20.1.jar/`, `lib/forge-1.20.1-47.4.10_mapped_official_1.20.1-recomp.jar/`, `lib/Applied-Energistics-2-forge-1.20.1/`，用于迁移时对照旧实现。

### 配置与环境

- 构建期版本与坐标统一来自 `gradle.properties`；仓库中未检测到 `.env`。
- NeoForge 公共配置规范定义在 `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`。
- 启动期 feature gate 优先读取 `chexsonsaeutils.configDir`，否则回落到 `FMLPaths.CONFIGDIR`。
- `.editorconfig` 明确要求 `charset = utf-8` 与 `end_of_line = crlf`。
- `generateModMetadata` 会从 `src/main/templates/` 生成 `META-INF/neoforge.mods.toml`。
- `sourceSets.main.resources` 额外包含 `src/generated/resources` 与 `generateModMetadata` 输出目录。
- `build.gradle` 已移除旧 Forge access transformer 声明，不再沿用旧 `mods.toml` 路径。

### 常用入口

- `gradlew.bat build`
- `gradlew.bat test`
- `gradlew.bat runClient`
- `gradlew.bat runServer`
- `build.gradle` 中的 `client`, `server`, `gameTestServer`, `data` run 配置继续可用。
<!-- GSD:stack-end -->


<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

### 命名

- Java 源文件统一使用 `PascalCase.java`，按职责放在小写包路径下。
- 生产代码方法使用 `lowerCamelCase`，名称尽量直陈行为，例如 `registerProcessingPatternReplacementDecoder()`、`evaluateConfiguredOutput()`。
- 测试方法也使用 `lowerCamelCase`，但命名更偏行为说明句。
- 常量使用 `UPPER_SNAKE_CASE`，尤其集中在 GUI 与 menu 协议常量中。
- 接口不加 `I` 前缀，遵循上游生态已有命名，例如 `ProcessingSlotRuleHost`。
- 小型跨层载荷优先用 `record` 表达，例如 `ThresholdPayload`、`ExpressionPayload`。

### 代码风格

- Java 使用 4 空格缩进，花括号与声明同行。
- 字符串统一使用双引号，语句结尾保留分号。
- 广泛使用 `final`、`static final`、`List.copyOf(...)`、`Map.of(...)`。
- `.editorconfig` 规定全部文本文件使用 UTF-8 与 CRLF。
- 纯逻辑优先抽成 `final` 工具类 + `static` 方法；和 AE2 运行态强耦合的状态放进 runtime 类。
- UI 状态整形与容器协议拆层处理：菜单类负责服务端动作转发，屏幕辅助类负责显示层格式化与校验。

### 导入与模块边界

- 依赖导入顺序通常是第三方 / 上游、当前项目内部包、`java.*` / `javax.*`。
- `parts/automation/`、`pattern/replacement/`、`crafting/` 是三个核心功能域。
- `mixin/ae2/` 只做接线、注入、accessor；真正业务尽量放回普通包，减少迁移时的 blast radius。
- 新的纯规则判断优先进入普通 Java 类，不要先写进 mixin。
- 新的 GUI 动作优先先在 menu 协议层定义动作常量与 payload，再接到 screen。
- bootstrap 只负责注册，不沉淀业务细节。

### 错误处理

- 持久化数据、旧版本字段、用户输入优先做安全降级，而不是直接抛异常。
- 兼容层或反射层一旦无法继续，应立即抛出 `IllegalStateException` 暴露 ABI 断裂。
- 菜单与 GUI 输入先规范化再写回运行态，例如 `sanitizeAndClampThreshold(...)`。
- 客户端仅用于展示的状态解析失败时优先兜底，而不是影响服务端权威状态。
- 与 AE2 内部字段、构造器、私有方法签名强耦合的位置，必须优先保留显式失败语义。

### 注释与迁移风格

- 注释只解释“为什么必须这样兼容”，不写样板废话。
- 保留“业务逻辑在普通类、平台绑定在外层”的结构，不把 NeoForge 适配代码扩散到纯逻辑域。
- 若必须替换 Forge / AE2 API，优先修改 bootstrap、menu 网络桥、config 与 mixin 接缝，不先改测试契约描述。
- 迁移过程中继续维持 UTF-8 + CRLF 和现有包拓扑，不把功能迁移与仓库重组耦合到同一次变更。
- 新增测试优先写行为回归或结构契约，而不是只做“存在性”断言。
<!-- GSD:conventions-end -->


<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

### 模式概览

- 整体形态是 NeoForge 模组引导 + AE2 深度集成 + Mixin 驱动扩展架构。
- `Chexsonsaeutils.java` 保持为最薄的模组入口，只负责注册、客户端绑定与可选解码器注入。
- 业务拆成三个独立功能域：`parts/automation`、`pattern/replacement`、`crafting`。
- 对 AE2 的侵入集中在 `mixin/ae2/`，领域代码尽量放在普通包中。
- 资源路径与运行时边界保持一致：AE2 风格屏幕样式放在 `src/main/resources/assets/ae2/`，模组自有资源位于 `src/main/resources/assets/chexsonsaeutils/` 与 `src/main/resources/data/chexsonsaeutils/`。

### 分层结构

**Bootstrap / Registration**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- 职责：NeoForge 生命周期入口、注册表装配、客户端屏幕绑定、AE2 模式解码器注入。

**Feature Gates / Compatibility Config**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/config/`
- 职责：把 continuation 与 processing pattern replacement 作为独立功能开关，并在 Mixin 装配前读取持久化配置。

**Multi-Level Emitter Domain**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/parts/automation/`
- 职责：实现多槽位、多比较模式、模糊匹配、crafting 状态联动、表达式判定等 emitter 逻辑。

**Menu State / UI Contract**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/menu/implementations/`
- 职责：承接服务端容器、客户端动作、屏幕状态快照与输入校验。

**Client GUI Implementations**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`
- 职责：渲染真实屏幕，处理按钮、输入框、滚动条与子界面交互。

**Processing Pattern Replacement Domain**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/`
- 职责：给 AE2 加工样板输入槽附加 replacement 规则，并让 planning / execution 都感知候选输入集合。

**Crafting Continuation Domain**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/crafting/`
- 职责：在 craft confirm 与 crafting CPU 生命周期中插入 ignore-missing 模式、等待状态追踪、持久化与 GUI 投影。

**AE2 Integration / Injection Layer**
- 位置：`src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- 职责：把三个功能域挂接到 AE2 原生菜单、GUI、规划树与 CPU 生命周期。

### 关键数据流

**Multi-Level Emitter**
- `Chexsonsaeutils.java` 注册 `multi_level_emitter` 物品与 `MenuType`。
- 玩家激活部件时，`MultiLevelEmitterRuntimePart` 先发布当前 runtime part，再通过 `MultiLevelEmitterMenu` 打开菜单。
- `MultiLevelEmitterMenu.RuntimeMenu` 负责把客户端动作映射到服务端状态写回。
- `MultiLevelEmitterRuntimeScreen` 负责真实渲染；`MultiLevelEmitterScreen` 负责纯 UI 状态计算与校验。
- emitter 的阈值、比较关系、模糊模式、crafting 模式、表达式与 `ConfigInventory` 一起写入 NBT。

**Processing Pattern Replacement**
- 终端 mixin 在 `Ctrl + 左键` 时打开 `ProcessingPatternReplacementScreen`。
- 规则草稿先经 `ProcessingSlotRuleValidation.java` 清洗，再编码进 pattern metadata。
- `ProcessingPatternReplacementDecoder` 和 `ReplacementAwareProcessingPattern` 让 replacement 规则进入 planning / execution。
- AE2 侧 planning 与 execution mixin 只负责接线，候选输入语义仍放在普通领域类中。

**Crafting Continuation**
- `CraftConfirmMenuContinuationMixin` 把 continuation / ignore-missing 模式带入提交链。
- `CraftingContinuationSubmitBridge` 用 `ThreadLocal` 传递一次提交范围内的模式。
- `CraftingContinuationStatusService` 与 `CraftingContinuationSavedData` 协调等待状态与重进世界后的恢复。
- CPU 菜单与屏幕通过 continuation 相关 mixin 暴露等待详情与运行中分支摘要。

### 核心抽象

- **Thin Bootstrap：** 注册与注入放在入口层，领域逻辑不直接堆进 bootstrap。
- **RuntimePart + Part：** `*RuntimePart` 持有运行态，`*Part` 保持纯逻辑、枚举与归一化函数。
- **RuntimeMenu + Screen Helper：** `RuntimeMenu` 负责容器通信，辅助 screen 类负责 view-model / validator。
- **Replacement Rule Host：** 用接口隔离 mixin 持有的菜单状态与 GUI。
- **Replacement-Aware Pattern：** 先解码为自定义 `IPatternDetails`，再用 mixin 改写 AE2 planning / execution。
- **Continuation Bridge + Status Service：** 一次提交范围内用 `ThreadLocal`，跨 tick / 重进世界用 `SavedData`。

### 入口点

- `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`：`@Mod("chexsonsaeutils")` 入口。
- `src/main/resources/chexsonsaeutils.mixins.json`：声明 `menu`、`client/gui`、`crafting` 三类 AE2 注入点。
- `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`：玩家右键激活 AE2 part 的运行时入口。
- `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java`：processing terminal 中 replacement UI 的入口。
- `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/CraftConfirmMenuContinuationMixin.java` 与 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/CraftingServiceContinuationMixin.java`：craft continuation 提交链入口。

### 错误处理

- 配置开关读取失败时走默认值，不阻塞模组启动。
- UI 输入先清洗再提交，尽量返回诊断而不是直接抛异常。
- replacement 草稿支持 `PARTIALLY_INVALID` 语义，用于保留“部分有效”的恢复结果。
- 反射/AE2 内部访问一旦签名失效，直接抛出 `IllegalStateException` 暴露兼容性问题。

### 1.21 + NeoForge 迁移触点

- **Bootstrap / Loader API：** `build.gradle`, `src/main/templates/META-INF/neoforge.mods.toml`, `Chexsonsaeutils.java`
- **Config / Filesystem API：** `config/` 下的兼容配置与 feature gate 类
- **Menu Opening / Networking：** `MultiLevelEmitterMenu.java` 中的 `player.openMenu(...)` 与 `MenuLocators.writeToPacket(...)`
- **AE2 ABI-Sensitive Mixins：** `mixin/ae2/menu/`, `mixin/ae2/client/gui/`, `mixin/ae2/crafting/`
- **Reflection Hotspots：** `MultiLevelEmitterRuntimePart.java`, `MultiLevelEmitterRuntimeScreen.java`, `CraftingContinuationPartialSubmit.java`
- **AE2 Style / Resource Layout：** `assets/ae2/screens/`, `assets/ae2/textures/guis/`, `assets/chexsonsaeutils/guis/`
<!-- GSD:architecture-end -->


<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

在使用 Edit、Write 或其他会改文件的工具前，先通过 GSD 命令进入对应工作流，保证 planning artifacts、状态文件和执行上下文保持同步。

使用这些入口：
- `/gsd:quick` 处理小修复、文档更新和临时任务
- `/gsd:debug` 处理调查与缺陷修复
- `/gsd:execute-phase` 执行已经规划好的 phase 工作

除非用户明确要求绕过，否则不要在 GSD 工作流之外直接修改仓库。
<!-- GSD:workflow-end -->


<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
