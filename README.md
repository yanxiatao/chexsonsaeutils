# Chexson's AE Utils

`Chexson's AE Utils` 是一个面向 `Minecraft 1.20.1 + Forge` 的 `Applied Energistics 2` 附属模组，当前版本聚焦两件事：

- 提供可实际使用的多物品发信器
- 在不破坏 AE2 原有行为边界的前提下，为合成流程补充“按任务忽略缺料并自动续作”的能力

## 当前功能

### 1. 多物品发信器

在 AE2 原生等级发信器的使用场景上扩展出一套多槽位条件监控能力：

- 一个发信器可同时监控多个物品
- 每个槽位可单独设置阈值
- 支持表达式逻辑，不再局限于简单线性关系
- 支持 `AND`、`OR` 与括号分组
- 表达式在应用前会进行校验并给出界面反馈
- 配置槽位支持动态增减，并保持客户端、服务端与重进世界后的同步一致性
- UI 保持 AE2 风格，使用独立菜单绑定与运行时屏幕实现

### 2. AE2 合成缺料续作

为 AE2 合成确认流程增加了按任务粒度的 continuation 能力：

- 在提交合成任务时可选择是否启用“忽略缺料并继续”
- 缺料时不是整单硬失败，而是只让被阻塞分支进入等待
- 已满足材料的子步骤会继续执行
- 材料回到网络后会自动恢复等待中的步骤
- 续作状态会在 AE2 CPU 状态界面中展示等待信息
- 行为是任务级别的，不是全局常驻开关

### 3. 兼容性开关

续作功能通过 Forge `COMMON` 配置控制，默认开启：

- 配置项：`craftingContinuationEnabled`
- 位置：标准 Forge `common` 配置文件
- 作用：关闭后会在启动阶段屏蔽 continuation 相关 mixin 与行为入口
- 生效方式：重启后生效

## 开发环境

- Minecraft `1.20.1`
- Forge `47.4.10`
- AE2 `15.4.5`
- GuideME `20.1.7`
- JEI runtime `15.0.0.12`
- Java `17`
- Gradle Wrapper 已包含在仓库中

## 构建与运行

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat runClient
.\gradlew.bat runServer
```

如果需要把 Gradle 缓存放到仓库本地目录：

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-user')
.\gradlew.bat test
```

## 测试

项目已经包含 JUnit 5 回归测试，覆盖重点包括：

- 发信器注册、放置、菜单、界面与持久化
- 表达式解析、格式化、校验与运行时应用
- 动态槽位同步
- continuation 状态、确认流程、生命周期与配置门控
- 结构重组后的包布局契约

示例：

```powershell
.\gradlew.bat test --tests "git.chexson.chexsonsaeutils.parts.MultiLevelEmitterCraftingContinuationConfigGateTest"
.\gradlew.bat test --tests "git.chexson.chexsonsaeutils.crafting.CraftingContinuationPartialSubmitTest"
```

## 代码结构

- `src/main/java/git/chexson/chexsonsaeutils/parts/automation/`
  多物品发信器核心部件与表达式实现
- `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/`
  菜单与屏幕绑定
- `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`
  运行时 GUI
- `src/main/java/git/chexson/chexsonsaeutils/crafting/`
  continuation 模式、持久化、状态投影与提交逻辑
- `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
  AE2 兼容集成点
- `src/test/java/git/chexson/chexsonsaeutils/`
  面向行为与结构契约的测试

## 设计原则

- 以 AE2 集成为前提，不做侵入式重写
- 优先保证行为稳定、可验证、可回归
- 目录结构尽量贴近 AE2 责任边界，降低后续扩展成本
- 新功能默认先补测试，再补运行时闭环

## 当前状态

当前远端主线已整理为只包含功能代码的干净历史，适合继续做：

- 里程碑归档
- 新功能开发
- 更细的安装说明与发布说明补充
