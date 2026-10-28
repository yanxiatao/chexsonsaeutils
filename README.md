# Chexson's AE Utils

Applied Energistics 2 的实用扩展模组，补充合成、样板、存储与自动化功能。

## 主要功能

- **ME 多槽发信器**：在一个部件中监控多个物品，配置阈值、模糊匹配与逻辑表达式。
- **处理样板替换**：按物品标签或指定候选材料配置替换规则。
- **合成续跑**：缺料时执行可完成的分支，并显示等待状态。
- **并行合成 CPU（无阂枢）**：并行执行合成任务，支持大数量规划与状态显示。
- **大数量合成终端与无限存储单元**：支持超出普通整数范围的数量输入、显示和存储。
- **直连处理机（径行）与极限装配机（归墟）**：集中管理样板，提供机器配方导入与加工能力。
- **ME 样板传递器**：向另一 ME 网络开放合成能力，支持多个来源与远程需求预览。
- **玩家模拟面板**：通过模拟玩家进行方块交互与挖掘，使用 ME 网络缓存物品。
- **合成生产关系图**：查看物料与样板关系，搜索节点并导出图片。
- **其他扩展**：定制样板供应器、物质团供应器、样板染色、增强合成状态，以及 Building Gadgets 2、FTB Ultimine 等可选集成。

详细操作见游戏内 AE2 指南；整合包脚本配置见 [直连处理机 KubeJS 用法](docs/direct-processing-kubejs.md)。

## 支持版本

本分支面向 **Minecraft 1.21.1 / NeoForge / Java 21**，不适用于 Forge 或 Fabric。

| 组件 | 当前构建版本或要求 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.238 |
| Applied Energistics 2 | 19.2.17 |
| ExtendedAE | 必需，使用兼容 Minecraft 1.21.1 的 NeoForge 版本 |
| GuideME | 21.1.1 |
| Java | 21 |

将模组及必需依赖放入 `mods` 目录。配置文件为 `config/chexsonsaeutils-common.toml`，也可从模组配置界面编辑。JEI、Configured、KubeJS 及其他集成模组按需安装；更新依赖时需确认对应功能的兼容性。

## 从源码构建

安装 Java 21 后运行：

```sh
./gradlew --no-daemon build
```

Windows 使用 `gradlew.bat`。依赖均由 Gradle 从 Maven、Modrinth 或 CurseMaven 下载，
无需复制 JAR 到 `libs/` 或 `reference-sources/`。版本和发布文件 ID 固定在
`gradle.properties`，首次构建需要网络。构建结果位于 `build/libs/`；`build` 同时执行
默认回归测试与 JSON 资源校验。

可选模组的真实 SDK 验收单独启用，也使用相同的在线坐标，例如：

```sh
./gradlew --no-daemon test -I tools/integration-tests/neoecoae/neoeco-sdk-test.init.gradle --tests '*NeoEcoSdkCategoryTest'
```

仓库的 GitHub Actions 对 PR 执行构建、测试和架构检查，测试报告作为运行产物保留。

## AI 编写提醒

本模组的代码、文档和部分资源主要由 AI 在开发者指令下编写、迁移与整理，并由人工取舍和审阅。可能存在缺陷或兼容问题；用于重要存档或服务器前，请先备份并在测试环境确认实际表现。

## 参考与许可证

本项目原创内容使用 [MIT 许可证](LICENSE)。引用或移植的第三方内容保留原许可证，不能统一视为 MIT。

| 项目 | 参考或使用内容 | 上游许可证 |
| --- | --- | --- |
| [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) | 网络、样板、合成接口及样板供应器逻辑移植 | LGPL-3.0；移植文件保留原声明 |
| [ExtendedAE](https://github.com/GlodBlock/ExtendedAE) | 装配矩阵界面、行结构、搜索与界面资源复用 | LGPL-3.0 |
| [AEA](https://github.com/REFactoryTeam/AEA) | 样板染色、合成状态及可选模组集成功能迁移 | MIT |
| [AdvancedAE](https://github.com/pedroksl/AdvancedAE) | 合成与机器实现参考 | LGPL-3.0 |
| [AE2 Lightning Tech](https://github.com/ae2lt/AE2-Lightning-Tech) | 批量加工与集成接口参考 | 代码 LGPL-3.0；美术资源 CC BY-NC-SA 3.0 |
| [NeoECOAEExtension](https://github.com/DancingSnow0517/NeoECOAEExtension) | 大数库存与批量合成接口对接 | GPL-3.0 |
| [DataEnergistics](https://github.com/fish1145/DataEnergistics) | 数量模式与下单接口对接 | 代码 AGPL-3.0；资源以其单独声明为准 |
| [JustEnoughCharacters](https://github.com/Towdium/JustEnoughCharacters) | 中文搜索接口对接 | MIT |

具体移植范围与版权声明见 [第三方声明](THIRD_PARTY_NOTICES.md)。参考源码保存在本地 `reference-sources/`，不随本仓库提交。版本变更见 [更新日志](CHANGELOG.md)。
