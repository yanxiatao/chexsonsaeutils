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
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.ProviderPatternOrder;
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
    public static final class FakePattern implements IPatternDetails, PatternDetailsColorAccessor,
            PrioritizedPattern {
        private final AEItemKey definition;
        private final List<GenericStack> outputs;
        private final IInput[] inputs;
        private final int color;
        /**
         * 模拟提供者的机器优先级（{@code ICraftingProvider#getPatternPriority()}）：默认 0＝并列，
         * 并列项的次序由 {@code ProviderPatternOrder} 的稳定键定死（台账 #140）。要表达
         * "清单序＝提供者有意排的序"，就把它声明成递减优先级——AE2 真网格上那份序正是这么来的。
         */
        private int priority;

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

        /**
         * 声明这张样板所在提供者的机器优先级（在喂进网格<b>之前</b>调用）。
         * 只在"清单序对用例有意义"的夹具上用：并列（默认 0）时那份序不再由清单决定。
         */
        public FakePattern withPriority(int priority) {
            this.priority = priority;
            return this;
        }

        @Override
        public int priority() {
            return this.priority;
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

        /**
         * 声明顺位 + <b>逐候选各自分自己的每发量</b> + 只收指名那一枚的 {@code isValid}。
         *
         * <p>台账 #201 探针图那一格的取料点形状：首位是一枚无生产者、{@code isValid} 又不收的
         * 幽灵键，真付得到的那一枚排在它后面且每发件数不同——既有 {@link #multi} 把所有候选
         * 钉成每发 1 枚，正是那一型在旧夹具上量不到的原因。
         */
        public static FakeInput ordered(long multiplier, List<GenericStack> candidates,
                java.util.Collection<AEKey> accepts) {
            var accepted = Set.copyOf(accepts);
            return new FakeInput(List.copyOf(candidates), multiplier, Map.of(), accepted::contains);
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
                java.util.Set<AEKey> emittable, MEStorage liveStorage) {
            this.storage = new FakeStorage(inventory, liveStorage);
            this.crafting = new FakeCraftingService(patterns, emittable);
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
            private MEStorage liveStorage;
            /** 样板登记簿：键＝样板的 primary output（＝AE2 的 {@code craftableItems} 键），值为该键的清单。 */
            private final Map<AEKey, List<IPatternDetails>> patterns = new java.util.LinkedHashMap<>();
            private final java.util.Set<AEKey> emittable = new java.util.HashSet<>();

            public Builder storage(Map<AEKey, Long> inv) {
                inventory.putAll(inv);
                return this;
            }

            /** 为真实库存存取场景显式注入接口，替代动态反射代理。 */
            public Builder liveStorage(MEStorage storage) {
                liveStorage = java.util.Objects.requireNonNull(storage);
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

            public FakeGrid build() {
                return new FakeGrid(inventory, patterns, emittable, liveStorage);
            }
        }
    }

    public static final class FakeStorage implements IStorageService {
        private final KeyCounter cached = new KeyCounter();
        private final MEStorage liveStorage;

        public FakeStorage(Map<AEKey, Long> inventory) {
            this(inventory, null);
        }

        private FakeStorage(Map<AEKey, Long> inventory, MEStorage liveStorage) {
            this.liveStorage = liveStorage;
            inventory.forEach(cached::add);
        }

        @Override
        public MEStorage getInventory() {
            return liveStorage;
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

    /**
     * 测试侧给样板打"机器优先级"标记的接口。
     *
     * <p>真网格上这份优先级挂在<b>提供者</b>（{@code ICraftingProvider#getPatternPriority()}）而不是样板上，
     * 替身没有提供者对象可问，所以由样板自己带一个读数；不带标记的按 AE2 的默认优先级 0
     * （＝与所有默认优先级并列，交给稳定键定序）。
     */
    public interface PrioritizedPattern {
        int priority();
    }

    public static final class FakeCraftingService implements ICraftingService,
            ProviderPatternOrder.PatternPrioritySource {
        private final Map<AEKey, List<IPatternDetails>> patterns;
        private final java.util.Set<AEKey> emittable;

        private FakeCraftingService(Map<AEKey, List<IPatternDetails>> patterns,
                java.util.Set<AEKey> emittable) {
            this.patterns = patterns;
            this.emittable = emittable;
        }

        /** 并列项定序（台账 #140）在测试里也要走真收集点：这份清单的优先级来源就是上面那个标记。 */
        @Override
        public int patternPriority(IPatternDetails pattern) {
            return pattern instanceof PrioritizedPattern prioritized ? prioritized.priority() : 0;
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

        /**
         * 与 AE2 的 {@code NetworkCraftingProviders#getFuzzyCraftable}（:138-139）同一条宇宙：
         * 在<b>同主键</b>（{@code AEKey#getPrimaryKey}，item 就是那个 {@code Item} 实例）的那些
         * <b>样板产出键</b>（＝{@code craftableItemsList} 的成员，不含纯可发射的键）里，
         * 交回第一个过 filter 的键。
         *
         * <p>AE2 这一趟走的是 {@code KeyCounter} 的哈希遍历序（不可损伤的 item 是
         * {@code AEKey2LongMap.OpenHashMap}，可损伤的是 {@code FuzzySearch.KeyComparator}：
         * <b>受损的排在前面</b>，损伤档并列才按 {@code hashCode}），本替身按<b>登记序</b>交回：
         * 这一份序是"别人家的序"，
         * 用例若要钉住它就必须把成员逐个登记清楚——规划侧不再吃它（台账 #143，
         * {@code ProviderPatternOrder.FuzzyClassView} 自己按稳定键排）。
         */
        @Override
        public AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
            var primaryKey = whatToCraft.getPrimaryKey();
            for (var craftable : patterns.keySet()) {
                if (craftable.getPrimaryKey() == primaryKey && filter.matches(craftable)) {
                    return craftable;
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
            return emittable.contains(someItem);
        }

        /**
         * 与 AE2 的 {@code NetworkCraftingProviders#getCraftables}（:102-119）同形：
         * <b>可合成</b>（样板的登记键＝primary output）∪ <b>可发射</b>。
         * 交回的集合本身是哈希序——规划侧读它的那一步按稳定键重排（台账 #143）。
         */
        @Override
        public Set<AEKey> getCraftables(AEKeyFilter filter) {
            Set<AEKey> craftables = new java.util.LinkedHashSet<>();
            for (AEKey output : patterns.keySet()) {
                if (filter.matches(output)) {
                    craftables.add(output);
                }
            }
            for (AEKey emitable : emittable) {
                if (filter.matches(emitable)) {
                    craftables.add(emitable);
                }
            }
            return Set.copyOf(craftables);
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
        /**
         * 节点宿主；默认 {@code null} 表示「无宿主」，与既有全部规划用例的口径一致。
         * 只给需要按宿主身份枚举网格的读侧用例（如真值供给快照）覆写。
         */
        public Object owner;

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
            return this.owner;
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
