# Sprint 2: Core Integration

Backport FormalMachine planning, CraftingContinuation, crafted status enhancement,
and persistence from 1.21.1 NeoForge to 1.20.1 Forge master.

## Files to create (copied from 1.21.1 migration branch)

### Batch A: FormalMachine planning (~11 files)
- `crafting/planning/AggregatedCraftingPlan.java`
- `crafting/planning/FormalMachineAggregationRemainderHelper.java`
- `crafting/planning/FormalMachineAggregationStep.java`
- `crafting/planning/FormalMachineHostLocator.java`
- `crafting/planning/FormalMachinePlanningAggregator.java`
- `crafting/planning/FormalMachinePlanningProvider.java`
- `crafting/formalmachine/BulkPatternExtractionPlanner.java`
- `crafting/formalmachine/FormalMachineAggregatedPattern.java`
- `crafting/formalmachine/FormalMachineAggregatedPatternDecoder.java`
- `crafting/formalmachine/FormalMachineAggregatedPatternImpl.java`
- `crafting/formalmachine/FormalMachineDelegatingPattern.java`

### Batch B: CraftingContinuation + Status (~16 files)
- `crafting/CraftingContinuationMode.java`
- `crafting/submit/CraftingContinuationPartialSubmit.java`
- `crafting/submit/CraftingContinuationSubmitBridge.java`
- `crafting/persistence/CraftingContinuationSavedData.java`
- `crafting/persistence/HighCapacityPatternHostSavedData.java`
- `crafting/status/CraftingStatusEnhancer.java`
- `crafting/status/EnhancedCraftingBlockedTracker.java`
- `crafting/status/EnhancedCraftingPlanSummaryEntry.java`
- `crafting/status/EnhancedCraftingStatusEntry.java`
- `crafting/status/EnhancedCraftingStatusFormatting.java`
- `crafting/status/CraftingContinuationStatusSnapshot.java`
- `crafting/status/CraftingContinuationTracker.java`
- `crafting/status/CraftingContinuationWaitingBranch.java`
- `crafting/status/CraftingContinuationWaitingDetail.java`

### Batch C: Mixins + Accessors (~27 files)
Various mixins for continuation, enhanced status, formal machine, accessors.

### Batch D: Updated existing files
- AbstractHighCapacityCraftingHostBlockEntity (massive ~1100 line diff)
- ChexsonsaeutilsContent.java (add registrations)
- Chexsonsaeutils.java (add registrations)
- ChexsonsaeutilsMixinPlugin.java (add mixin refs)
- chexsonsaeutils.mixins.json (add mixins)
- MultiLevelEmitterRuntimeScreen.java (update)
- MultiLevelEmitterMenu.java (update)
- MultiLevelEmitterRuntimePart.java (update)
- MultiLevelEmitterUtils.java (update)

## Strategy
1. Batch A+B+C: Copy via 3 parallel @fixer (mechanical copy + minimal AE2 API adaptation)
2. Batch D: Direct fixes + AbstractHighCapacity diff application
3. Build + fix remaining errors
4. Oracle review
5. Commit
