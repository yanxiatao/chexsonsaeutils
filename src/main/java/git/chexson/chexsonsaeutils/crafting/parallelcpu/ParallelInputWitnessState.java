package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parallel CPU 对规划期 exact 输入分配的可持久化执行游标。 */
final class ParallelInputWitnessState {

    private static final String NBT_PATTERNS = "patterns";
    private static final String NBT_OWNERSHIP = "ownership";
    private static final String NBT_DEFINITION = "definition";
    private static final String NBT_KEY = "key";
    private static final String NBT_SLOT_COUNT = "slotCount";
    private static final String NBT_RUNS = "runs";
    private static final String NBT_SLOT = "slot";
    private static final String NBT_TEMPLATE_AMOUNT = "templateAmount";
    private static final String NBT_UNITS = "units";

    private final Map<AEItemKey, PatternState> patterns = new LinkedHashMap<>();
    private final Map<AEKey, OwnershipState> ownership = new LinkedHashMap<>();

    ParallelInputWitnessState(PlanningInputWitness witness) {
        if (witness == null || !witness.complete()) {
            throw new IllegalArgumentException("complete witness required");
        }
        for (var pattern : witness.patternInputs()) {
            var state = new PatternState(pattern.inputSlotCount());
            for (var run : pattern.runs()) {
                state.slots.get(run.inputSlot()).add(new InputRun(
                        run.exactKey(), run.templateAmount(), run.templateUnitCount()));
            }
            patterns.put(pattern.patternDefinition(), state);
        }
        for (var keyOwnership : witness.ownership()) {
            var runs = new ArrayList<OwnershipRun>();
            for (var run : keyOwnership.runs()) {
                runs.add(new OwnershipRun(run.patternDefinition(), run.inputSlot(),
                        run.templateAmount(), run.templateUnitCount()));
            }
            ownership.put(keyOwnership.exactKey(), new OwnershipState(runs));
        }
    }

    private ParallelInputWitnessState() {
    }

    boolean hasPattern(IPatternDetails details) {
        return definitionOf(details) != null && patterns.containsKey(definitionOf(details));
    }

    long maxExecutions(
            IPatternDetails details,
            ListCraftingInventory inventory,
            long requested
    ) {
        AEItemKey definition = definitionOf(details);
        PatternState pattern = definition == null ? null : patterns.get(definition);
        if (pattern == null || requested <= 0L || details.getInputs().length != pattern.slots.size()) {
            return 0L;
        }
        long low = 0L;
        long high = requested;
        while (low < high) {
            long difference = high - low;
            long candidate = low + difference / 2L + difference % 2L;
            if (canPrepare(definition, details, pattern, inventory, candidate)) {
                low = candidate;
            } else {
                high = candidate - 1L;
            }
        }
        return low;
    }

    long dispatchLimit(
            IPatternDetails details,
            ListCraftingInventory inventory,
            long requested
    ) {
        AEItemKey definition = definitionOf(details);
        PatternState pattern = definition == null ? null : patterns.get(definition);
        if (pattern == null || requested <= 0L || details.getInputs().length != pattern.slots.size()) {
            return 0L;
        }
        long available = maxExecutions(details, inventory, requested);
        if (available <= 1L) {
            return available;
        }
        long homogeneous = available;
        for (int slot = 0; slot < pattern.slots.size(); slot++) {
            long unitsPerExecution = details.getInputs()[slot].getMultiplier();
            if (unitsPerExecution <= 0L) {
                return 0L;
            }
            homogeneous = Math.min(homogeneous,
                    homogeneousPrefix(pattern.slots.get(slot), unitsPerExecution, homogeneous));
            if (homogeneous <= 1L) {
                return homogeneous;
            }
        }
        return homogeneous;
    }

