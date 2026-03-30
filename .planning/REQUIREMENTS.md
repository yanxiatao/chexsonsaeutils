# Requirements: Chexson's AE Utils 1.21 NeoForge Migration

**Defined:** 2026-03-30
**Core Value:** 在迁移到 `1.21.x + NeoForge` 后，现有全部核心功能必须继续可用，且不能靠牺牲行为一致性来换取“能编译通过”。

## v1 Requirements

### Platform

- [ ] **PLAT-01**: 项目可以在选定的 `Minecraft 1.21.x + NeoForge` 目标上完成依赖解析、编译、资源处理与重混淆打包
- [ ] **PLAT-02**: 模组元数据、加载入口、注册流程和菜单/屏幕绑定在目标平台上可正常加载
- [ ] **PLAT-03**: `processingPatternReplacementEnabled` 与 `craftingContinuationEnabled` 的公共配置仍能在目标平台读取并生效

### Multi-Level Emitter

- [ ] **EMIT-01**: 玩家可以在目标平台上获得并放置 multi-level emitter，并正常打开运行时菜单
- [ ] **EMIT-02**: multi-level emitter 的配置槽位数、阈值、比较模式、逻辑关系、匹配模式与 crafting 模式在保存/读取后保持有效
- [ ] **EMIT-03**: multi-level emitter 仍能依据库存、模糊匹配与 crafting 状态计算红石输出
- [ ] **EMIT-04**: multi-level emitter 的表达式编辑、校验、同步与运行时求值行为保持现有语义

### Pattern Replacement

- [ ] **PATT-01**: 玩家仍可在 processing terminal 中为输入槽编辑 replacement rule 草稿并保存到 encoded pattern
- [ ] **PATT-02**: encoded pattern 上的 replacement metadata 在目标平台仍可被解码并形成 replacement-aware processing pattern
- [ ] **PATT-03**: planning / execution 侧仍会根据 replacement rule 选择候选输入，而不是退回原始单一输入语义

### Crafting Continuation

- [ ] **CONT-01**: craft confirm 流程仍可切换 continuation / ignore-missing 模式并正确传入提交链
- [ ] **CONT-02**: continuation 模式下，AE2 crafting job 可在缺料时以等待分支状态继续运行，而不是整体硬失败
- [ ] **CONT-03**: CPU 菜单与屏幕仍能展示 continuation 的等待详情、等待分支与运行中分支摘要

### Persistence & Compatibility

- [ ] **COMP-01**: 旧版 emitter NBT 中的阈值、比较模式、逻辑关系、匹配/ crafting 模式和表达式字段可以被安全读取或降级
- [ ] **COMP-02**: 旧版 encoded pattern 中的 replacement metadata 可以被安全读取或明确降级，不得静默丢失语义
- [ ] **COMP-03**: continuation 相关的保存数据与状态投影在迁移后仍能安全处理，不得因版本切换导致崩溃

### Verification

- [ ] **VER-01**: 现有关键 JUnit 回归测试在迁移后保持通过，或被等价的新测试替换并记录原因
- [ ] **VER-02**: 对新平台接缝增加至少一组结构/兼容性契约测试，覆盖构建元数据、关键类路径或资源路径变化
- [ ] **VER-03**: 迁移分支上能够执行至少一轮构建与关键验证命令，证明 mod 可在目标平台启动到可验证状态

## v2 Requirements

### Hardening

- **HARD-01**: 为真实游戏运行时增加自动化 smoke test 或 GameTest
- **HARD-02**: 减少对 AE2 私有字段、私有构造器和 obfuscated 符号的反射依赖
- **HARD-03**: 梳理并清理历史遗留的 access transformer / 构建模板噪音

## Out of Scope

| Feature | Reason |
|---------|--------|
| 新增 emitter 功能、额外 AE2 UI 能力或新玩法 | 当前目标是迁移并保留功能，不是扩展产品边界 |
| 多平台/多 loader 支持 | 当前只承诺 `1.21 + NeoForge` |
| 与兼容性无关的大规模代码重构 | 会掩盖迁移回归来源，增加验证成本 |
| 与 AE2 现有风格无关的视觉重设计 | 现阶段优先保证行为等价 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| PLAT-01 | Phase 1 | Pending |
| PLAT-02 | Phase 1 | Pending |
| PLAT-03 | Phase 1 | Pending |
| EMIT-01 | Phase 2 | Pending |
| EMIT-02 | Phase 2 | Pending |
| EMIT-03 | Phase 2 | Pending |
| EMIT-04 | Phase 2 | Pending |
| PATT-01 | Phase 3 | Pending |
| PATT-02 | Phase 3 | Pending |
| PATT-03 | Phase 3 | Pending |
| CONT-01 | Phase 4 | Pending |
| CONT-02 | Phase 4 | Pending |
| CONT-03 | Phase 4 | Pending |
| COMP-01 | Phase 2 | Pending |
| COMP-02 | Phase 3 | Pending |
| COMP-03 | Phase 4 | Pending |
| VER-01 | Phase 5 | Pending |
| VER-02 | Phase 5 | Pending |
| VER-03 | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 19 total
- Mapped to phases: 19
- Unmapped: 0 ✅

---
*Requirements defined: 2026-03-30*
*Last updated: 2026-03-30 after initial definition*
