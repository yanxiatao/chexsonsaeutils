package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import git.chexson.chexsonsaeutils.crafting.color.PatternDetailsColorAccessor;
import git.chexson.chexsonsaeutils.crafting.graphplan.replay.CapturedPlanReplay;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回放实机捕获的规划夹具（{@code GraphPlanCapture} 写出的 JSON）。
 *
 * <p>键按「模糊类下标 + 类内变体序」还原：类下标映射到注册表里一个独立物品，
 * 变体序 &gt; 0 的挂一个 {@code CUSTOM_DATA} 组件——同类不同变体因此共享
 * {@code primaryKey}，{@code classVariants} 的规模与实机一致（这是 modpack 里
 * {@code cacheFuzzy} / {@code extractTemplates} 成本的主要来源）。
 *
 * <p>物品身份本身不参与求解，只用来提供互异的键与稳定的模糊类划分。
 */
public final class CapturedPlanFixture {

    private CapturedPlanFixture() {
    }

    public record Loaded(ICraftingService craftingService, KeyCounter stock, AEKey target, long amount,
            int requestedColor, int patternCount, int keyCount, long stockDropped,
            boolean authoritativePerformance, CapturedPlanReplay.Loaded runtimeReplay) {
        public FakeGrid replayGrid() {
            var builder = FakeGrid.builder().storage(toStockMap(stock));
            Set<AEKey> seen = new HashSet<>();
            var queue = new java.util.ArrayDeque<AEKey>();
            queue.add(target);
            while (!queue.isEmpty()) {
                AEKey key = queue.removeFirst();
                if (!seen.add(key)) {
                    continue;
                }
                List<IPatternDetails> producers = List.copyOf(craftingService.getCraftingFor(key));
                if (!producers.isEmpty()) {
                    builder.pattern(key, producers.toArray(IPatternDetails[]::new));
                }
                if (craftingService.canEmitFor(key)) {
                    builder.emittable(key);
                }
                for (IPatternDetails producer : producers) {
                    for (GenericStack output : producer.getOutputs()) {
                        queue.addLast(output.what());
                    }
                    for (IPatternDetails.IInput input : producer.getInputs()) {
                        for (GenericStack candidate : input.getPossibleInputs()) {
                            queue.addLast(candidate.what());
                            AEKey fuzzy = craftingService.getFuzzyCraftable(candidate.what(), ignored -> true);
                            if (fuzzy != null) {
                                builder.fuzzyCraftable(candidate.what(), fuzzy);
                                queue.addLast(fuzzy);
                            }
                            AEKey remainder = input.getRemainingKey(candidate.what());
                            if (remainder != null) {
                                queue.addLast(remainder);
                            }
                        }
                    }
                }
            }
            return builder.build();
        }

        private static Map<AEKey, Long> toStockMap(KeyCounter stock) {
            Map<AEKey, Long> result = new HashMap<>();
            for (var entry : stock) {
                result.put(entry.getKey(), entry.getLongValue());
            }
            return result;
        }
    }

    public static Loaded load(Path file) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        int schemaVersion = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
        if (schemaVersion == 2) {
            var replay = CapturedPlanReplay.load(file,
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            return new Loaded(replay.craftingService(), replay.stock(), replay.target(), replay.amount(),
                    replay.requestedColor(), replay.patternCount(), replay.keyCount(), replay.stockDropped(),
                    replay.authoritativePerformance(), replay);
        }
        JsonArray keySpecs = root.getAsJsonArray("keys");
        int classCount = 0;
        for (var element : keySpecs) {
            classCount = Math.max(classCount, element.getAsJsonArray().get(0).getAsInt() + 1);
        }
        List<Item> items = distinctItems(classCount);
        List<AEItemKey> keys = new ArrayList<>(keySpecs.size());
        for (var element : keySpecs) {
            JsonArray spec = element.getAsJsonArray();
            keys.add(synthesizeKey(items.get(spec.get(0).getAsInt()), spec.get(1).getAsInt()));
        }

        var stock = new KeyCounter();
        for (var element : root.getAsJsonArray("stock")) {
            JsonArray entry = element.getAsJsonArray();
            stock.add(keys.get(entry.get(0).getAsInt()), entry.get(1).getAsLong());
        }

        var emittable = new HashSet<AEKey>();
        for (var element : root.getAsJsonArray("emittable")) {
            emittable.add(keys.get(element.getAsInt()));
        }

        JsonArray patternSpecs = root.getAsJsonArray("patterns");
        var patterns = new ArrayList<IPatternDetails>(patternSpecs.size());
        for (int index = 0; index < patternSpecs.size(); index++) {
            patterns.add(buildPattern(patternSpecs.get(index).getAsJsonObject(), keys, index));
        }

        var craftingFor = new HashMap<AEKey, List<IPatternDetails>>();
        for (var element : root.getAsJsonArray("craftingFor")) {
            JsonArray entry = element.getAsJsonArray();
            var list = new ArrayList<IPatternDetails>();
            for (var patternIndex : entry.get(1).getAsJsonArray()) {
                list.add(patterns.get(patternIndex.getAsInt()));
            }
            craftingFor.put(keys.get(entry.get(0).getAsInt()), List.copyOf(list));
        }

        var fuzzyCraftable = new HashMap<AEKey, AEKey>();
        for (var element : root.getAsJsonArray("fuzzyCraftable")) {
            JsonArray entry = element.getAsJsonArray();
            fuzzyCraftable.put(keys.get(entry.get(0).getAsInt()), keys.get(entry.get(1).getAsInt()));
        }

        return new Loaded(
                new ReplayCraftingService(craftingFor, emittable, fuzzyCraftable),
                stock,
                keys.get(root.get("target").getAsInt()),
                root.get("amount").getAsLong(),
                root.get("requestedColor").getAsInt(),
                patterns.size(),
                keys.size(),
                root.has("stockDropped") ? root.get("stockDropped").getAsLong() : 0L,
                false,
                null);
    }

