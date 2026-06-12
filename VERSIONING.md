# 版本号规则

本项目的发布版本写在 `gradle.properties` 的 `mod_version`。`build.gradle` 会把该值用作 Gradle 项目版本、jar 文件版本，并展开到 `neoforge.mods.toml` 的 mod 版本字段。不要在其他文件手动维护第二份版本号。

## 格式

```text
<Minecraft版本>-<Mod语义化版本>[-<预发布阶段>.<序号>]
```

当前 `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17` 迁移线使用：

```text
1.21.1-0.1.0-beta.1
```

## 字段含义

- `<Minecraft版本>` 使用精确目标 Minecraft 版本，例如 `1.21.1`。Minecraft 目标版本变化时新开兼容线，例如 `1.21.4-0.1.0-beta.1`。
- `<Mod语义化版本>` 使用 `MAJOR.MINOR.PATCH`：
  - `MAJOR`：不兼容的存档、配置、物品 ID、网络协议或公开行为变更。
  - `MINOR`：兼容地新增玩家可见功能，或新增默认关闭的实验能力。
  - `PATCH`：修复、文案、资源、依赖补丁级适配，不改变存档/配置契约。
- `<预发布阶段>` 可选，使用 `alpha.N`、`beta.N` 或 `rc.N`。正式版移除该后缀，例如 `1.21.1-0.1.0`。

## 升版规则

- 仅更新 NeoForge 或 AE2 的最低兼容版本时，优先更新依赖属性；如果可能影响运行时行为，至少提升 `PATCH`。
- 新增兼容功能时提升 `MINOR`。
- 破坏存档、配置或现有自动化工作流兼容性时提升 `MAJOR`，并在发布说明中写清迁移方式。
- 预发布阶段按 `alpha.N` -> `beta.N` -> `rc.N` -> 正式版推进；同一阶段重复发布时递增序号。
- 每次发布只修改 `gradle.properties` 中的 `mod_version`，其他产物由 Gradle 资源展开生成。
