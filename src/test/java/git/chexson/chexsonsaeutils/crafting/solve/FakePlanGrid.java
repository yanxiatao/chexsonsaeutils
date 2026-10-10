package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeService;
import appeng.api.networking.IGridService;
import appeng.api.networking.IGridVisitor;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IStorageProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.api.storage.MEStorage;
import appeng.api.util.AEColor;
import git.chexson.chexsonsaeutils.crafting.color.PatternDetailsColorAccessor;
import com.google.common.collect.ImmutableSet;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared synthetic grid fixtures for planner tests. A {@link FakeGrid} wires a
 * fake storage service and crafting service together so planning code can run
 * without a Minecraft server.
 */
public final class FakePlanGrid {

    private FakePlanGrid() {
    }

    public static void assertPlansEqual(ICraftingPlan reference, ICraftingPlan actual) {
        assertNotNull(actual);
        assertEquals(reference.finalOutput(), actual.finalOutput(), "finalOutput");
        assertEquals(reference.simulation(), actual.simulation(), "simulation");
        assertEquals(reference.bytes(), actual.bytes(), "bytes");
        assertEquals(toPlain(reference.usedItems()), toPlain(actual.usedItems()), "usedItems");
        assertEquals(toPlain(reference.missingItems()), toPlain(actual.missingItems()), "missingItems");
        assertEquals(toPlain(reference.emittedItems()), toPlain(actual.emittedItems()), "emittedItems");
        assertEquals(reference.patternTimes(), actual.patternTimes(), "patternTimes");
    }

    public static Map<AEKey, Long> toPlain(KeyCounter counter) {
        Map<AEKey, Long> map = new HashMap<>();
        for (var entry : counter) {
            map.put(entry.getKey(), entry.getLongValue());
        }
        return map;
    }

