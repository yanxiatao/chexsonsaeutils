# Service/Controller/Manager类重命名与拆分方案

## 概述

遵循CLAUDE.md规范7：禁止出现XxxService/XxxManager/XxxController这些极大概率会在后期发展为超级对象的类。
处理逻辑应当只出现在接口实现层和独立逻辑层。

## 重命名方案（小型类）

### 1. ProcessingSlotTagService → ProcessingSlotTagExpander
- **文件**: `pattern/replacement/ProcessingSlotTagService.java`
- **行数**: 66行
- **职责**: 从ItemStack提取tag，构建ProcessingSlotCandidateGroup
- **方法**: extractSourceTagIds, buildCandidateGroups, isSelectableCandidate, sharesAnySourceTag
- **引用位置**: 
  - `mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java`
  - `client/gui/implementations/ProcessingPatternReplacementScreen.java`
- **新名称理由**: Expander反映其"展开tag到候选物品组"的职责

### 2. EnhancedCraftingStatusService → CraftingStatusEnhancer
- **文件**: `crafting/status/EnhancedCraftingStatusService.java`
- **行数**: 141行
- **职责**: 附加blocked amounts和pattern times到crafting status/summary
- **方法**: attachBlockedAmounts, copyBlockedAmountsBySerial, attachPatternTimes, sortedPatternTimes
- **引用位置**: 
  - `mixin/ae2ct/AE2CTRecipeHelperCompatMixin.java`
  - `mixin/ae2/crafting/CraftingStatusEnhancedStatusMixin.java`
  - `mixin/ae2/client/gui/CraftConfirmTableRendererEnhancedStatusMixin.java`
  - `mixin/ae2/client/gui/CraftingCPUScreenEnhancedStatusMixin.java`
  - `mixin/ae2/menu/CraftingPlanSummaryEnhancedStatusMixin.java`
- **新名称理由**: Enhancer反映其"增强现有status对象"的职责

### 3. ProcessingExecutionBudgetController → ProcessingExecutionBudget
- **文件**: `crafting/directprocessing/ProcessingExecutionBudgetController.java`
- **行数**: 165行
- **职责**: Token-based执行预算管理（admit/complete/aeReturn tokens + time budget）
- **方法**: tryClaimAdmit, tryClaimComplete, tryClaimAeReturn, hasTimeBudget, resetForTick
- **引用位置**: 
  - `blockentity/directprocessing/AEDirectProcessingMachineBlockEntity.java`
- **新名称理由**: 移除Controller后缀，直接表示"预算"本身

### 4. ScaledCraftingPatternEligibilityService → ScaledCraftingPatternAnalyzer
- **文件**: `crafting/formalmachine/ScaledCraftingPatternEligibilityService.java`
- **行数**: 120行
- **职责**: 分析AECraftingPattern是否eligible for scaled crafting，计算max multiplier
- **方法**: analyze, buildScaledCraftingGrid, capMultiplier, createScaledPattern
- **引用位置**: 无直接import（可能通过静态方法调用）
- **新名称理由**: Analyzer反映其"分析pattern eligibility"的职责

## 重命名方案（中型类）

### 5. MachineRecipeDiscoveryService → MachineRecipeIndexBuilder
- **文件**: `crafting/directprocessing/MachineRecipeDiscoveryService.java`
- **行数**: 554行
- **职责**: 从recipe registry发现机器配方，构建MachineRecipeIndex
- **核心方法**: buildIndex, buildIndexTemplate, validateRecipeTypeIds, validateImportRequest
- **引用位置**: 
  - `blockentity/directprocessing/AEDirectProcessingMachineBlockEntity.java`
- **新名称理由**: IndexBuilder反映其"构建索引"的核心职责

### 6. CraftingContinuationStatusService → CraftingContinuationTracker
- **文件**: `crafting/status/CraftingContinuationStatusService.java`
- **行数**: 517行
- **职责**: 追踪waiting crafts状态，reconcile waiting inputs，管理持久化
- **核心方法**: trackJob, clearCompletedJob, reconcileWaitingInputs, buildSnapshot
- **引用位置**: 
  - `crafting/submit/CraftingContinuationPartialSubmit.java`
  - `mixin/ae2/crafting/CraftingServiceContinuationMixin.java`
  - `mixin/ae2/client/gui/CraftingCPUScreenContinuationMixin.java`
  - `mixin/ae2/menu/CraftingCPUMenuContinuationMixin.java`
  - `mixin/ae2/client/gui/CraftingStatusTableRendererContinuationMixin.java`
