# 编码约定

**Analysis Date:** 2026-03-30

## 命名模式

**文件：**
- Java 源文件统一使用 `PascalCase.java`，按职责放在小写包路径下，例如 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`
- 测试文件统一使用 `*Test.java`，回归/契约/集成语义会直接编码进文件名，例如 `MultiLevelEmitterLifecycleIntegrationTest.java`、`ProcessingPatternReplacementConfigGateTest.java`、`RepositoryStructureContractTest.java`
- 资源文件使用 AE2 / Minecraft 约定的小写蛇形或路径式命名，例如 `src/main/resources/assets/ae2/screens/multi_level_emitter.json`、`src/main/resources/data/chexsonsaeutils/recipes/network/parts/multi_level_emitter.json`

**函数：**
- 生产代码方法使用 `lowerCamelCase`，名称尽量直陈行为，例如 `registerProcessingPatternReplacementDecoder()`、`updateThresholdFromUi()`、`evaluateConfiguredOutput()`
- 测试方法也使用 `lowerCamelCase`，但命名更偏行为说明句，例如 `defaultsBackfillThresholdAndComparisonForMissingSlotMetadata()`、`extractsBothPlannedAndPreviouslyMissingInitialInputs()`
- 事件/动作型方法通常带动词前缀，如 `register*`、`apply*`、`cycle*`、`read*`、`write*`、`resolve*`

**变量：**
- 局部变量与字段统一使用 `lowerCamelCase`
- 常量使用 `UPPER_SNAKE_CASE`，尤其集中在 GUI 和 menu 协议常量里，例如 `ACTION_COMMIT_THRESHOLD`、`GROUPED_CONTENT_HEIGHT`
- 布尔值命名偏可读语义，例如 `cardCapabilityStateInitialized`、`processingPatternReplacementEnabled`

**类型：**
- 类、枚举、record、接口统一使用 `PascalCase`
- 接口不会加 `I` 前缀，遵循上游生态已有命名即可，例如 `ProcessingSlotRuleHost`
- 小型跨层载荷优先用 `record` 表达，例如 `ThresholdPayload`、`ExpressionPayload`

## 代码风格

**格式：**
- Java 使用 4 空格缩进，花括号与声明同行
- 字符串统一使用双引号
- 语句结尾保留分号
- 代码中广泛使用 `final`、`static final`、不可变 `List.copyOf(...)` / `Map.of(...)`
- `.editorconfig` 明确要求 UTF-8 与 CRLF，见 `/.editorconfig`

**静态工具偏好：**
- 纯逻辑优先抽成 `final` 工具类 + `static` 方法，例如 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterPart.java`
- 和 AE2 运行态强耦合的状态放进 runtime 类，例如 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java`
- UI 状态整形与容器协议拆层处理：菜单类负责服务端动作转发，屏幕辅助类负责显示层格式化与校验

## 导入组织

**顺序：**
1. AE2 / Minecraft / Forge / 第三方依赖
2. 当前项目内部包
3. `java.*` / `javax.*`

**分组：**
- 组与组之间通常留一个空行
- 同组内基本按语义聚集，不强依赖机械字母序
- 只有在文件足够复杂时才保留多个内部导入分组；小文件通常保持单组

## 错误处理

**模式：**
- 持久化数据、旧版本字段、用户输入优先做“安全降级”而不是抛异常，例如 `ComparisonMode.fromPersisted(...)`、`MatchingMode.fromPersisted(...)`
- 兼容层或反射层一旦无法继续，应立即抛出 `IllegalStateException` 暴露 ABI 断裂，例如 `CraftingContinuationPartialSubmit.java` 与 `MultiLevelEmitterRuntimePart.java`
- 菜单与 GUI 输入先规范化再写回运行态，例如 `sanitizeAndClampThreshold(...)`

**什么时候抛异常：**
- 反射字段、构造器、私有方法签名失效
- 关键运行时绑定缺失，例如菜单类型供应器为空
- 无法维持服务器权威状态时

**什么时候兜底：**
- 读取旧 NBT / 非法配置 / 失效枚举值
- 特性卡片缺失后将功能模式回退到安全默认值
- 客户端侧仅用于展示的状态无法解析时

## 日志

**框架：**
- 模组启动层使用 `SLF4J` / `LogUtils.getLogger()`，见 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- 极少数 AE2 内部态异常使用 `AELog.warn(...)`，见 `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java`

**约定：**
- 日志主要用于注册和异常态观测，不在正常热路径刷屏
- 生产逻辑更偏向可测试的返回值与状态同步，而不是大量日志驱动

## 注释

**什么时候写注释：**
- 解释“为什么必须这样兼容”而不是“代码在做什么”
- 标记 AE2/Forge 兼容背景、ThreadLocal bridge、Mixin 限制、历史兼容语义
- 在反射或显式 workaround 处说明原因

**风格：**
- 注释短而直接，不写样板废话
- Javadoc 只在需要强调职责边界时使用，例如 `MultiLevelEmitterRuntimePart` 的类注释

## 函数设计

**风格：**
- 对外入口方法通常短小，复杂细节拆到私有 helper
- 倾向显式守卫式返回，减少深层嵌套
- 状态变更通常围绕“标准化 -> 写入 -> refresh/reconfigure”这一固定节奏

**参数与返回：**
- 输入校验优先在边界完成
- 小载荷优先 `record`，复杂返回优先显式 value object，例如 `AggregationResult`、`SlotEvaluation`
- 在纯逻辑层避免隐藏副作用，便于测试直接调用

## 模块设计

**导出与边界：**
- bootstrap 只负责注册，不沉淀业务细节，见 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- `parts/automation/`、`pattern/replacement/`、`crafting/` 是三个核心功能域
- `mixin/ae2/` 只做接线、注入、accessor；真正业务尽量放回普通包，减少迁移时的 blast radius

**新增代码时优先遵守：**
- 新的纯规则判断优先进入普通 Java 类，不要先写进 mixin
- 新的 GUI 动作先在 menu 协议层定义动作常量与 payload，再接到 screen
- 新的兼容开关优先进入 `config/` 与 `ChexsonsaeutilsMixinPlugin`
- 新的测试优先写行为回归或结构契约，而不是只做“存在性”断言

## 1.21 + NeoForge 迁移时应保持的风格

- 保留“业务逻辑在普通类、平台绑定在外层”的结构，不要把 NeoForge 适配代码扩散到纯逻辑域
- 若必须替换 Forge / AE2 API，优先改 bootstrap、menu 网络桥、config、mixin 接缝，不要先改测试契约描述
- 迁移后继续维持 UTF-8 + CRLF 和现有包拓扑，避免让功能迁移和仓库重组耦合在同一次变更中

---

*Convention analysis: 2026-03-30*
*Update when patterns change*
