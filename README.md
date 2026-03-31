# Chexson's AE Utils

`Chexson's AE Utils` 是一个面向 `Minecraft 1.20.1 + Forge` 的 `Applied Energistics 2` 附属模组，当前版本聚焦三类已经落地的能力：

- 提供可实际使用的多物品发信器
- 为 AE2 加工样板提供 replacement rule 编辑、持久化与执行期替换能力
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

### 2. 加工样板替换规则

为 AE2 processing pattern 增加了可编辑、可持久化的输入替换规则：

- 可在终端中为 processing pattern 的输入槽配置 replacement rule
- 支持基于标签或显式候选物集合定义替换边界
- replacement metadata 会随 encoded pattern 一起保存
- planning 与 execution 两侧都会按 replacement-aware 语义选择候选输入
- 提供独立 feature gate，可在启动阶段整体关闭相关 mixin 与行为入口

### 3. AE2 合成缺料续作

为 AE2 合成确认流程增加了按任务粒度的 continuation 能力：

- 在提交合成任务时可选择是否启用“忽略缺料并继续”
- 缺料时不是整单硬失败，而是只让被阻塞分支进入等待
- 已满足材料的子步骤会继续执行
- 材料回到网络后会自动恢复等待中的步骤
- 续作状态会在 AE2 CPU 状态界面中展示等待信息
- 行为是任务级别的，不是全局常驻开关

### 4. 兼容性开关

当前提供两个启动期配置开关，默认开启：

- 配置项：`craftingContinuationEnabled`
- 配置项：`processingPatternReplacementEnabled`
- 位置：标准 Forge `common` 配置文件
- 作用：关闭后会在启动阶段屏蔽对应功能的 mixin 与行为入口
- 生效方式：重启后生效

## AI 生成说明

- 本仓库代码均由 AI 编写；README 文案也由 AI 辅助整理。实际能力与行为仍以仓库中的代码、配置和提交历史为准
