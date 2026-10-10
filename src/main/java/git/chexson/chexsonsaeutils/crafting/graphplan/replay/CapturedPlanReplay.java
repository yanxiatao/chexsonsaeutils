package git.chexson.chexsonsaeutils.crafting.graphplan.replay;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import git.chexson.chexsonsaeutils.crafting.color.PatternDetailsColorAccessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/** Registry-aware v2 capture loader shared by JUnit and the NeoForge runtime replay. */
public final class CapturedPlanReplay {

    public record Loaded(
            ICraftingService craftingService,
            KeyCounter stock,
            AEKey target,
            long amount,
            int requestedColor,
            int patternCount,
            int keyCount,
            long stockDropped,
            boolean authoritativePerformance,
            VirtualGrid grid,
            HolderLookup.Provider registries
    ) {
        public MEStorage storage() {
            return this.grid.getStorageService().getInventory();
        }
    }

    private CapturedPlanReplay() {
    }

    public static Loaded load(Path file, HolderLookup.Provider registries) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        int schemaVersion = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
        if (schemaVersion != 2) {
            throw new IOException("capture schema " + schemaVersion + " is legacy and cannot be authoritative");
        }

        JsonArray keySpecs = requireArray(root, "keys");
        List<AEKey> keys = new ArrayList<>(keySpecs.size());
        for (var element : keySpecs) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IOException("capture key must be an SNBT string: " + element);
            }
            keys.add(AEKey.CODEC.parse(
                    registries.createSerializationContext(NbtOps.INSTANCE),
                    parseSnbtTag(element.getAsString())).getOrThrow());
        }

        KeyCounter stock = new KeyCounter();
        for (var element : requireArray(root, "stock")) {
            JsonArray entry = element.getAsJsonArray();
            stock.add(key(keys, entry.get(0).getAsInt()), entry.get(1).getAsLong());
        }

        Set<AEKey> craftables = new java.util.LinkedHashSet<>();
        for (var element : requireArray(root, "craftables")) {
            craftables.add(key(keys, element.getAsInt()));
        }

        Set<AEKey> emittable = new HashSet<>();
        for (var element : requireArray(root, "emittable")) {
            emittable.add(key(keys, element.getAsInt()));
        }

        JsonArray patternSpecs = requireArray(root, "patterns");
        List<IPatternDetails> patterns = new ArrayList<>(patternSpecs.size());
        for (var element : patternSpecs) {
            patterns.add(buildPattern(element.getAsJsonObject(), keys));
        }

        Map<AEKey, List<IPatternDetails>> craftingFor = new LinkedHashMap<>();
        for (var element : requireArray(root, "craftingFor")) {
            JsonArray entry = element.getAsJsonArray();
            List<IPatternDetails> producers = new ArrayList<>();
            for (var patternIndex : entry.get(1).getAsJsonArray()) {
                producers.add(patterns.get(patternIndex.getAsInt()));
            }
            craftingFor.put(key(keys, entry.get(0).getAsInt()), List.copyOf(producers));
        }

        Map<AEKey, List<AEKey>> fuzzyCraftable = new LinkedHashMap<>();
        for (var element : requireArray(root, "fuzzyCraftable")) {
            JsonArray entry = element.getAsJsonArray();
            fuzzyCraftable.computeIfAbsent(key(keys, entry.get(0).getAsInt()), ignored -> new ArrayList<>())
                    .add(key(keys, entry.get(1).getAsInt()));
        }
        Map<AEKey, List<AEKey>> immutableFuzzy = new LinkedHashMap<>();
        fuzzyCraftable.forEach((key, values) -> immutableFuzzy.put(key, List.copyOf(values)));

        ReplayCraftingService service = new ReplayCraftingService(
                craftingFor, craftables, emittable, immutableFuzzy);
        ReplayStorage storage = new ReplayStorage(stock);
        VirtualGrid grid = new VirtualGrid(service, storage);
        JsonObject integrity = root.has("integrity") && root.get("integrity").isJsonObject()
                ? root.getAsJsonObject("integrity") : new JsonObject();
        boolean complete = integrity.has("complete") && integrity.get("complete").getAsBoolean();
        boolean serviceClosureCaptured = integrity.has("serviceClosureCaptured")
                && integrity.get("serviceClosureCaptured").getAsBoolean();
        boolean craftablesCaptured = root.has("craftables") && root.get("craftables").isJsonArray();
        boolean stockComplete = integrity.has("stockComplete")
                && integrity.get("stockComplete").getAsBoolean();
        boolean validityCaptured = integrity.has("validityCaptured")
                && integrity.get("validityCaptured").getAsBoolean();
        boolean noIntegrityErrors = integrity.has("errors")
                && integrity.get("errors").isJsonArray()
                && integrity.getAsJsonArray("errors").isEmpty();
        boolean authoritative = root.has("authoritativePerformance")
                && root.get("authoritativePerformance").getAsBoolean()
                && complete
                && serviceClosureCaptured
                && craftablesCaptured
                && stockComplete
                && validityCaptured
                && noIntegrityErrors
                && (!root.has("stockDropped") || root.get("stockDropped").getAsLong() == 0L);
        return new Loaded(
                service,
                copy(stock),
                key(keys, root.get("target").getAsInt()),
                root.get("amount").getAsLong(),
                root.has("requestedColor") ? root.get("requestedColor").getAsInt() : -1,
                patterns.size(),
                keys.size(),
                root.has("stockDropped") ? root.get("stockDropped").getAsLong() : 0L,
                authoritative,
                grid,
                registries);
    }

    private static JsonArray requireArray(JsonObject root, String name) throws IOException {
        if (!root.has(name) || !root.get(name).isJsonArray()) {
            throw new IOException("capture is missing array '" + name + "'");
        }
        return root.getAsJsonArray(name);
    }

    private static AEKey key(List<AEKey> keys, int index) throws IOException {
        if (index < 0 || index >= keys.size()) {
            throw new IOException("capture key index out of bounds: " + index);
        }
        return keys.get(index);
    }

    private static net.minecraft.nbt.CompoundTag parseSnbtTag(String snbt) throws IOException {
        try {
            return TagParser.parseTag(snbt);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IOException("capture key is not valid SNBT: " + snbt, failure);
        }
    }

    private static IPatternDetails buildPattern(JsonObject spec, List<AEKey> keys) throws IOException {
        AEKey definitionKey = key(keys, spec.get("definition").getAsInt());
        if (!(definitionKey instanceof AEItemKey definition)) {
            throw new IOException("pattern definition is not an item key: " + definitionKey);
        }
        List<GenericStack> outputs = new ArrayList<>();
        for (var element : spec.getAsJsonArray("outputs")) {
            JsonArray entry = element.getAsJsonArray();
            outputs.add(new GenericStack(key(keys, entry.get(0).getAsInt()), entry.get(1).getAsLong()));
        }
        JsonArray inputSpecs = spec.getAsJsonArray("inputs");
        IPatternDetails.IInput[] inputs = new IPatternDetails.IInput[inputSpecs.size()];
        for (int index = 0; index < inputSpecs.size(); index++) {
            inputs[index] = buildInput(inputSpecs.get(index).getAsJsonObject(), keys);
        }
        return new ReplayPattern(definition, List.copyOf(outputs), inputs,
                spec.has("color") ? spec.get("color").getAsInt() : -1);
    }

    private static IPatternDetails.IInput buildInput(JsonObject spec, List<AEKey> keys) throws IOException {
        List<GenericStack> candidates = new ArrayList<>();
        for (var element : spec.getAsJsonArray("candidates")) {
            JsonArray entry = element.getAsJsonArray();
            candidates.add(new GenericStack(key(keys, entry.get(0).getAsInt()), entry.get(1).getAsLong()));
        }
        boolean acceptsKnown = spec.has("acceptsKnown") && spec.get("acceptsKnown").getAsBoolean();
        Set<AEKey> accepts = new HashSet<>();
        for (var element : spec.getAsJsonArray("accepts")) {
            accepts.add(key(keys, element.getAsInt()));
        }
        Map<AEKey, AEKey> remainders = new HashMap<>();
        JsonArray remainderSpecs = spec.getAsJsonArray("remainders");
        for (int index = 0; index < candidates.size(); index++) {
            int remainderIndex = remainderSpecs.get(index).getAsInt();
            if (remainderIndex >= 0) {
                remainders.put(candidates.get(index).what(), key(keys, remainderIndex));
            }
        }
        return new ReplayInput(candidates.toArray(GenericStack[]::new), spec.get("multiplier").getAsLong(),
                acceptsKnown, Set.copyOf(accepts), Map.copyOf(remainders));
    }

    private static KeyCounter copy(KeyCounter source) {
        KeyCounter result = new KeyCounter();
        result.addAll(source);
        return result;
    }

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

        @Override public AEItemKey getDefinition() { return this.definition; }
        @Override public IInput[] getInputs() { return this.inputs.clone(); }
        @Override public List<GenericStack> getOutputs() { return this.outputs; }
        @Override public int chexsonsaeutils$getColor() { return this.color; }
    }

    private record ReplayInput(GenericStack[] candidates, long multiplier, boolean acceptsKnown,
            Set<AEKey> accepts, Map<AEKey, AEKey> remainders) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() { return this.candidates.clone(); }
        @Override public long getMultiplier() { return this.multiplier; }
        @Override public boolean isValid(AEKey input, Level level) {
            return this.acceptsKnown ? this.accepts.contains(input)
                    : java.util.Arrays.stream(this.candidates).anyMatch(candidate -> candidate.what().equals(input));
        }
        @Override public AEKey getRemainingKey(AEKey template) { return this.remainders.get(template); }
    }

    private static final class ReplayCraftingService implements ICraftingService {
        private final Map<AEKey, List<IPatternDetails>> craftingFor;
        private final Set<AEKey> craftables;
        private final Set<AEKey> emittable;
        private final Map<AEKey, List<AEKey>> fuzzyCraftable;

        private ReplayCraftingService(Map<AEKey, List<IPatternDetails>> craftingFor,
                Set<AEKey> craftables, Set<AEKey> emittable,
                Map<AEKey, List<AEKey>> fuzzyCraftable) {
            this.craftingFor = Map.copyOf(craftingFor);
            this.craftables = Set.copyOf(craftables);
            this.emittable = Set.copyOf(emittable);
            this.fuzzyCraftable = Map.copyOf(fuzzyCraftable);
        }

        @Override public Collection<IPatternDetails> getCraftingFor(AEKey key) {
            return this.craftingFor.getOrDefault(key, List.of());
        }
        @Override public boolean canEmitFor(AEKey key) { return this.emittable.contains(key); }
        @Override public AEKey getFuzzyCraftable(AEKey key, AEKeyFilter filter) {
            for (AEKey candidate : this.fuzzyCraftable.getOrDefault(key, List.of())) {
                if (filter.matches(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        @Override public Set<AEKey> getCraftables(AEKeyFilter filter) {
            Set<AEKey> result = new java.util.LinkedHashSet<>();
            for (AEKey key : this.craftables) {
                if (filter.matches(key)) {
                    result.add(key);
                }
            }
            return Set.copyOf(result);
        }
        @Override public void refreshNodeCraftingProvider(IGridNode node) { }
        @Override public void addGlobalCraftingProvider(ICraftingProvider provider) { }
        @Override public void removeGlobalCraftingProvider(ICraftingProvider provider) { }
        @Override public void refreshGlobalCraftingProvider(ICraftingProvider provider) { }
        @Override public Future<ICraftingPlan> beginCraftingCalculation(Level level,
                ICraftingSimulationRequester requester, AEKey key, long amount, CalculationStrategy strategy) {
            throw new UnsupportedOperationException("runtime harness owns calculation submission");
        }
        @Override public ICraftingSubmitResult submitJob(ICraftingPlan plan, ICraftingRequester requester,
                ICraftingCPU target, boolean prioritizePower, IActionSource source) {
            throw new UnsupportedOperationException("runtime replay never submits crafting jobs");
        }
        @Override public ImmutableSet<ICraftingCPU> getCpus() { return ImmutableSet.of(); }
        @Override public boolean isRequesting(AEKey key) { return false; }
        @Override public long getRequestedAmount(AEKey key) { return 0L; }
        @Override public boolean isRequestingAny() { return false; }
    }

    public static final class VirtualGrid implements IGrid {
        private final ICraftingService crafting;
        private final IStorageService storage;
        private final appeng.api.networking.energy.IEnergyService energy = new ReplayEnergyService();

        private VirtualGrid(ICraftingService crafting, ReplayStorage storage) {
            this.crafting = crafting;
            this.storage = new ReplayStorageService(storage);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <C extends appeng.api.networking.IGridService> C getService(Class<C> serviceClass) {
            if (serviceClass == ICraftingService.class) {
                return (C) this.crafting;
            }
            if (serviceClass == IStorageService.class) {
                return (C) this.storage;
            }
            if (serviceClass == appeng.api.networking.energy.IEnergyService.class) {
                return (C) this.energy;
            }
            throw new IllegalArgumentException("unsupported replay grid service: " + serviceClass.getName());
        }

        @Override public appeng.api.networking.energy.IEnergyService getEnergyService() { return this.energy; }
        @Override public IStorageService getStorageService() { return this.storage; }
        @Override public ICraftingService getCraftingService() { return this.crafting; }
        @Override public <T extends appeng.api.networking.events.GridEvent> T postEvent(T event) { return event; }
        @Override public Iterable<Class<?>> getMachineClasses() { return List.of(); }
        @Override public Iterable<IGridNode> getMachineNodes(Class<?> machineClass) { return List.of(); }
        @Override public <T> Set<T> getMachines(Class<T> machineClass) { return Set.of(); }
        @Override public <T> Set<T> getActiveMachines(Class<T> machineClass) { return Set.of(); }
        @Override public Iterable<IGridNode> getNodes() { return List.of(); }
        @Override public boolean isEmpty() { return true; }
        @Override public IGridNode getPivot() { return null; }
        @Override public int size() { return 0; }
        @Override public void export(com.google.gson.stream.JsonWriter writer) { }
    }

    private static final class ReplayEnergyService implements appeng.api.networking.energy.IEnergyService {
        @Override public double getIdlePowerUsage() { return 0.0; }
        @Override public double getChannelPowerUsage() { return 0.0; }
        @Override public double getAvgPowerUsage() { return 0.0; }
        @Override public double getAvgPowerInjection() { return 0.0; }
        @Override public boolean isNetworkPowered() { return true; }
        @Override public double injectPower(double amount, Actionable mode) { return 0.0; }
        @Override public double getStoredPower() { return Double.MAX_VALUE; }
        @Override public double getMaxStoredPower() { return Double.MAX_VALUE; }
        @Override public double getEnergyDemand(double maxRequired) { return 0.0; }
        @Override public double extractAEPower(double amount, Actionable mode,
                appeng.api.config.PowerMultiplier multiplier) {
            return amount;
        }
    }

    private static final class ReplayStorage implements MEStorage {
        private final KeyCounter stock;

        private ReplayStorage(KeyCounter stock) {
            this.stock = copy(stock);
        }

        @Override public long insert(AEKey what, long amount, Actionable mode, IActionSource source) { return 0L; }
        @Override public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            long available = this.stock.get(what);
            long extracted = Math.min(amount, available);
            if (mode == Actionable.MODULATE && extracted > 0L) {
                this.stock.remove(what, extracted);
            }
            return extracted;
        }
        @Override public void getAvailableStacks(KeyCounter out) { out.addAll(this.stock); }
        @Override public net.minecraft.network.chat.Component getDescription() {
            return net.minecraft.network.chat.Component.literal("Chexson's AE Utils replay storage");
        }
    }

    private record ReplayStorageService(ReplayStorage inventory) implements IStorageService {
        @Override public MEStorage getInventory() { return this.inventory; }
        @Override public KeyCounter getCachedInventory() { return copy(this.inventory.stock); }
        @Override public void invalidateCache() { }
        @Override public void addGlobalStorageProvider(IStorageProvider provider) { }
        @Override public void removeGlobalStorageProvider(IStorageProvider provider) { }
        @Override public void refreshGlobalStorageProvider(IStorageProvider provider) { }
        @Override public void refreshNodeStorageProvider(IGridNode node) { }
    }
}
