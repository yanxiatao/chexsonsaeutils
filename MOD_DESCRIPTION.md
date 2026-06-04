# CurseForge / Modrinth 发布描述

## 中文

### 简介

`Chexson's AE Utils` 是一个面向 Applied Energistics 2 的自动化实用扩展，当前支持 `Minecraft 1.21.1 + NeoForge + AE2 19`。它补充了 ME 多槽发信器、处理样板替换规则，以及缺料时继续提交可执行分支的合成续跑能力。

本模组适合需要更细粒度 ME 网络控制、可替换处理输入、以及更强自动合成容错能力的技术向存档和整合包。

### 主要功能

- `ME 多槽发信器`：一个 AE2 部件中最多管理 `64` 个监控槽位，每个槽位可独立设置物品、阈值、比较方式、模糊匹配和合成相关行为。
- `表达式逻辑`：使用 `#1 OR (#2 AND #3)` 这类表达式组合多个槽位，并提供语法与槽位状态校验。
- `处理样板替换`：为 AE2 processing pattern 输入槽保存标签组或显式物品候选，在 planning / execution 阶段按规则选择可用输入。
- `合成续跑 / Ignore Missing`：合成计划缺料时，可提交已满足条件的分支，只让缺料分支等待。
- `状态可见性`：在相关 AE2 菜单和状态界面中保留等待数量、等待分支、规则状态等信息。
- `配置开关`：可通过 `config/chexsonsaeutils-common.toml` 启用或禁用合成续跑和处理样板替换功能。

### 依赖

- Minecraft `1.21.1`
- NeoForge `21.1.222+`
- Applied Energistics 2 `19.2.17+`
- Java `21`

### AI 编写提示

本模组的代码、文档与部分资源主要由 AI 在开发者指令下编写、迁移和整理，并经过人工审阅。请在正式服务器或重要存档中使用前备份世界，并先在测试环境中验证自动化链路。

### 免责声明

本模组不是 AE2 官方项目。由于功能涉及 AE2 菜单、样板、合成服务和部件系统，更新 AE2、NeoForge 或 Minecraft 后建议重新测试兼容性。

## English

### Summary

`Chexson's AE Utils` is an Applied Energistics 2 utility add-on for `Minecraft 1.21.1 + NeoForge + AE2 19`. It adds an ME Multi-Level Emitter, processing pattern replacement rules, and ignore-missing crafting continuation.

The mod is intended for technical saves and modpacks that need finer ME network control, replaceable processing inputs, and more fault-tolerant autocrafting.

### Features

- `ME Multi-Level Emitter`: manage up to `64` monitored slots in one AE2 part, with independent items, thresholds, comparison modes, fuzzy matching, and crafting-related behavior.
- `Expression Logic`: combine slots with expressions such as `#1 OR (#2 AND #3)`, with validation for syntax and slot state.
- `Processing Pattern Replacement`: store tag-group or explicit item candidates for AE2 processing pattern inputs, then select available replacements during planning and execution.
- `Crafting Continuation / Ignore Missing`: when a crafting plan is missing inputs, submit the branches that can run and leave only missing-input branches waiting.
- `Status Visibility`: keep waiting amounts, waiting branches, and rule states visible in related AE2 menus and status views.
- `Config Switches`: enable or disable crafting continuation and processing pattern replacement in `config/chexsonsaeutils-common.toml`.

### Requirements

- Minecraft `1.21.1`
- NeoForge `21.1.222+`
- Applied Energistics 2 `19.2.17+`
- Java `21`

### AI Authorship Notice

This mod's code, documentation, and some assets were primarily written, migrated, and organized by AI under developer direction, with human review. Back up important worlds and test automation workflows before using it on production servers or valuable saves.

### Disclaimer

This is not an official AE2 project. Because the mod integrates with AE2 menus, patterns, crafting services, and parts, compatibility should be re-tested after updating AE2, NeoForge, or Minecraft.