    BatchLimit explainBatchLimit(
            IPatternDetails details,
            ListCraftingInventory inventory,
            long requested
    ) {
        AEItemKey definition = definitionOf(details);
        PatternState pattern = definition == null ? null : patterns.get(definition);
        if (pattern == null) {
            return new BatchLimit(0L, "missing-pattern");
        }
        if (requested <= 0L || details.getInputs().length != pattern.slots.size()) {
            return new BatchLimit(0L, "invalid-request");
        }
        long materialLimit = materialLimit(details, pattern, inventory, requested);
        long ownershipLimit = ownershipLimit(definition, details, pattern, inventory, requested);
        long executions = Math.min(materialLimit, ownershipLimit);
        String reason;
        if (materialLimit == ownershipLimit) {
            int slot = limitingMaterialSlot(details, pattern, inventory, requested);
            reason = "material+ownership(slot=" + slot + ", k=" + materialLimit + ")";
        } else if (materialLimit < ownershipLimit) {
            reason = "material(slot=" + limitingMaterialSlot(details, pattern, inventory, requested)
                    + ", k=" + materialLimit + ")";
        } else {
            reason = "ownership(k=" + ownershipLimit + ")";
        }
        return new BatchLimit(executions, reason);
    }

    @Nullable
    Prepared prepare(
            IPatternDetails details,
            ListCraftingInventory inventory,
            long executions,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        return prepare(details, inventory, executions, executions,
                expectedOutputs, expectedContainerItems);
    }

    @Nullable
    Prepared prepareDispatch(
            IPatternDetails details,
            ListCraftingInventory inventory,
            long dispatchLimit,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        return prepare(details, inventory, 1L, dispatchLimit,
                expectedOutputs, expectedContainerItems);
    }

