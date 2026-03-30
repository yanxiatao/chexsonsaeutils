# Roadmap: Chexson's AE Utils 1.21 NeoForge Migration

## Overview

这是一个 brownfield 迁移路线，而不是从零开始的新模组开发。路线按真实依赖顺序推进：先锁定目标 `1.21.x + NeoForge + AE2` 版本组合并让基础工程在目标平台可构建/可加载，再分别恢复 multi-level emitter、processing pattern replacement、crafting continuation 三大功能域，最后用回归与运行时验证收口，证明迁移分支具备继续开发和验证的稳定基础。

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [ ] **Phase 1: Lock Target Platform Baseline** - 锁定目标版本组合，重建 NeoForge 1.21.x 基础工程并让模组完成最小可加载闭环
- [ ] **Phase 2: Port Multi-Level Emitter** - 迁移 emitter 的注册、菜单、运行态、表达式与持久化语义
- [ ] **Phase 3: Port Processing Pattern Replacement** - 恢复终端规则编辑、样板元数据、planning / execution 替换语义
- [ ] **Phase 4: Port Crafting Continuation** - 恢复 continuation 提交流、等待状态跟踪与 CPU UI 投影
- [ ] **Phase 5: Harden Regression and Verify Migration** - 用测试、结构契约和目标平台验证证明迁移结果可用

## Phase Details

### Phase 1: Lock Target Platform Baseline
**Goal**: 选定可执行的 `1.21.x + NeoForge + AE2` 目标组合，并让基础工程、模组元数据、注册入口和配置读取在目标平台完成构建与最小可加载闭环。
**Depends on**: Nothing (first phase)
**Requirements**: [PLAT-01, PLAT-02, PLAT-03]
**Success Criteria** (what must be TRUE):
  1. 项目可以在选定目标组合上完成依赖解析、编译和打包。
  2. 模组元数据、`@Mod` 入口、注册流程与菜单/屏幕绑定在目标平台可被加载。
  3. `processingPatternReplacementEnabled` 与 `craftingContinuationEnabled` 在目标平台仍可读取并控制功能 gate。
  4. 迁移专用分支已建立，后续功能迁移不再继续落在 `master`。
**Plans**: 3 plans

Plans:
- [ ] 01-01: 锁定目标 `1.21.x + NeoForge + AE2` 版本组合并迁移构建插件、Gradle/JDK、模组元数据文件
- [ ] 01-02: 迁移 bootstrap、注册入口、菜单/屏幕基础绑定和公共配置读取链路
- [ ] 01-03: 让目标平台完成最小构建/启动验证，并补基础结构契约

### Phase 2: Port Multi-Level Emitter
**Goal**: 在目标平台恢复 multi-level emitter 的物品/部件注册、菜单交互、运行时 watcher 逻辑、表达式、模糊匹配与持久化兼容。
**Depends on**: Phase 1
**Requirements**: [EMIT-01, EMIT-02, EMIT-03, EMIT-04, COMP-01]
**Success Criteria** (what must be TRUE):
  1. 玩家可以在目标平台获得、放置并打开 multi-level emitter。
  2. emitter 的槽位配置、阈值、比较关系、匹配模式和 crafting 模式保存/读取后仍有效。
  3. emitter 仍能根据库存、模糊匹配、crafting 状态与表达式结果输出正确红石行为。
  4. 旧版 emitter NBT 被安全读取，至少能保持等价语义或明确降级。
**Plans**: 3 plans

Plans:
- [ ] 02-01: 迁移 emitter item / runtime part / AE2 part 接缝与 watcher 相关平台代码
- [ ] 02-02: 迁移 menu / screen / network 打开链路与表达式交互协议
- [ ] 02-03: 补 emitter 持久化与行为回归测试，验证旧 NBT 与新平台语义一致

### Phase 3: Port Processing Pattern Replacement
**Goal**: 在目标平台恢复 processing terminal 规则编辑、规则持久化、replacement-aware pattern 解码，以及 planning / execution 侧候选输入替换语义。
**Depends on**: Phase 2
**Requirements**: [PATT-01, PATT-02, PATT-03, COMP-02]
**Success Criteria** (what must be TRUE):
  1. processing terminal 仍可编辑并保存 replacement rule 草稿到 encoded pattern。
  2. 旧版 encoded pattern 上的 replacement metadata 在目标平台能被安全读取或明确降级。
  3. replacement-aware processing pattern 仍能在 planning / execution 中选择候选输入。
  4. 相关 feature gate 关闭时不会错误注入 replacement 功能。
**Plans**: 3 plans

Plans:
- [ ] 03-01: 迁移 terminal menu / screen mixin 与 rule host / 草稿交互链路
- [ ] 03-02: 迁移 replacement metadata 持久化、decoder 和 AE2 planning / execution 注入点
- [ ] 03-03: 用规则执行矩阵与兼容性回归验证旧样板元数据语义

### Phase 4: Port Crafting Continuation
**Goal**: 在目标平台恢复 continuation 模式切换、部分提交、等待分支追踪、保存数据和 CPU 菜单/屏幕状态投影。
**Depends on**: Phase 3
**Requirements**: [CONT-01, CONT-02, CONT-03, COMP-03]
**Success Criteria** (what must be TRUE):
  1. craft confirm 流程仍可选择 continuation / ignore-missing 模式并传入提交链。
  2. continuation 模式下，缺料 crafting job 会进入等待分支而不是整体硬失败。
  3. CPU 菜单与屏幕仍会展示 continuation 的等待详情与运行中分支摘要。
  4. continuation 相关保存数据在迁移后能被安全读取或明确处理。
**Plans**: 3 plans

Plans:
- [ ] 04-01: 迁移 craft confirm UI / menu / submit bridge 与提交拦截链路
- [ ] 04-02: 迁移 waiting status service、saved data、CPU menu/screen 投影与相关注入点
- [ ] 04-03: 补 continuation 生命周期与兼容性测试，验证旧等待状态处理逻辑

### Phase 5: Harden Regression and Verify Migration
**Goal**: 让迁移分支具备可验证、可持续推进的基础，通过回归测试、平台契约和目标环境验证证明功能等价迁移成立。
**Depends on**: Phase 4
**Requirements**: [VER-01, VER-02, VER-03]
**Success Criteria** (what must be TRUE):
  1. 关键 JUnit 回归测试通过，或等价的新测试已替换并记录原因。
  2. 新平台接缝拥有额外结构/兼容性契约测试，能保护构建元数据、类路径或资源路径变化。
  3. 迁移分支可执行至少一轮构建与关键验证命令，证明 mod 在目标平台启动到可验证状态。
  4. 剩余风险、未完成项和后续 hardening 方向被明确记录，不留隐性 blocker。
**Plans**: 3 plans

Plans:
- [ ] 05-01: 刷新现有回归测试与结构契约，使其面向 NeoForge 1.21 接缝继续有效
- [ ] 05-02: 执行目标平台构建、启动和关键功能验证，并修复暴露问题
- [ ] 05-03: 收口迁移文档、已知风险与后续 hardening 清单

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Lock Target Platform Baseline | 0/3 | Not started | - |
| 2. Port Multi-Level Emitter | 0/3 | Not started | - |
| 3. Port Processing Pattern Replacement | 0/3 | Not started | - |
| 4. Port Crafting Continuation | 0/3 | Not started | - |
| 5. Harden Regression and Verify Migration | 0/3 | Not started | - |