    /** AEItemKey carrying a dyed color component, as {@code resolveRequestedColor} reads it. */
    public static AEItemKey dyedKey(Item item, int color) {
        var stack = new net.minecraft.world.item.ItemStack(item);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color, false));
        return AEItemKey.of(stack);
    }

    /** Minimal crafting pattern: outputs {@code outputCount} of {@code output} per run. */
    public static final class FakePattern implements IPatternDetails, PatternDetailsColorAccessor {
        private final AEItemKey definition;
        private final List<GenericStack> outputs;
        private final IInput[] inputs;
        private final int color;

        private FakePattern(AEKey output, long outputCount, Map<? extends AEKey, Long> inputs) {
            this(AEItemKey.of(Items.PAPER), List.of(new GenericStack(output, outputCount)),
                    inputs.entrySet().stream()
                            .map(e -> (IInput) new FakeInput(e.getKey(), e.getValue()))
                            .toArray(IInput[]::new),
                    -1);
        }

        private FakePattern(AEItemKey definition, List<GenericStack> outputs, IInput[] inputs) {
            this(definition, outputs, inputs, -1);
        }

        private FakePattern(AEItemKey definition, List<GenericStack> outputs, IInput[] inputs, int color) {
            this.definition = definition;
            this.outputs = outputs;
            this.inputs = inputs;
            this.color = color;
        }

        public static FakePattern crafting(AEKey output, long outputCount, Map<? extends AEKey, Long> inputs) {
            return new FakePattern(output, outputCount, inputs);
        }

        public static FakePattern of(AEKey output, long outputCount, IInput... inputs) {
            return new FakePattern(AEItemKey.of(Items.PAPER),
                    List.of(new GenericStack(output, outputCount)), inputs);
        }

        /** 多输出样板：用于催化物（自输出）场景，输出同时包含产物与循环输入。 */
        public static FakePattern withOutputs(IInput[] inputs, GenericStack... outputs) {
            return new FakePattern(AEItemKey.of(Items.PAPER), List.of(outputs), inputs);
        }

        /** 带颜色样板（染色环场景）；definition 须逐样板区分以支撑 selectedPatternInputs 键控。 */
        public static FakePattern colored(
                int color,
                AEItemKey definition,
                List<GenericStack> outputs,
                IInput... inputs
        ) {
            return new FakePattern(definition, List.copyOf(outputs), inputs, color);
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }

        @Override
        public int chexsonsaeutils$getColor() {
            return color;
        }
    }

    public static final class FakeInput implements IPatternDetails.IInput {
        private final List<GenericStack> possibleInputs;
        private final long multiplier;
        private final Map<AEKey, AEKey> remainingKeys;
        @Nullable
        private final Predicate<AEKey> validity;

        public FakeInput(AEKey key, long multiplier) {
            this(key, multiplier, null);
        }

        public FakeInput(AEKey key, long multiplier, @Nullable AEKey remainingKey) {
            this(List.of(new GenericStack(key, 1)), multiplier,
                    remainingKey == null ? Map.of() : Map.of(key, remainingKey), null);
        }

        private FakeInput(List<GenericStack> possibleInputs, long multiplier,
                Map<AEKey, AEKey> remainingKeys, @Nullable Predicate<AEKey> validity) {
            this.possibleInputs = possibleInputs;
            this.multiplier = multiplier;
            this.remainingKeys = Map.copyOf(remainingKeys);
            this.validity = validity;
        }

        /** AE2 processing-pattern encoding: template amount {@code amount} with multiplier 1. */
        public static FakeInput stack(AEKey key, long amount) {
            return new FakeInput(List.of(new GenericStack(key, amount)), 1L, Map.of(), null);
        }

        /** Multi-candidate input; every candidate key is a valid template. */
        /** 多候选 + 回流键组合：多候选循环槽场景的夹具入口。 */
        public static FakeInput multiWithRemainder(long multiplier, AEKey remainingOfPrimary, AEKey... candidates) {
            Map<AEKey, AEKey> remainders = candidates.length == 0
                    ? Map.of()
                    : Map.of(candidates[0], remainingOfPrimary);
            return multiWithRemainders(multiplier, remainders, candidates);
        }

        public static FakeInput multiWithRemainders(
                long multiplier,
                Map<AEKey, AEKey> remainders,
                AEKey... candidates
        ) {
            var stacks = new ArrayList<GenericStack>(candidates.length);
            var accepted = new HashSet<AEKey>();
            for (AEKey candidate : candidates) {
                stacks.add(new GenericStack(candidate, 1));
                accepted.add(candidate);
            }
            return new FakeInput(stacks, multiplier, remainders, accepted::contains);
        }

        public static FakeInput multi(long multiplier, AEKey... candidates) {
            var stacks = new ArrayList<GenericStack>(candidates.length);
            for (var candidate : candidates) {
                stacks.add(new GenericStack(candidate, 1));
            }
            var accepted = List.of(candidates);
            return new FakeInput(stacks, multiplier, Map.of(), accepted::contains);
        }

        /** Single-candidate input whose isValid accepts every item variant of the template's item. */
        public static FakeInput anyVariant(AEItemKey template, long multiplier) {
            return new FakeInput(List.of(new GenericStack(template, 1)), multiplier, Map.of(),
                    key -> key instanceof AEItemKey itemKey && itemKey.getItem() == template.getItem());
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs.toArray(GenericStack[]::new);
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            if (validity != null) {
                return validity.test(input);
            }
            return input.equals(possibleInputs.get(0).what());
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainingKeys.get(template);
        }
    }

    public static final class FakeRequester implements ICraftingSimulationRequester {
        private final IGridNode node;

        public FakeRequester(IGridNode node) {
            this.node = node;
        }

        @Override
        public IActionSource getActionSource() {
            return IActionSource.empty();
        }

        @Override
        public IGridNode getGridNode() {
            return node;
        }
    }

    /** Minimal grid wiring a fake storage service and crafting service together. */
    public static final class FakeGrid implements IGrid {
        public final FakeStorage storage;
        public final FakeCraftingService crafting;
        public final FakeNode node;
        public final FakeRequester requester;

        private FakeGrid(Map<AEKey, Long> inventory, Map<AEKey, List<IPatternDetails>> patterns,
                Predicate<AEKey> emittable, Map<AEKey, List<AEKey>> fuzzyCraftables) {
            this.storage = new FakeStorage(inventory);
            this.crafting = new FakeCraftingService(patterns, emittable, fuzzyCraftables);
            this.node = new FakeNode(this);
            this.requester = new FakeRequester(node);
        }

        public static Builder builder() {
            return new Builder();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <C extends IGridService> C getService(Class<C> iface) {
            if (iface == IStorageService.class) {
                return (C) storage;
            }
            if (iface == ICraftingService.class) {
                return (C) crafting;
            }
            throw new UnsupportedOperationException("service not provided in test: " + iface);
        }

        @Override
        public <T extends appeng.api.networking.events.GridEvent> T postEvent(T ev) {
            return ev;
        }

        @Override
        public Iterable<Class<?>> getMachineClasses() {
            return List.of();
        }

        @Override
        public Iterable<IGridNode> getMachineNodes(Class<?> machineClass) {
            return List.of();
        }

        @Override
        public <T> Set<T> getMachines(Class<T> machineClass) {
            return Set.of();
        }

        @Override
        public <T> Set<T> getActiveMachines(Class<T> machineClass) {
            return Set.of();
        }

        @Override
        public Iterable<IGridNode> getNodes() {
            return List.of(node);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public IGridNode getPivot() {
            return node;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public void export(com.google.gson.stream.JsonWriter jsonWriter) {
        }

        public static final class Builder {
            private final Map<AEKey, Long> inventory = new HashMap<>();
            private final Map<AEKey, List<IPatternDetails>> patterns = new HashMap<>();
            private final java.util.Set<AEKey> emittable = new java.util.HashSet<>();
            private final Map<AEKey, List<AEKey>> fuzzyCraftables = new HashMap<>();

            public Builder storage(Map<AEKey, Long> inv) {
                inventory.putAll(inv);
                return this;
            }

            public Builder pattern(AEKey output, IPatternDetails... pats) {
                patterns.put(output, List.of(pats));
                return this;
            }

            public Builder emittable(AEKey... keys) {
                emittable.addAll(List.of(keys));
                return this;
            }

            /**
             * Registers the ordered fuzzy craftable substitutes the tree
             * planner's {@code findCraftedStack} consults for {@code candidate}
             * (mirrors {@code NetworkCraftingProviders.getFuzzyCraftable}).
             */
            public Builder fuzzyCraftable(AEKey candidate, AEKey... craftables) {
                fuzzyCraftables.put(candidate, List.of(craftables));
                return this;
            }

            public FakeGrid build() {
                return new FakeGrid(inventory, patterns, emittable::contains, fuzzyCraftables);
            }
        }
    }

    public static final class FakeStorage implements IStorageService {
        private final KeyCounter cached = new KeyCounter();

        public FakeStorage(Map<AEKey, Long> inventory) {
            inventory.forEach(cached::add);
        }

        @Override
        public MEStorage getInventory() {
            return null;
        }

        @Override
        public KeyCounter getCachedInventory() {
            return cached;
        }

        @Override
        public void addGlobalStorageProvider(IStorageProvider cc) {
        }

        @Override
        public void removeGlobalStorageProvider(IStorageProvider cc) {
        }

        @Override
        public void refreshNodeStorageProvider(IGridNode node) {
        }

        @Override
        public void refreshGlobalStorageProvider(IStorageProvider provider) {
        }

        @Override
        public void invalidateCache() {
        }
    }

    public static final class FakeCraftingService implements ICraftingService {
        private final Map<AEKey, List<IPatternDetails>> patterns;
        private final Predicate<AEKey> emittable;
        private final Map<AEKey, List<AEKey>> fuzzyCraftables;

        private FakeCraftingService(Map<AEKey, List<IPatternDetails>> patterns, Predicate<AEKey> emittable,
                Map<AEKey, List<AEKey>> fuzzyCraftables) {
            this.patterns = patterns;
            this.emittable = emittable;
            this.fuzzyCraftables = fuzzyCraftables;
        }

        @Override
        public java.util.Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
            return patterns.getOrDefault(whatToCraft, List.of());
        }

        @Override
        public void refreshNodeCraftingProvider(IGridNode node) {
        }

        @Override
        public void addGlobalCraftingProvider(ICraftingProvider cc) {
        }

        @Override
        public void removeGlobalCraftingProvider(ICraftingProvider cc) {
        }

        @Override
        public void refreshGlobalCraftingProvider(ICraftingProvider provider) {
        }

        @Override
        public AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
            var candidates = fuzzyCraftables.get(whatToCraft);
            if (candidates == null) {
                return null;
            }
            for (var candidate : candidates) {
                if (filter.matches(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        @Override
        public Future<ICraftingPlan> beginCraftingCalculation(Level level,
                ICraftingSimulationRequester simRequester, AEKey what, long amount,
                CalculationStrategy strategy) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public ICraftingSubmitResult submitJob(ICraftingPlan job, ICraftingRequester requestingMachine,
                ICraftingCPU target, boolean prioritizePower, IActionSource src) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public ImmutableSet<ICraftingCPU> getCpus() {
            return ImmutableSet.of();
        }

        @Override
        public boolean canEmitFor(AEKey someItem) {
            return emittable.test(someItem);
        }

        @Override
        public Set<AEKey> getCraftables(AEKeyFilter filter) {
            Set<AEKey> craftable = new HashSet<>();
            for (List<IPatternDetails> patternList : patterns.values()) {
                for (IPatternDetails pattern : patternList) {
                    for (GenericStack output : pattern.getOutputs()) {
                        if (filter.matches(output.what())) {
                            craftable.add(output.what());
                        }
                    }
                }
            }
            return Set.copyOf(craftable);
        }

        @Override
        public boolean isRequesting(AEKey what) {
            return false;
        }

        @Override
        public long getRequestedAmount(AEKey what) {
            return 0;
        }

        @Override
        public boolean isRequestingAny() {
            return false;
        }
    }

    public static final class FakeNode implements IGridNode {
        private final IGrid grid;

        private FakeNode(IGrid grid) {
            this.grid = grid;
        }

        @Override
        public IGrid getGrid() {
            return grid;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends IGridNodeService> T getService(Class<T> serviceClass) {
            return (T) grid.getService((Class) serviceClass);
        }

        @Override
        public Object getOwner() {
            return null;
        }

        @Override
        public void beginVisit(IGridVisitor visitor) {
        }

        @Override
        public ServerLevel getLevel() {
            return null;
        }

        @Override
        public Set<Direction> getConnectedSides() {
            return Set.of();
        }

        @Override
        public Map<Direction, IGridConnection> getInWorldConnections() {
            return Map.of();
        }

        @Override
        public List<IGridConnection> getConnections() {
            return List.of();
        }

        @Override
        public boolean hasGridBooted() {
            return true;
        }

        @Override
        public boolean isPowered() {
            return true;
        }

        @Override
        public boolean meetsChannelRequirements() {
            return true;
        }

        @Override
        public boolean hasFlag(GridFlags flag) {
            return false;
        }

        @Override
        public int getOwningPlayerId() {
            return -1;
        }

        @Override
        public UUID getOwningPlayerProfileId() {
            return null;
        }

        @Override
        public double getIdlePowerUsage() {
            return 0;
        }

        @Override
        public AEItemKey getVisualRepresentation() {
            return null;
        }

        @Override
        public AEColor getGridColor() {
            return AEColor.TRANSPARENT;
        }

        @Override
        public void fillCrashReportCategory(CrashReportCategory category) {
        }

        @Override
        public int getMaxChannels() {
            return 0;
        }

        @Override
        public int getUsedChannels() {
            return 0;
        }
    }
}
