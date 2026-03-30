# 代码库风险点

**Analysis Date:** 2026-03-30

## 技术债

**Forge 1.20.1 锁定的构建链：**
- Issue: `build.gradle`、`gradle.properties`、`src/main/resources/META-INF/mods.toml` 全部直接绑定 Forge 47 / Minecraft 1.20.1 / Java 17
- Why: 当前项目就是以单平台 Forge 1.20.1 为交付目标构建的
- Impact: 迁移到 1.21 + NeoForge 时，构建插件、元数据文件、loader 版本范围、依赖坐标需要整体替换，不能只改一两个版本号
- Fix approach: 先独立完成平台层迁移，再逐层处理 AE2 API、menu/network、mixin 和资源兼容

**缺失的 access transformer 文件：**
- Issue: `build.gradle` 配置了 `src/main/resources/META-INF/accesstransformer.cfg`，但当前仓库中该文件不存在
- Why: 可能来自模板残留，或者早期设计预留后未落地
- Impact: 当前 Forge 构建是否完全依赖它并不清晰；迁移时如果误以为存在访问转换支持，容易走错兼容路线
- Fix approach: 在迁移前先确认该路径是否应删除，或补充契约测试防止“配置存在但文件缺失”继续漂移

**大量结构契约与源码文本断言：**
- Issue: 多个测试直接断言源码字符串、文件路径和包拓扑，例如 `src/test/java/git/chexson/chexsonsaeutils/support/RepositoryStructureContractTest.java`
- Why: 项目希望在不启动真实 Minecraft runtime 的前提下，快速守住接缝稳定性
- Impact: 迁移或重构时会出现“功能没坏但契约测试先红”的情况，必须同步更新测试意图
- Fix approach: 迁移时先明确哪些契约是“必须保持”，哪些是“可接受重写”，避免盲目修测试

## 已知缺口

**暂无可稳定复现的运行时 bug 清单：**
- Symptoms: 静态分析和现有测试没有直接暴露单一必现缺陷
- Trigger: N/A
- Workaround: N/A
- Root cause: 当前风险主要来自平台迁移与上游 AE2/Forge ABI 变动，而不是已登记的业务错误

## 安全性考虑

**反射访问上游私有成员：**
- Risk: `src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java` 和 `src/main/java/git/chexson/chexsonsaeutils/crafting/submit/CraftingContinuationPartialSubmit.java` 直接反射 AE2 私有字段、私有构造器和内部 listener 类型
- Current mitigation: 解析失败时快速抛 `IllegalStateException`，不会默默错运行
- Recommendations: 对每个反射热点补单独契约测试，并在迁移到 1.21 + NeoForge 后优先寻找可替代的公开 API

**ThreadLocal 桥接跨调用传递状态：**
- Risk: `CraftingContinuationSubmitBridge` 与 `MultiLevelEmitterRuntimePart` 都使用 `ThreadLocal` 在一次交互链路里传状态
- Current mitigation: 使用后立即消费/清理，作用域较短
- Recommendations: 迁移时确认 NeoForge / AE2 在菜单与 crafting 提交链上的线程模型没有变化；若线程边界改变，应改成显式上下文传递

## 性能瓶颈

**非严格匹配会退化为全量 watcher：**
- Problem: `MultiLevelEmitterRuntimePart.hasMarkedNonStrictStorageSlot()` 为真时会对 storage watcher 调 `setWatchAll(true)`
- Measurement: 未见现成基准数据
- Cause: AE2 模糊匹配需要观察所有候选物品，当前实现选择了简单但宽范围的失效策略
- Improvement path: 迁移后评估 AE2 1.21 是否提供更细粒度的 fuzzy watch API；若没有，可考虑缓存候选集合减少重算

**表达式与候选规则评估是线性放大的：**
- Problem: emitter 最大支持 64 槽，表达式、模糊匹配、crafting 状态组合后每次刷新会跨多个列表和集合遍历
- Measurement: 未见现成基准数据
- Cause: 当前实现优先可读性和正确性，没有做更细的热点缓存
- Improvement path: 迁移时先保持语义不变，再基于 profiler 确认是否需要优化

## 脆弱区域