    @Nullable
    private Prepared prepare(
            IPatternDetails details,
            ListCraftingInventory inventory,
            long extractedExecutions,
            long dispatchLimit,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        AEItemKey definition = definitionOf(details);
        PatternState pattern = definition == null ? null : patterns.get(definition);
        if (pattern == null || extractedExecutions <= 0L || dispatchLimit < extractedExecutions
                || details.getInputs().length != pattern.slots.size()) {
            return null;
        }
        var holder = new KeyCounter[pattern.slots.size()];
        var advances = new ArrayList<SlotAdvance>(pattern.slots.size());
        var ownershipAdvances = new ArrayList<OwnershipAdvance>();
        for (int slot = 0; slot < pattern.slots.size(); slot++) {
            var input = details.getInputs()[slot];
            long requiredUnits = multiplyOrMax(input.getMultiplier(), extractedExecutions);
            var selection = select(pattern.slots.get(slot), requiredUnits, inventory, null);
            if (selection == null) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, holder);
                return null;
            }
            var counter = holder[slot] = new KeyCounter();
            for (var take : selection.takes()) {
                long amount = multiplyOrMax(take.units(), take.templateAmount());
                long extracted = inventory.extract(take.key(), amount, Actionable.MODULATE);
                if (extracted != amount) {
                    CraftingCpuHelper.reinjectPatternInputs(inventory, holder);
                    return null;
                }
                counter.add(take.key(), extracted);
                ownershipAdvances.add(new OwnershipAdvance(take.key(), take.units()));
                AEKey remaining = input.getRemainingKey(take.key());
                if (remaining != null) {
                    expectedContainerItems.add(remaining, take.units());
                }
            }
            advances.add(new SlotAdvance(slot, requiredUnits));
        }
        for (var output : details.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                expectedOutputs.add(output.what(), multiplyOrMax(output.amount(), extractedExecutions));
            }
        }
        return new Prepared(holder, advances, ownershipAdvances, definition,
                extractedExecutions, dispatchLimit);
    }

    void commit(Prepared prepared) {
        commit(prepared, prepared.extractedExecutions());
    }

    void commit(Prepared prepared, long acceptedExecutions) {
        if (acceptedExecutions <= 0L || acceptedExecutions > prepared.dispatchLimit()) {
            throw new IllegalArgumentException("accepted dispatch outside prepared limit");
        }
        PatternState pattern = patterns.get(prepared.definition());
        if (pattern == null) {
            throw new IllegalStateException("witness pattern disappeared");
        }
        if (acceptedExecutions == prepared.extractedExecutions()) {
            for (var advance : prepared.advances()) {
                consume(pattern.slots.get(advance.slot()), advance.units());
            }
            for (var advance : prepared.ownershipAdvances()) {
                consumeOwnership(prepared.definition(), advance.key(), advance.units());
            }
            return;
        }
        for (var advance : prepared.advances()) {
            long unitsPerExecution = advance.units() / prepared.extractedExecutions();
            if (unitsPerExecution * prepared.extractedExecutions() != advance.units()) {
                throw new IllegalStateException("prepared slot advance is not execution-aligned");
            }
            consume(pattern.slots.get(advance.slot()),
                    multiplyOrMax(unitsPerExecution, acceptedExecutions));
        }
        for (var advance : prepared.ownershipAdvances()) {
            long unitsPerExecution = advance.units() / prepared.extractedExecutions();
            if (unitsPerExecution * prepared.extractedExecutions() != advance.units()) {
                throw new IllegalStateException("prepared ownership advance is not execution-aligned");
            }
            consumeOwnership(prepared.definition(), advance.key(),
                    multiplyOrMax(unitsPerExecution, acceptedExecutions));
        }
    }

    boolean isEmpty() {
        for (var pattern : patterns.values()) {
            for (var slot : pattern.slots) {
                if (remainingUnits(slot) > 0L) {
                    return false;
                }
            }
        }
        return true;
    }

    CompoundTag writeToNBT(HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        ListTag patternTags = new ListTag();
        for (var entry : patterns.entrySet()) {
            CompoundTag patternTag = new CompoundTag();
            patternTag.put(NBT_DEFINITION, entry.getKey().toTag(registries));
            patternTag.putInt(NBT_SLOT_COUNT, entry.getValue().slots.size());
            ListTag runTags = new ListTag();
            for (int slot = 0; slot < entry.getValue().slots.size(); slot++) {
                for (var run : entry.getValue().slots.get(slot)) {
                    if (run.remainingUnits <= 0L) {
                        continue;
                    }
                    runTags.add(writeRun(registries, slot, run.key, run.templateAmount, run.remainingUnits));
                }
            }
            patternTag.put(NBT_RUNS, runTags);
            patternTags.add(patternTag);
        }
        root.put(NBT_PATTERNS, patternTags);
        ListTag ownershipTags = new ListTag();
        for (var entry : ownership.entrySet()) {
            CompoundTag keyTag = new CompoundTag();
            keyTag.put(NBT_KEY, entry.getKey().toTagGeneric(registries));
            ListTag runTags = new ListTag();
            for (var run : entry.getValue().runs) {
                if (run.remainingUnits <= 0L) {
                    continue;
                }
                CompoundTag runTag = writeRun(registries, run.slot, null,
                        run.templateAmount, run.remainingUnits);
                runTag.put(NBT_DEFINITION, run.definition.toTag(registries));
                runTags.add(runTag);
            }
            keyTag.put(NBT_RUNS, runTags);
            ownershipTags.add(keyTag);
        }
        root.put(NBT_OWNERSHIP, ownershipTags);
        return root;
    }

    static ParallelInputWitnessState readFromNBT(CompoundTag root, HolderLookup.Provider registries) {
        var state = new ParallelInputWitnessState();
        for (var tag : root.getList(NBT_PATTERNS, Tag.TAG_COMPOUND)) {
            CompoundTag patternTag = (CompoundTag) tag;
            AEItemKey definition = AEItemKey.fromTag(registries, patternTag.getCompound(NBT_DEFINITION));
            int slotCount = patternTag.getInt(NBT_SLOT_COUNT);
            if (definition == null || slotCount <= 0) {
                continue;
            }
            var pattern = new PatternState(slotCount);
            for (var runValue : patternTag.getList(NBT_RUNS, Tag.TAG_COMPOUND)) {
                CompoundTag runTag = (CompoundTag) runValue;
                int slot = runTag.getInt(NBT_SLOT);
                AEKey key = AEKey.fromTagGeneric(registries, runTag.getCompound(NBT_KEY));
                long templateAmount = runTag.getLong(NBT_TEMPLATE_AMOUNT);
                long units = runTag.getLong(NBT_UNITS);
                if (slot >= 0 && slot < slotCount && key != null && templateAmount > 0L && units > 0L) {
                    pattern.slots.get(slot).add(new InputRun(key, templateAmount, units));
                }
            }
            state.patterns.put(definition, pattern);
        }
        for (var tag : root.getList(NBT_OWNERSHIP, Tag.TAG_COMPOUND)) {
            CompoundTag keyTag = (CompoundTag) tag;
            AEKey exactKey = AEKey.fromTagGeneric(registries, keyTag.getCompound(NBT_KEY));
            if (exactKey == null) {
                continue;
            }
            var runs = new ArrayList<OwnershipRun>();
            for (var runValue : keyTag.getList(NBT_RUNS, Tag.TAG_COMPOUND)) {
                CompoundTag runTag = (CompoundTag) runValue;
                AEItemKey definition = AEItemKey.fromTag(registries, runTag.getCompound(NBT_DEFINITION));
                int slot = runTag.getInt(NBT_SLOT);
                long templateAmount = runTag.getLong(NBT_TEMPLATE_AMOUNT);
                long units = runTag.getLong(NBT_UNITS);
                if (definition != null && slot >= 0 && templateAmount > 0L && units > 0L) {
                    runs.add(new OwnershipRun(definition, slot, templateAmount, units));
                }
            }
            state.ownership.put(exactKey, new OwnershipState(runs));
        }
        return state;
    }

    private long materialLimit(IPatternDetails details, PatternState pattern,
            ListCraftingInventory inventory, long requested) {
        long low = 0L;
        long high = requested;
        while (low < high) {
            long difference = high - low;
            long candidate = low + difference / 2L + difference % 2L;
            Map<AEKey, Long> simulated = new HashMap<>();
            boolean available = true;
            for (int slot = 0; slot < pattern.slots.size(); slot++) {
                long requiredUnits = multiplyOrMax(details.getInputs()[slot].getMultiplier(), candidate);
                if (select(pattern.slots.get(slot), requiredUnits, inventory, simulated) == null) {
                    available = false;
                    break;
                }
            }
            if (available) {
                low = candidate;
            } else {
                high = candidate - 1L;
            }
        }
        return low;
    }

    private int limitingMaterialSlot(IPatternDetails details, PatternState pattern,
            ListCraftingInventory inventory, long requested) {
        long lowest = Long.MAX_VALUE;
        int limiting = -1;
        for (int slot = 0; slot < pattern.slots.size(); slot++) {
            long multiplier = details.getInputs()[slot].getMultiplier();
            if (multiplier <= 0L) {
                return slot;
            }
            long units = 0L;
            for (var run : pattern.slots.get(slot)) {
                long available = inventory.extract(run.key, Long.MAX_VALUE, Actionable.SIMULATE);
                units = addOrMax(units, Math.min(run.remainingUnits, available / run.templateAmount));
            }
            long executions = Math.min(requested, units / multiplier);
            if (executions < lowest) {
                lowest = executions;
                limiting = slot;
            }
        }
        return limiting;
    }

    private long ownershipLimit(AEItemKey definition, IPatternDetails details, PatternState pattern,
            ListCraftingInventory inventory, long requested) {
        long low = 0L;
        long high = requested;
        while (low < high) {
            long difference = high - low;
            long candidate = low + difference / 2L + difference % 2L;
            if (ownsRequiredFront(definition, pattern, details, inventory, candidate)) {
                low = candidate;
            } else {
                high = candidate - 1L;
            }
        }
        return low;
    }

    private boolean canPrepare(AEItemKey definition, IPatternDetails details, PatternState pattern,
            ListCraftingInventory inventory, long executions) {
        Map<AEKey, Long> simulated = new HashMap<>();
        for (int slot = 0; slot < pattern.slots.size(); slot++) {
            long requiredUnits = multiplyOrMax(details.getInputs()[slot].getMultiplier(), executions);
            if (select(pattern.slots.get(slot), requiredUnits, inventory, simulated) == null) {
                return false;
            }
        }
        return ownsRequiredFront(definition, pattern, details, inventory, executions);
    }

    private boolean ownsRequiredFront(AEItemKey definition, PatternState pattern,
            IPatternDetails details, ListCraftingInventory inventory, long executions) {
        Map<AEKey, Long> requestedItems = new HashMap<>();
        for (int slot = 0; slot < pattern.slots.size(); slot++) {
            long required = multiplyOrMax(details.getInputs()[slot].getMultiplier(), executions);
            long remaining = required;
            for (var run : pattern.slots.get(slot)) {
                if (remaining <= 0L) {
                    break;
                }
                long take = Math.min(remaining, run.remainingUnits);
                long items = multiplyOrMax(take, run.templateAmount);
                requestedItems.merge(run.key, items, ParallelInputWitnessState::addOrMax);
                remaining -= take;
            }
            if (remaining > 0L) {
                return false;
            }
        }
        for (var entry : requestedItems.entrySet()) {
            OwnershipState state = ownership.get(entry.getKey());
            if (state == null) {
                return false;
            }
            long ownedByCurrent = 0L;
            long reservedBeforeCurrent = 0L;
            var reservedOwners = new java.util.HashSet<OwnerSlot>();
            boolean reachedCurrent = false;
            for (var run : state.runs) {
                if (run.remainingUnits <= 0L) {
                    continue;
                }
                long items = multiplyOrMax(run.remainingUnits, run.templateAmount);
                if (run.definition.equals(definition)) {
                    reachedCurrent = true;
                    ownedByCurrent = addOrMax(ownedByCurrent, items);
                } else if (!reachedCurrent
                        && reservedOwners.add(new OwnerSlot(run.definition, run.slot))) {
                    reservedBeforeCurrent = addOrMax(reservedBeforeCurrent, run.templateAmount);
                }
            }
            if (!reachedCurrent || ownedByCurrent < entry.getValue()) {
                return false;
            }
            long available = inventory.extract(
                    entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            if (available < addOrMax(reservedBeforeCurrent, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private Selection select(List<InputRun> runs, long requiredUnits, ListCraftingInventory inventory,
            @Nullable Map<AEKey, Long> simulated) {
        long remaining = requiredUnits;
        var takes = new ArrayList<Take>();
        for (var run : runs) {
            if (remaining <= 0L) {
                break;
            }
            long availableItems = simulated == null
                    ? inventory.extract(run.key, Long.MAX_VALUE, Actionable.SIMULATE)
                    : simulated.computeIfAbsent(run.key,
                            key -> inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE));
            long availableUnits = availableItems / run.templateAmount;
            long take = Math.min(remaining, Math.min(run.remainingUnits, availableUnits));
            if (take <= 0L) {
                continue;
            }
            takes.add(new Take(run.key, run.templateAmount, take));
            if (simulated != null) {
                simulated.put(run.key, availableItems - multiplyOrMax(take, run.templateAmount));
            }
            remaining -= take;
        }
        return remaining == 0L ? new Selection(takes) : null;
    }

    private void consume(List<InputRun> runs, long units) {
        long remaining = units;
        for (var run : runs) {
            if (remaining <= 0L) {
                break;
            }
            long take = Math.min(remaining, run.remainingUnits);
            run.remainingUnits -= take;
            remaining -= take;
        }
        if (remaining > 0L) {
            throw new IllegalStateException("witness allocation underflow");
        }
    }

    private void consumeOwnership(AEItemKey definition, AEKey key, long units) {
        OwnershipState state = ownership.get(key);
        if (state == null) {
            throw new IllegalStateException("witness ownership disappeared");
        }
        long remaining = units;
        for (var run : state.runs) {
            if (remaining <= 0L) {
                break;
            }
            if (run.remainingUnits <= 0L || !run.definition.equals(definition)) {
                continue;
            }
            long take = Math.min(remaining, run.remainingUnits);
            run.remainingUnits -= take;
            remaining -= take;
        }
        if (remaining > 0L) {
            throw new IllegalStateException("witness ownership underflow");
        }
    }

    private static long homogeneousPrefix(
            List<InputRun> runs,
            long unitsPerExecution,
            long limit
    ) {
        if (unitsPerExecution <= 0L || limit <= 0L) {
            return 0L;
        }
        FiringIdentity first = firingIdentity(runs, unitsPerExecution, 0L);
        if (first == null) {
            return 0L;
        }
        if (first.segments().size() == 1
                && first.segments().getFirst().units() == unitsPerExecution) {
            for (var run : runs) {
                if (run.remainingUnits > 0L) {
                    return Math.min(limit, run.remainingUnits / unitsPerExecution);
                }
            }
            return 0L;
        }
        long matching = 1L;
        while (matching < limit) {
            FiringIdentity next = firingIdentity(runs, unitsPerExecution, matching);
            if (!first.equals(next)) {
                break;
            }
            matching++;
        }
        return matching;
    }

    @Nullable
    private static FiringIdentity firingIdentity(
            List<InputRun> runs,
            long unitsPerExecution,
            long firingIndex
    ) {
        long skip = multiplyOrMax(unitsPerExecution, firingIndex);
        long remaining = unitsPerExecution;
        var segments = new ArrayList<IdentitySegment>();
        for (var run : runs) {
            if (skip >= run.remainingUnits) {
                skip -= run.remainingUnits;
                continue;
            }
            long available = run.remainingUnits - skip;
            long take = Math.min(remaining, available);
            segments.add(new IdentitySegment(run.key, run.templateAmount, take));
            remaining -= take;
            skip = 0L;
            if (remaining == 0L) {
                return new FiringIdentity(segments);
            }
        }
        return null;
    }

    private static long remainingUnits(List<InputRun> runs) {
        long total = 0L;
        for (var run : runs) {
            total = addOrMax(total, run.remainingUnits);
        }
        return total;
    }

    private static CompoundTag writeRun(HolderLookup.Provider registries, int slot,
            @Nullable AEKey key, long templateAmount, long units) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(NBT_SLOT, slot);
        if (key != null) {
            tag.put(NBT_KEY, key.toTagGeneric(registries));
        }
        tag.putLong(NBT_TEMPLATE_AMOUNT, templateAmount);
        tag.putLong(NBT_UNITS, units);
        return tag;
    }

    @Nullable
    private static AEItemKey definitionOf(IPatternDetails details) {
        return details != null && details.getDefinition() instanceof AEItemKey definition ? definition : null;
    }

    private static long multiplyOrMax(long left, long right) {
        return left <= 0L || right <= 0L ? 0L
                : left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long addOrMax(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    record BatchLimit(long executions, String reason) {
    }

    record Prepared(KeyCounter[] holder, List<SlotAdvance> advances,
            List<OwnershipAdvance> ownershipAdvances, AEItemKey definition,
            long extractedExecutions, long dispatchLimit) {
        Prepared {
            advances = List.copyOf(advances);
            ownershipAdvances = List.copyOf(ownershipAdvances);
        }
    }

    private record FiringIdentity(List<IdentitySegment> segments) {
        FiringIdentity {
            segments = List.copyOf(segments);
        }
    }

    private record IdentitySegment(AEKey key, long templateAmount, long units) {
    }

    private record Selection(List<Take> takes) {
    }

    private record Take(AEKey key, long templateAmount, long units) {
    }

    private record OwnershipAdvance(AEKey key, long units) {
    }

    private record OwnerSlot(AEItemKey definition, int slot) {
    }

    private record SlotAdvance(int slot, long units) {
    }

    private static final class InputRun {
        final AEKey key;
        final long templateAmount;
        long remainingUnits;

        InputRun(AEKey key, long templateAmount, long remainingUnits) {
            this.key = key;
            this.templateAmount = templateAmount;
            this.remainingUnits = remainingUnits;
        }
    }

    private static final class PatternState {
        final List<List<InputRun>> slots;

        PatternState(int slotCount) {
            slots = new ArrayList<>(slotCount);
            for (int slot = 0; slot < slotCount; slot++) {
                slots.add(new ArrayList<>());
            }
        }
    }

    private static final class OwnershipRun {
        final AEItemKey definition;
        final int slot;
        final long templateAmount;
        long remainingUnits;

        OwnershipRun(AEItemKey definition, int slot, long templateAmount, long remainingUnits) {
            this.definition = definition;
            this.slot = slot;
            this.templateAmount = templateAmount;
            this.remainingUnits = remainingUnits;
        }
    }

    private static final class OwnershipState {
        final List<OwnershipRun> runs;

        OwnershipState(List<OwnershipRun> runs) {
            this.runs = runs;
        }
    }
}