    private static List<Item> distinctItems(int count) {
        var items = new ArrayList<Item>(count);
        for (Item item : BuiltInRegistries.ITEM) {
            // 注册表首项是 minecraft:air，AEItemKey.of 对它返回 null
            if (AEItemKey.of(item) == null) {
                continue;
            }
            items.add(item);
            if (items.size() == count) {
                return items;
            }
        }
        throw new IllegalStateException(
                "capture needs " + count + " distinct fuzzy classes but only " + items.size()
                        + " items are registered in the test environment");
    }

    private static AEItemKey synthesizeKey(Item item, int variant) {
        if (variant == 0) {
            return AEItemKey.of(item);
        }
        var stack = new ItemStack(item);
        var tag = new CompoundTag();
        tag.putInt("chexsonsaeutilsVariant", variant);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    private static IPatternDetails buildPattern(JsonObject spec, List<AEItemKey> keys, int patternIndex) {
        var outputs = new ArrayList<GenericStack>();
        for (var element : spec.getAsJsonArray("outputs")) {
            JsonArray entry = element.getAsJsonArray();
            outputs.add(new GenericStack(keys.get(entry.get(0).getAsInt()), entry.get(1).getAsLong()));
        }
        JsonArray inputSpecs = spec.getAsJsonArray("inputs");
        var inputs = new IPatternDetails.IInput[inputSpecs.size()];
        for (int index = 0; index < inputSpecs.size(); index++) {
            inputs[index] = buildInput(inputSpecs.get(index).getAsJsonObject(), keys);
        }
        return new ReplayPattern(definitionKey(patternIndex), List.copyOf(outputs), inputs,
                spec.get("color").getAsInt());
    }

    private static IPatternDetails.IInput buildInput(JsonObject spec, List<AEItemKey> keys) {
        var candidates = new ArrayList<GenericStack>();
        for (var element : spec.getAsJsonArray("candidates")) {
            JsonArray entry = element.getAsJsonArray();
            candidates.add(new GenericStack(keys.get(entry.get(0).getAsInt()), entry.get(1).getAsLong()));
        }
        var accepts = new HashSet<AEKey>();
        for (var element : spec.getAsJsonArray("accepts")) {
            accepts.add(keys.get(element.getAsInt()));
        }
        if (accepts.isEmpty()) {
            for (GenericStack candidate : candidates) {
                accepts.add(candidate.what());
            }
        }
        int remainder = spec.get("remainder").getAsInt();
        return new ReplayInput(candidates.toArray(GenericStack[]::new), spec.get("multiplier").getAsLong(),
                remainder < 0 ? null : keys.get(remainder), accepts);
    }

    /** 样板 definition 必须逐样板互异（crafts 账本与 patternsBySource 都按它键控）。 */
    private static AEItemKey definitionKey(int patternIndex) {
        var stack = new ItemStack(net.minecraft.world.item.Items.PAPER);
        var tag = new CompoundTag();
        tag.putInt("chexsonsaeutilsPattern", patternIndex);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    /**
     * 身份语义而非 record 深比较：AE2 的真实样板实现（AEProcessingPattern 等）
     * 都没有覆盖 equals/hashCode，用的就是对象身份。夹具若用 record 深哈希，
     * 每次查 crafts 账本都要走完输出与输入结构，读数会凭空多出一大块。
     */
    private static final class ReplayPattern implements IPatternDetails, PatternDetailsColorAccessor {
        private final AEItemKey definition;
        private final List<GenericStack> outputs;
        private final IInput[] inputs;
        private final int color;

        private ReplayPattern(AEItemKey definition, List<GenericStack> outputs, IInput[] inputs, int color) {
            this.definition = definition;
            this.outputs = outputs;
            this.inputs = inputs;
            this.color = color;
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

    private record ReplayInput(GenericStack[] candidates, long multiplier, @Nullable AEKey remainder,
            Set<AEKey> accepts) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return accepts.contains(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainder;
        }
    }

    private record ReplayCraftingService(Map<AEKey, List<IPatternDetails>> craftingFor, Set<AEKey> emittable,
            Map<AEKey, AEKey> fuzzyCraftable) implements ICraftingService {

        @Override
        public Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
            return craftingFor.getOrDefault(whatToCraft, List.of());
        }

        @Override
        public boolean canEmitFor(AEKey someItem) {
            return emittable.contains(someItem);
        }

        @Override
        public AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
            AEKey candidate = fuzzyCraftable.get(whatToCraft);
            return candidate != null && filter.matches(candidate) ? candidate : null;
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
        public java.util.concurrent.Future<ICraftingPlan> beginCraftingCalculation(Level level,
                ICraftingSimulationRequester simRequester, AEKey what, long amount, CalculationStrategy strategy) {
            throw new UnsupportedOperationException("not used in replay");
        }

        @Override
        public ICraftingSubmitResult submitJob(ICraftingPlan job, ICraftingRequester requestingMachine,
                ICraftingCPU target, boolean prioritizePower, IActionSource src) {
            throw new UnsupportedOperationException("not used in replay");
        }

        @Override
        public ImmutableSet<ICraftingCPU> getCpus() {
            return ImmutableSet.of();
        }

        @Override
        public Set<AEKey> getCraftables(AEKeyFilter filter) {
            return Set.of();
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
}