- **新名称理由**: Tracker反映其"追踪continuation状态"的职责

## 拆分方案（超大类）

### 7. FormalMachinePlanningAggregationService → 拆分为7个类
- **文件**: `crafting/planning/FormalMachinePlanningAggregationService.java`
- **行数**: 1917行
- **职责**: Formal machine crafting plan aggregation全流程

#### 拆分策略：

##### 7.1 FormalMachinePlanRewriter（主入口）
- **新文件**: `crafting/planning/FormalMachinePlanRewriter.java`
- **职责**: 主入口 + Future包装
- **方法**: 
  - tryBeginCraftingCalculation (公开入口)
  - wrapNativeFuture
  - rewriteNativePlan (委托给其他组件)
  - supportsStrategy
- **估算行数**: ~150行

##### 7.2 FormalMachinePlanGraphBuilder
- **新文件**: `crafting/planning/FormalMachinePlanGraphBuilder.java`
- **职责**: 构建SelectedPlanGraph
- **方法**: 
  - buildSelectedPlanGraph
  - collapseEquivalentCandidates
  - comparePatternDefinitions
- **估算行数**: ~180行

##### 7.3 FormalMachineAggregationCandidateBuilder
- **新文件**: `crafting/planning/FormalMachineAggregationCandidateBuilder.java`
- **职责**: 构建HostAggregationCandidate
- **方法**: 
  - buildHostAggregationCandidates
  - buildHostAggregationCandidate
  - selectSegmentNodes
  - formalInputsByOutput
- **估算行数**: ~300行

##### 7.4 FormalMachineDependencySegmenter
- **新文件**: `crafting/planning/FormalMachineDependencySegmenter.java`
- **职责**: 依赖分割算法
- **方法**: 
  - splitPerPatternFormalAggregationSegments
  - splitFormalDependencySegments (两个重载)
- **估算行数**: ~250行

##### 7.5 FormalMachinePlanTopologySorter
- **新文件**: `crafting/planning/FormalMachinePlanTopologySorter.java`
- **职责**: 拓扑排序
- **方法**: 
  - topoSortDependencyOutputs
  - orderPatternTimesByDependencies
- **估算行数**: ~200行

##### 7.6 FormalMachinePlanInputOutputDescriptor
- **新文件**: `crafting/planning/FormalMachinePlanInputOutputDescriptor.java`
- **职责**: 输入输出描述
- **方法**: 
  - describeAggregatedBoundaryInputs
  - describeAggregationInputs
  - extractExternalMissingInputs
  - restoreRecursiveInitialBoundaryOutputs
  - computeRewrittenUsedItems
- **估算行数**: ~400行

##### 7.7 FormalMachinePlanningRewriteContext（从内部类提取）
- **新文件**: `crafting/planning/FormalMachinePlanningRewriteContext.java`
- **职责**: 重写上下文状态管理
- **原位置**: FormalMachinePlanningAggregationService内部类
- **估算行数**: ~250行

##### 7.8 AggregatingPlanningFuture（保持内部类或独立）
- **决策**: 保持为FormalMachinePlanRewriter的私有内部类
- **原因**: 仅被主入口使用，无需独立

##### 拆分后总行数估算：
- FormalMachinePlanRewriter: ~150行
- FormalMachinePlanGraphBuilder: ~180行
- FormalMachineAggregationCandidateBuilder: ~300行
- FormalMachineDependencySegmenter: ~250行
- FormalMachinePlanTopologySorter: ~200行
- FormalMachinePlanInputOutputDescriptor: ~400行
- FormalMachinePlanningRewriteContext: ~250行
- Records + 工具方法: ~187行
- **总计**: ~1917行（与原始一致）

## 待分析

### 8. AbstractHighCapacityCraftingHostBlockEntity
- **文件**: `blockentity/crafting/AbstractHighCapacityCraftingHostBlockEntity.java`
- **行数**: 待统计
- **状态**: 待分析职责

## 执行顺序

1. ✅ 分析所有类职责
2. 重命名小型类（1-4）
3. 重命名中型类（5-6）
4. 拆分FormalMachinePlanningAggregationService（7）
5. 分析与拆分AbstractHighCapacityCraftingHostBlockEntity（8）
6. 更新所有引用
7. 验证编译