**AE2 mixin / accessor 面：**
- Why fragile: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/` 下的类直接锁定 AE2 15.x 的类名、字段名、菜单结构和 GUI 结构
- Common failures: 目标类改名、方法签名变化、局部变量表变化、注入点失效
- Safe modification: 每改一个 mixin 先确认目标 AE2 1.21 源码，再同步更新对应 contract/integration tests
- Test coverage: 有较多结构契约，但缺少真实 1.21 运行时校验

**反射热点：**
- Why fragile: `StorageLevelEmitterPart.storageWatcher`、`craftingWatcher`，以及 `ExecutingCraftingJob` 私有构造器都不是稳定 ABI
- Common failures: 字段名变化、构造参数变化、访问级别变化
- Safe modification: 优先寻找公开 API；若继续反射，先把目标签名集中抽象并加失败消息
- Test coverage: 存在部分契约测试，但没有真正跨版本 smoke test

**AE2 风格 GUI 资源路径：**
- Why fragile: `src/main/resources/assets/ae2/screens/` 与 `src/main/resources/assets/ae2/textures/guis/` 直接挂到 AE2 命名空间
- Common failures: AE2 1.21 改 screen schema、纹理路径或布局字段后，界面会在运行时失效
- Safe modification: 迁移前先对照 AE2 1.21 资源布局；必要时把项目自定义资源从“覆盖式”改成“挂接式”
- Test coverage: 有路径存在性契约，没有运行时渲染验证

## 依赖风险

**`net.minecraftforge.gradle`：**
- Risk: 当前插件只覆盖 Forge 构建链，不适用于 NeoForge 迁移目标
- Impact: 不切换构建插件就无法真正进入 1.21 + NeoForge
- Migration plan: 用 NeoForge 对应 Gradle 方案重建 run/data/test 配置

**`appeng:appliedenergistics2-forge:15.4.5`：**
- Risk: 这是 Forge 1.20.1 侧 AE2 版本，1.21 分支的 API 与内部实现大概率变化
- Impact: menu、part、crafting、mixin、资源几乎全部受影响
- Migration plan: 先锁定目标 AE2 1.21 版本，再按 bootstrap -> part/menu -> pattern -> crafting 的顺序迁移

**`org.spongepowered:mixingradle:0.7-SNAPSHOT`：**
- Risk: 依赖旧式 Forge 工程集成方式
- Impact: NeoForge 新工具链下可能需要不同的 mixin 集成姿势
- Migration plan: 优先采用目标生态推荐的 mixin 集成方式，避免硬搬旧 buildscript

## 缺失的关键能力

**没有真实 1.21 / NeoForge 兼容验证：**
- Problem: 当前测试与运行配置全部围绕 Forge 1.20.1
- Current workaround: 依赖静态结构契约和现有单测保护纯逻辑
- Blocks: 无法证明迁移后 mixin、菜单、资源、保存数据和 AE2 集成仍能真实运行
- Implementation complexity: High

**没有自动化运行时 smoke test：**
- Problem: 项目没有 GameTest 或真实客户端/服务端启动验证
- Current workaround: 依赖 `runClient`、`runServer` 的手工验证
- Blocks: 平台迁移后很多问题只能在人工打开游戏后才会暴露
- Implementation complexity: Medium

## 测试覆盖缺口

**Loader / runtime 级集成：**
- What's not tested: NeoForge/FML 实际加载、mixins 是否成功应用、菜单是否能真实打开、资源是否正常渲染
- Risk: 迁移后构建能过但进游戏崩
- Priority: High
- Difficulty to test: 需要目标版本的完整开发运行环境

**真实 AE2 1.21 ABI：**
- What's not tested: AE2 新版本类名、字段、构造器、菜单路径、资源布局
- Risk: 反射与 mixin 热点全面失效
- Priority: High
- Difficulty to test: 需要先选定具体 AE2 1.21 目标版本

**构建配置一致性：**
- What's not tested: `accessTransformer` 配置与文件是否一致、NeoForge 构建元数据是否完整
- Risk: 迁移后出现“配置看起来正确但实际缺文件/缺字段”的隐性问题
- Priority: Medium
- Difficulty to test: 低，适合补新的结构契约测试

---

*Concerns audit: 2026-03-30*
*Update as issues are fixed or new ones discovered*
