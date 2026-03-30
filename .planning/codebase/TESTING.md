# 测试模式

**Analysis Date:** 2026-03-30

## 测试框架

**Runner:**
- JUnit Jupiter `5.10.2`
- 由 Gradle `test` 任务执行，配置见 `build.gradle`

**Assertion Library:**
- JUnit 自带断言：`assertEquals`、`assertTrue`、`assertFalse`、`assertIterableEquals`、`assertDoesNotContain` 等
- 项目未引入 Mockito、AssertJ 这类额外测试依赖

**Run Commands:**
```bash
.\gradlew.bat test
.\gradlew.bat test --tests "git.chexson.chexsonsaeutils.parts.MultiLevelEmitterPartTest"
.\gradlew.bat test --tests "git.chexson.chexsonsaeutils.crafting.CraftingContinuationPartialSubmitTest"
.\gradlew.bat build
```

## 测试文件组织

**位置：**
- 全部测试位于 `src/test/java/git/chexson/chexsonsaeutils/`
- 当前按功能域分为 `parts/`、`crafting/`、`pattern/`、`support/`

**命名：**
- 常规单元回归：`*Test.java`
- 集成 / 生命周期回归：`*IntegrationTest.java`、`*LifecycleIntegrationTest.java`
- 结构 / 源码契约：`*ContractTest.java`、`*ConfigGateTest.java`
- 辅助工具：`*TestHarness.java`、`*TestSupport.java`

**结构示例：**
```text
src/test/java/git/chexson/chexsonsaeutils/
├─ crafting/
│  └─ CraftingContinuationPartialSubmitTest.java
├─ parts/
│  ├─ MultiLevelEmitterPartTest.java
│  ├─ MultiLevelEmitterIntegrationTest.java
│  └─ MultiLevelEmitterMenuTestHarness.java
├─ pattern/
│  ├─ ProcessingPatternReplacementExecutionTest.java
│  └─ PatternTerminalEntryContractTest.java
└─ support/
   ├─ RepositoryStructureContractTest.java
   └─ SourceLayoutTestSupport.java
```

## 测试结构

**风格：**
- 测试类通常保持 package-private
- 测试方法名直接描述行为或回归缺陷，不使用中文或“should*”模板化命名
- 简单用例直接在单个方法内 arrange / act / assert；复杂场景才抽 helper
- 几乎不依赖全局 `beforeEach`，更偏向每个用例内显式准备数据

**实际模式：**
```java
class MultiLevelEmitterPartTest {

    @Test
    void defaultsBackfillThresholdAndComparisonForMissingSlotMetadata() {
        Map<Integer, Long> thresholds = new HashMap<>();
        thresholds.put(0, 7L);

        Map<Integer, Long> normalizedThresholds =
                MultiLevelEmitterPart.normalizeThresholdsForSlotCount(thresholds, 2);

        assertEquals(7L, normalizedThresholds.get(0));
        assertEquals(1L, normalizedThresholds.get(1));
    }
}
```

## Mocking 与替身

**工具：**
- 不使用 mocking framework
- 大量使用 `Proxy.newProxyInstance(...)` 为 AE2 / Forge 接口构造最小替身，见 `src/test/java/git/chexson/chexsonsaeutils/crafting/CraftingContinuationPartialSubmitTest.java`
- 使用手写 stub / fake / harness，例如 `TrackingStorage`、`DummyKey`、`MultiLevelEmitterMenuTestHarness`

**模式：**
- 对外部系统边界做最小行为模拟，只实现当前断言真正依赖的方法
- 对私有方法和不可直接实例化的结构，允许反射进入测试，但会把反射细节封装在本地 helper 中
- 对资源路径、源码布局、配置文本稳定性，直接读取源码文件断言字符串契约，见 `RepositoryStructureContractTest.java`

**不做什么：**
- 不做快照测试
- 不引入大型容器或真实 Minecraft runtime 来跑单测
- 不为简单纯函数强行搭框架

## Fixtures 与工厂

**测试数据：**
- 简单数据直接内联
- 略复杂的虚拟 AE2 对象会在测试类内部用私有静态类或 record 构造
- 通用文件读取与路径帮助函数集中在 `src/test/java/git/chexson/chexsonsaeutils/support/`

**常见模式：**
- 通过 `SourceLayoutTestSupport` 读取 UTF-8 文件并验证源码/资源路径
- 通过 `Path.of(...)` + `Files.exists(...)` 检查构建拓扑是否变化
- 通过本地 `defaultValue(...)` 辅助方法为动态代理返回基础默认值

## 覆盖重点

**当前强项：**
- Multi-level emitter 纯逻辑、菜单协议、持久化、表达式、生命周期
- Processing pattern replacement 的规则持久化、终端入口、规划/执行行为、配置开关
- Crafting continuation 的提交桥接、配置 gate、状态生命周期与 GUI 同步契约
- 仓库布局、编码、资源路径、关键构建字符串的结构契约

**当前缺失：**
- 没有配置 JaCoCo 或强制覆盖率阈值
- 没有真实 game runtime 的自动化 smoke test
- 没有 1.21 / NeoForge 的兼容性矩阵

## 测试类型

**单元测试：**
- 直接调用纯逻辑方法
- 重点验证默认值、边界值、旧数据回退
- 代表文件：`src/test/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterPartTest.java`

**契约测试：**
- 直接校验源码文本、资源路径、方法签名、配置字符串是否存在
- 用于保护 mixin 接线和代码结构不被重构破坏
- 代表文件：`src/test/java/git/chexson/chexsonsaeutils/support/RepositoryStructureContractTest.java`

**集成式回归：**
- 在不启动完整客户端的前提下，跨多个类验证生命周期、菜单流转、状态同步
- 代表文件：`src/test/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterLifecycleIntegrationTest.java`

**UI / 菜单语义测试：**
- 不直接测试像素，而是测试 screen/menu 的动作、可见槽位、按钮协议、表达式校验等可确定逻辑
- 代表文件：`src/test/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterScreenTest.java`

## 常见断言模式

**异常与反射：**
- 反射调用失败会包装成 `AssertionError`，避免静默跳过真实破坏
- 对私有实现变动敏感的测试会直接断言源码包含某段关键字符串

**资源与布局：**
- 通过 `Files.exists(...)` 断言资源路径稳定
- 通过读取 `build.gradle`、`.editorconfig`、`.gitattributes` 断言构建和编码契约

**兼容性回归：**
- 非法枚举值、旧字段名、缺失元数据都应回退到安全默认值
- 新功能迁移时优先补“之前为什么这么做”的回归测试，而不是只补 happy path

## 1.21 + NeoForge 迁移时的测试策略

- 先让现有 `parts/`、`pattern/`、`crafting/` 三大域的纯逻辑与结构契约保持通过
- 所有替换 Forge / AE2 API 的改动都应该伴随新的契约测试，明确记录新类名、新资源路径、新配置入口
- 对 mixin 与反射热点，迁移后至少补一轮“类/字段/构造器仍可解析”的专项回归
- 如果加入真实运行时 smoke test，应放在独立测试层，不要污染当前快速单测模式

---

*Testing analysis: 2026-03-30*
*Update when test patterns change*
