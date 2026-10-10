package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import appeng.api.crafting.IPatternDetails;
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
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import com.google.common.collect.ImmutableSet;
import com.google.gson.stream.JsonWriter;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.color.PatternColorHelper;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.ProviderPatternOrder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures the immutable service view used by one real planning request. */
public final class GraphPlanCapture {

    public static final int SCHEMA_VERSION = 2;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "chexsonsaeutils-plan-capture");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Nullable
    private final Level level;
    private final AEKey target;
    private final KeyCounter stock;
    private final RecordingService service;
    private final Map<AEKey, Integer> keyIndex = new LinkedHashMap<>();
    private final List<AEKey> keys = new ArrayList<>();
    private final Map<IPatternDetails, Integer> patternIndex = new IdentityHashMap<>();
    private final List<IPatternDetails> patterns = new ArrayList<>();
    private final Set<AEKey> craftables = new LinkedHashSet<>();
    private final Map<AEKey, List<Integer>> craftingFor = new LinkedHashMap<>();
    private final Map<AEKey, List<AEKey>> fuzzyCraftable = new LinkedHashMap<>();
    private final Set<AEKey> emittable = new LinkedHashSet<>();
    private final List<Query> queries = new ArrayList<>();
    private final List<String> integrityErrors = new ArrayList<>();
    private final AtomicBoolean written = new AtomicBoolean();
    private boolean serviceClosureCaptured;

    private GraphPlanCapture(ICraftingService delegate, KeyCounter stock, AEKey target, @Nullable Level level) {
        this.level = level;
        this.target = target;
        this.stock = copy(stock);
        this.service = new RecordingService(delegate);
    }

    public static boolean enabled() {
        return ChexsonsaeutilsCompatibilityConfig.graphPlanCaptureEnabled();
    }

    @Nullable
    public static GraphPlanCapture begin(ICraftingService delegate, KeyCounter stock, AEKey target,
            @Nullable Level level) {
        return enabled() ? forCapture(delegate, stock, target, level) : null;
    }

    public static GraphPlanCapture forCapture(ICraftingService delegate, KeyCounter stock, AEKey target,
            @Nullable Level level) {
        return new GraphPlanCapture(delegate, stock, target, level);
    }

    public ICraftingService service() {
        return this.service;
    }

    @Nullable
    public Path write(long requestedAmount) {
        return write(requestedAmount, Path.of("logs"));
    }

    @Nullable
    public Path write(long requestedAmount, Path dir) {
        try {
            Frozen frozen = freeze(requestedAmount);
            Path file = capturePath(dir, frozen.captureId());
            writeAtomically(file, frozen.json());
            return file;
        } catch (RuntimeException | IOException failure) {
            LOGGER.error("Failed to write graph plan capture", failure);
            return null;
        }
    }

    @Nullable
    public Path writeAsync(long requestedAmount) {
        if (!this.written.compareAndSet(false, true)) {
            return null;
        }
        long started = System.nanoTime();
        final Frozen frozen;
        try {
            frozen = freeze(requestedAmount);
        } catch (RuntimeException | IOException failure) {
            LOGGER.error("Failed to freeze graph plan capture", failure);
            return null;
        }
        Path file = capturePath(Path.of("logs"), frozen.captureId());
        try {
            WRITER.execute(() -> {
                try {
                    writeAtomically(file, frozen.json());
                    LOGGER.info("Graph plan capture written: target={}, amount={}, captureOverheadNs={}, file={}",
                            this.target, requestedAmount, System.nanoTime() - started, file.toAbsolutePath());
                } catch (IOException failure) {
                    LOGGER.error("Failed to write graph plan capture {}", file, failure);
                }
            });
            return file;
        } catch (RuntimeException rejected) {
            LOGGER.error("Graph plan capture queue is full; capture {} was not written", frozen.captureId(), rejected);
            return null;
        }
    }

    public static void awaitPendingWrites() {
        while (WRITER.getActiveCount() != 0 || !WRITER.getQueue().isEmpty()) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized Frozen freeze(long requestedAmount) throws IOException {
        indexOf(this.target);
        for (var entry : this.stock) {
            indexOf(entry.getKey());
        }
        captureServiceClosure();
        for (IPatternDetails pattern : List.copyOf(this.patterns)) {
            indexOf(pattern);
        }
        UUID captureId = UUID.randomUUID();
        StringWriter output = new StringWriter();
        try (JsonWriter json = new JsonWriter(output)) {
            json.setIndent("");
            writeBody(json, captureId, requestedAmount);
        }
        return new Frozen(captureId, output.toString());
    }

    private void writeBody(JsonWriter json, UUID captureId, long requestedAmount) throws IOException {
        HolderLookup.Provider registries = registries();
        boolean complete = this.level != null && this.serviceClosureCaptured && this.integrityErrors.isEmpty();
        json.beginObject();
        json.name("schemaVersion").value(SCHEMA_VERSION);
        json.name("authoritativePerformance").value(complete);
        json.name("captureId").value(captureId.toString());
        json.name("capturedAt").value(Instant.now().toString());
        json.name("target").value(indexOf(this.target));
        json.name("amount").value(requestedAmount);
        json.name("requestedColor").value(requestedColor());
        writeEnvironment(json);

        json.name("keys").beginArray();
        for (AEKey key : this.keys) {
            // SNBT（NbtOps）保存键：JsonOps 往返会把 custom_data 里的 int 降级成
            // byte，破坏组件键的无损还原；SNBT 的数值后缀保留 NBT 类型。
            net.minecraft.nbt.Tag encoded = AEKey.CODEC.encodeStart(
                    registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), key)
                    .getOrThrow();
            json.value(encoded.toString());
        }
        json.endArray();

        json.name("stock").beginArray();
        for (var entry : this.stock) {
            json.beginArray().value(indexOf(entry.getKey())).value(entry.getLongValue()).endArray();
        }
        json.endArray();
        json.name("stockDropped").value(0L);

        json.name("craftables").beginArray();
        for (AEKey key : this.craftables) {
            json.value(indexOf(key));
        }
        json.endArray();

        json.name("emittable").beginArray();
        for (AEKey key : this.emittable) {
            json.value(indexOf(key));
        }
        json.endArray();

        json.name("patterns").beginArray();
        for (IPatternDetails pattern : this.patterns) {
            writePattern(json, pattern);
        }
        json.endArray();

        json.name("craftingFor").beginArray();
        for (var entry : this.craftingFor.entrySet()) {
            json.beginArray().value(indexOf(entry.getKey())).beginArray();
            for (int pattern : entry.getValue()) {
                json.value(pattern);
            }
            json.endArray().endArray();
        }
        json.endArray();

        json.name("fuzzyCraftable").beginArray();
        for (var entry : this.fuzzyCraftable.entrySet()) {
            for (AEKey result : entry.getValue()) {
                json.beginArray().value(indexOf(entry.getKey())).value(indexOf(result)).endArray();
            }
        }
        json.endArray();

        json.name("queries").beginArray();
        for (Query query : this.queries) {
            json.beginObject();
            json.name("operation").value(query.operation());
            json.name("key").value(indexOf(query.key()));
            if (query.result() != null) {
                json.name("result").value(indexOf(query.result()));
            }
            json.endObject();
        }
        json.endArray();
        json.name("integrity").beginObject()
                .name("complete").value(complete)
                .name("serviceClosureCaptured").value(this.serviceClosureCaptured)
                .name("stockComplete").value(true)
                .name("validityCaptured").value(true)
                .name("errors").beginArray();
        for (String error : this.integrityErrors) {
            json.value(error);
        }
        json.endArray().endObject();
        json.endObject();
    }

    private void captureServiceClosure() {
        if (this.serviceClosureCaptured) {
            return;
        }
        try {
            this.craftables.addAll(this.service.delegate.getCraftables(ignored -> true));
            for (AEKey key : this.craftables) {
                indexOf(key);
            }

            int keyCursor = 0;
            int patternCursor = 0;
            while (keyCursor < this.keys.size() || patternCursor < this.patterns.size()) {
                while (keyCursor < this.keys.size()) {
                    AEKey key = this.keys.get(keyCursor++);
                    // 记的是我们侧真正用的那份（并列项定序之后），回放侧读到同一份次序
                    this.service.recordCraftingFor(key, this.service.stabilizedCraftingFor(key));
                }
                while (patternCursor < this.patterns.size()) {
                    indexOf(this.patterns.get(patternCursor++));
                }
            }

            List<AEKey> queryDomain = List.copyOf(this.keys);
            for (AEKey key : queryDomain) {
                recordEmittable(key, this.service.delegate.canEmitFor(key));
                recordFuzzyResults(key, this.craftables);
            }
            this.serviceClosureCaptured = true;
        } catch (RuntimeException failure) {
            this.integrityErrors.add(failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
        }
    }

    private void recordEmittable(AEKey key, boolean result) {
        indexOf(key);
        if (result) {
            this.emittable.add(key);
        }
    }

    private void recordFuzzyResults(AEKey key, Collection<AEKey> candidates) {
        List<AEKey> results = new ArrayList<>();
        for (AEKey candidate : candidates) {
            AEKey result = this.service.delegate.getFuzzyCraftable(key, candidate::equals);
            if (result != null && !results.contains(result)) {
                indexOf(result);
                results.add(result);
            }
        }
        this.fuzzyCraftable.put(key, List.copyOf(results));
    }

    private void writeEnvironment(JsonWriter json) throws IOException {
        json.name("environment").beginObject();
        json.name("minecraft").value(net.minecraft.SharedConstants.getCurrentVersion().getName());
        json.name("java").value(System.getProperty("java.version", "unknown"));
        json.name("vm").value(System.getProperty("java.vm.name", "unknown"));
        json.name("os").value(System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.arch", "unknown"));
        json.name("processors").value(Runtime.getRuntime().availableProcessors());
        json.name("thread").value(Thread.currentThread().getName());
        json.name("mods").beginObject();
        writeModVersion(json, ModIds.MODID);
        writeModVersion(json, "ae2");
        writeModVersion(json, "neoforge");
        writeModVersion(json, "neoecoae");
        json.endObject().endObject();
    }

    private static void writeModVersion(JsonWriter json, String modId) throws IOException {
        String version = ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unavailable");
        json.name(modId).value(version);
    }

    private void writePattern(JsonWriter json, IPatternDetails pattern) throws IOException {
        json.beginObject();
        json.name("definition").value(indexOf(pattern.getDefinition()));
        json.name("color").value(PatternColorHelper.getPatternColor(pattern));
        json.name("outputs").beginArray();
        for (GenericStack output : pattern.getOutputs()) {
            if (output != null) {
                json.beginArray().value(indexOf(output.what())).value(output.amount()).endArray();
            }
        }
        json.endArray();
        json.name("inputs").beginArray();
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            writeInput(json, input);
        }
        json.endArray().endObject();
    }

    private void writeInput(JsonWriter json, IPatternDetails.IInput input) throws IOException {
        GenericStack[] candidates = input.getPossibleInputs();
        json.beginObject();
        json.name("multiplier").value(input.getMultiplier());
        json.name("candidates").beginArray();
        for (GenericStack candidate : candidates) {
            if (candidate != null) {
                json.beginArray().value(indexOf(candidate.what())).value(candidate.amount()).endArray();
            }
        }
        json.endArray();
        json.name("acceptsKnown").value(true);
        json.name("accepts").beginArray();
        for (AEKey key : classMembersOf(candidates)) {
            if (input.isValid(key, this.level)) {
                json.value(indexOf(key));
            }
        }
        json.endArray();
        json.name("remainders").beginArray();
        for (GenericStack candidate : candidates) {
            // 与上方 candidates 数组对齐：跳过 null 候选，否则回放按序号错位配对
            if (candidate == null) {
                continue;
            }
            AEKey remainder = input.getRemainingKey(candidate.what());
            json.value(remainder == null ? -1 : indexOf(remainder));
        }
        json.endArray().endObject();
    }

    private List<AEKey> classMembersOf(GenericStack[] candidates) {
        Set<Object> classes = new LinkedHashSet<>();
        for (GenericStack candidate : candidates) {
            if (candidate != null) {
                classes.add(candidate.what().getPrimaryKey());
            }
        }
        List<AEKey> result = new ArrayList<>();
        for (AEKey key : this.keys) {
            if (classes.contains(key.getPrimaryKey())) {
                result.add(key);
            }
        }
        return result;
    }

    private int requestedColor() {
        return this.target instanceof AEItemKey itemKey
                ? PatternColorHelper.getPatternColor(itemKey.toStack(1)) : -1;
    }

    private int indexOf(AEKey key) {
        Integer existing = this.keyIndex.get(key);
        if (existing != null) {
            return existing;
        }
        int index = this.keys.size();
        this.keyIndex.put(key, index);
        this.keys.add(key);
        return index;
    }

    private int indexOf(IPatternDetails pattern) {
        Integer existing = this.patternIndex.get(pattern);
        if (existing != null) {
            return existing;
        }
        int index = this.patterns.size();
        this.patternIndex.put(pattern, index);
        this.patterns.add(pattern);
        indexOf(pattern.getDefinition());
        for (GenericStack output : pattern.getOutputs()) {
            if (output != null) {
                indexOf(output.what());
            }
        }
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null) {
                    indexOf(candidate.what());
                    AEKey remainder = input.getRemainingKey(candidate.what());
                    if (remainder != null) {
                        indexOf(remainder);
                    }
                }
            }
        }
        return index;
    }

    private static KeyCounter copy(KeyCounter source) {
        KeyCounter result = new KeyCounter();
        result.addAll(source);
        return result;
    }

    private HolderLookup.Provider registries() {
        if (this.level != null) {
            return this.level.registryAccess();
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.registryAccess()
                : RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static Path capturePath(Path dir, UUID captureId) {
        return dir.resolve("chexsonsaeutils-plan-capture-" + captureId + ".json");
    }

    private static void writeAtomically(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Frozen(UUID captureId, String json) {
    }

    private record Query(String operation, AEKey key, @Nullable AEKey result) {
    }

    private final class RecordingService implements ICraftingService,
            ProviderPatternOrder.PatternPrioritySource {
        private final ICraftingService delegate;

        private RecordingService(ICraftingService delegate) {
            this.delegate = delegate;
        }

        /**
         * 录制代理报的优先级穿回真网格（{@code delegate} 是 AE2 的 {@code CraftingService} 时
         * 走 {@code getProviders}，否则按默认 0）：并列项定序在录制侧与实机侧必须是同一把尺，
         * 否则工件里记下的路线序与实机真正用过的那份不同。
         */
        @Override
        public int patternPriority(IPatternDetails pattern) {
            return ProviderPatternOrder.observedPriority(delegate, pattern);
        }

        /** 我们侧真正用的那份清单＝并列项定过序的那份（台账 #140）。 */
        private Collection<IPatternDetails> stabilizedCraftingFor(AEKey key) {
            return ProviderPatternOrder.stabilized(this, this.delegate.getCraftingFor(key));
        }

        private void recordCraftingFor(AEKey key, Collection<IPatternDetails> result) {
            List<Integer> indices = new ArrayList<>(result.size());
            for (IPatternDetails pattern : result) {
                indices.add(indexOf(pattern));
            }
            craftingFor.put(key, List.copyOf(indices));
            indexOf(key);
        }

        @Override
        public Collection<IPatternDetails> getCraftingFor(AEKey key) {
            var result = stabilizedCraftingFor(key);
            recordCraftingFor(key, result);
            queries.add(new Query("getCraftingFor", key, null));
            return result;
        }

        @Override
        public boolean canEmitFor(AEKey key) {
            boolean result = this.delegate.canEmitFor(key);
            recordEmittable(key, result);
            queries.add(new Query(result ? "canEmitFor:true" : "canEmitFor:false", key, null));
            return result;
        }

        @Override
        public AEKey getFuzzyCraftable(AEKey key, AEKeyFilter filter) {
            AEKey result = this.delegate.getFuzzyCraftable(key, filter);
            indexOf(key);
            if (result != null) {
                List<AEKey> results = new ArrayList<>(fuzzyCraftable.getOrDefault(key, List.of()));
                if (!results.contains(result)) {
                    results.add(result);
                }
                fuzzyCraftable.put(key, List.copyOf(results));
                indexOf(result);
            }
            queries.add(new Query("getFuzzyCraftable", key, result));
            return result;
        }

        @Override public void refreshNodeCraftingProvider(IGridNode node) { this.delegate.refreshNodeCraftingProvider(node); }
        @Override public void addGlobalCraftingProvider(ICraftingProvider provider) { this.delegate.addGlobalCraftingProvider(provider); }
        @Override public void removeGlobalCraftingProvider(ICraftingProvider provider) { this.delegate.removeGlobalCraftingProvider(provider); }
        @Override public void refreshGlobalCraftingProvider(ICraftingProvider provider) { this.delegate.refreshGlobalCraftingProvider(provider); }
        @Override public java.util.concurrent.Future<ICraftingPlan> beginCraftingCalculation(Level level,
                ICraftingSimulationRequester requester, AEKey key, long amount, CalculationStrategy strategy) {
            return this.delegate.beginCraftingCalculation(level, requester, key, amount, strategy);
        }
        @Override public ICraftingSubmitResult submitJob(ICraftingPlan plan, ICraftingRequester requester,
                ICraftingCPU target, boolean prioritizePower, IActionSource source) {
            return this.delegate.submitJob(plan, requester, target, prioritizePower, source);
        }
        @Override public ImmutableSet<ICraftingCPU> getCpus() { return this.delegate.getCpus(); }
        @Override public Set<AEKey> getCraftables(AEKeyFilter filter) { return this.delegate.getCraftables(filter); }
        @Override public boolean isRequesting(AEKey key) { return this.delegate.isRequesting(key); }
        @Override public long getRequestedAmount(AEKey key) { return this.delegate.getRequestedAmount(key); }
        @Override public boolean isRequestingAny() { return this.delegate.isRequestingAny(); }
    }
}
