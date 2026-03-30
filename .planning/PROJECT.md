# Chexson's AE Utils 1.21 NeoForge Migration

## What This Is

这是一个已有的 brownfield Minecraft AE2 附属模组迁移项目。当前代码库已经在 `Minecraft 1.20.1 + Forge` 上实现了多物品发信器、加工样板替换规则和 crafting continuation，本轮工作的目标是把这些能力迁移到 `1.21.x + NeoForge`，并尽量保持现有行为、配置和持久化语义不退化。

## Core Value

在迁移到 `1.21.x + NeoForge` 后，现有全部核心功能必须继续可用，且不能靠牺牲行为一致性来换取“能编译通过”。

## Requirements

### Validated

- ✅ Multi-level emitter 已实现多槽位监控、阈值/比较模式、模糊匹配、crafting 联动与表达式判定，见 `src/main/java/git/chexson/chexsonsaeutils/parts/automation/` 与相关测试
- ✅ Processing pattern replacement 已实现终端规则编辑、规则持久化与 planning/execution 侧替换语义，见 `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/` 与 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- ✅ Crafting continuation 已实现确认界面模式切换、部分提交、等待分支跟踪与 CPU 状态投影，见 `src/main/java/git/chexson/chexsonsaeutils/crafting/`
- ✅ 启动期 feature gate 已支持 continuation / processing replacement 独立开关，见 `src/main/java/git/chexson/chexsonsaeutils/config/`
- ✅ 代码库已存在面向行为、结构和接缝的大量回归测试，见 `src/test/java/git/chexson/chexsonsaeutils/`

### Active

- [ ] 将构建链、模组元数据、注册流程和运行入口迁移到可工作的 `Minecraft 1.21.x + NeoForge`
- [ ] 保留 multi-level emitter、processing pattern replacement、crafting continuation 的全部当前功能
- [ ] 保留或安全迁移已有 NBT、配置、样板元数据与等待状态等持久化语义
- [ ] 保持现有回归测试可用，并为迁移新增必要的兼容性/结构契约测试
- [ ] 在独立迁移分支上完成迁移，不污染当前 `master`

### Out of Scope

- 新增与迁移无关的玩法或 AE2 扩展功能 - 用户当前诉求是迁移并保留功能，不是扩 scope
- 多加载器支持（Forge/Fabric/Quilt 同时维护） - 当前目标明确是 `1.21 + NeoForge`
- 与兼容性无关的大规模 UI 重设计 - 现有界面应优先保持 AE2 风格和现有交互
- 与迁移无关的目录重组或架构重写 - 避免把平台迁移和代码库重构耦合在同一轮

## Context

- 当前仓库已经完成 brownfield codebase map，文档位于 `.planning/codebase/`
- 现有基线平台是 `Minecraft 1.20.1 + Forge 47.4.10 + AE2 15.4.5 + Java 17`
- 代码库强依赖 AE2 集成点，包括 menu、GUI、crafting internals、mixin、反射访问和 AE2 命名空间资源
- 当前主功能域分为 `parts/automation/`、`pattern/replacement/`、`crafting/` 三块
- 现有测试以 JUnit 5 为主，覆盖纯逻辑、结构契约、菜单/屏幕语义、持久化与部分集成接缝
- 已识别的高风险点包括：Forge 锁定构建链、AE2 1.21 ABI 变化、mixin 注入点、反射热点、GUI 资源路径、缺失的 access transformer 文件

## Constraints

- **Tech stack**: 目标必须落在 `Minecraft 1.21.x + NeoForge`，并依赖与之兼容的 AE2 版本
- **Compatibility**: 迁移完成后必须保留当前用户可见功能，不接受“删功能换过编译”
- **Verification**: 现有回归测试和新增迁移契约必须一起作为验收基础
- **Branching**: 迁移工作必须在独立分支进行，保持 `master` 可回退
- **Dependency coupling**: 迁移顺序必须服从 AE2 目标版本 API 可用性，不能盲目先改下游功能
- **Repository hygiene**: 保持 UTF-8 + CRLF 约定，不把编码/换行问题引入迁移噪音

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 按 brownfield 项目处理本次迁移 | 现有代码已经包含大量已验证功能，不能按“重写新模组”思路处理 | — Pending |
| 先追求功能等价，再考虑新增能力 | 用户要求保留全部功能，功能回归风险高于新能力缺失 | — Pending |
| 用 codebase map 驱动 ROADMAP | 当前项目高风险点集中在平台接缝，需要先把现状映射清楚 | — Pending |
| 将精确的 `1.21.x + NeoForge + AE2` 版本组合放入首个迁移阶段锁定 | 该组合属于时效性信息，需要在执行阶段基于最新官方资料确认 | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition**:
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone**:
1. Full review of all sections
2. Core Value check -> still the right priority?
3. Audit Out of Scope -> reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-03-30 after initialization*
