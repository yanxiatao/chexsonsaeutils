package git.chexson.chexsonsaeutils.helpers.remote;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageHelper;
import appeng.util.ConfigInventory;
import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.remote.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** 有界跨网调度实现；B 回调只接单，A 回调只接收实物，实际交付放到后续世界 tick。 */
public final class RemoteTransmitterImpl implements RemoteTransmitter, DeclaredBatchHost, ICraftingRequester {
    private static final int MAX_KEYS = 256;
    private static final long CAPACITY = 1_000_000_000L;
    private static final int MAX_TASKS = 4;
    private static RemotePatternEncoding encoding;
    /** common setup 注入真实编码器，缺失装配必须快速失败。 */
    public static void bindEncoding(RemotePatternEncoding value) { encoding = Objects.requireNonNull(value); }
    private final RemoteTransmitterHost host;
    private final IActionSource source = IActionSource.ofMachine(this);
    private final IActionSource receiver;
    private final ConfigInventory filter = ConfigInventory.configStacks(18).changeListener(this::changed).build();
    private RemoteLedger<AEKey> ledger = new RemoteLedgerImpl<>(MAX_KEYS, CAPACITY);
    private final Map<UUID, ICraftingLink> links = new LinkedHashMap<>();
    private List<IPatternDetails> patterns = List.of();
    private Set<AEKey> outputs = Set.of();
    private UUID identity = UUID.randomUUID();
    private int priority;
    private boolean paused;
    private boolean faulted;
    private boolean loading;
    private String status = "初始化";
    private Future<ICraftingPlan> calculation;
    private AEKey calculatingKey;
    private long calculatingAmount;
    private IGrid calculatingSource;
    private long nextCalculation;
    private long nextRefresh;
    private boolean publishedReady;
    private int publishedPriority;
    private int cursor;
    private int deliveryCursor;
    private IGrid receiverBinding;
    private boolean deliveryHeld;
    private final Map<AEKey, Long> blockedUntil = new HashMap<>();
    public RemoteTransmitterImpl(RemoteTransmitterHost host) {
        this.host = Objects.requireNonNull(host);
        this.receiver = IActionSource.ofMachine(() -> host.receiverNode().getNode());
        host.sourceNode().addService(ICraftingRequester.class, this);
        host.receiverNode().addService(ICraftingProvider.class, this);
    }
    @Override public UUID identity() { return identity; }
    @Override public String label() { return host.remoteLabel(); }
    @Override public Level world() { return host.world(); }
    @Override public IGrid sourceGrid() { return host.sourceNode().getGrid(); }
    @Override public IGrid receiverGrid() { return host.receiverNode().getGrid(); }
    @Override public Set<AEKey> outputs() { return outputs; }
    @Override public int priority() { return priority; }
    @Override public ConfigInventory filter() { return filter; }
    @Override public String status() { return status; }
    @Override public boolean paused() { return paused; }
    @Override public void priority(int value) {
        if (value < -100000 || value > 100000) throw new IllegalArgumentException("优先级范围为 -100000..100000");
        priority = value; changed();
    }
    @Override public void pause(boolean value) { paused = value; changed(); }
    private void changed() {
        if (!loading) { nextRefresh = 0; host.saveRemoteChanges(); }
    }
    @Override public boolean ready() {
        return !paused && !faulted && !deliveryHeld && host.sourceNode().isActive() && host.receiverNode().isActive()
                && sourceGrid() != null && receiverGrid() != null && sourceGrid() != receiverGrid();
    }
    @Override public BigInteger load() {
        return ledger.pending().values().stream().reduce(BigInteger.ZERO, BigInteger::add)
                .add(BigInteger.valueOf(links.size()));
    }
    @Override public boolean accept(AEKey key, BigInteger amount) {
        if (!ready() || !outputs.contains(key) || world().getGameTime() < blockedUntil.getOrDefault(key, 0L)) return false;
        if (!ledger.enqueue(key, amount)) return false;
        host.saveRemoteChanges();
        return true;
    }
    @Override public List<IPatternDetails> getAvailablePatterns() { return ready() ? patterns : List.of(); }
    @Override public int getPatternPriority() { return priority; }
    @Override public boolean isBusy() { return !ready(); }
    @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] holder) {
        return pushDeclaredBatch(pattern, holder, BigInteger.ONE).signum() > 0;
    }
    @Override public boolean supportsDeclaredBatch(IPatternDetails pattern) { return pattern instanceof RemotePattern; }
    @Override public boolean declaredBatchFiringsUnbounded() { return true; }
    @Override public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] prototype, BigInteger times) {
        if (!(pattern instanceof RemotePattern remote) || !ready() || prototype.length != 0 || times.signum() <= 0) return BigInteger.ZERO;
        return RemoteRoutes.enqueue(receiverGrid(), remote.output(), times) ? times : BigInteger.ZERO;
    }
    @Override public IGridNode getActionableNode() { return host.sourceNode().getNode(); }
    @Override public ImmutableSet<ICraftingLink> getRequestedJobs() { return ImmutableSet.copyOf(links.values()); }
    @Override public long insertCraftedItems(ICraftingLink link, AEKey key, long amount, Actionable mode) {
        long accepted = ledger.receive(link.getCraftingID(), key, amount, mode == Actionable.SIMULATE);
        if (mode == Actionable.MODULATE && accepted > 0) host.saveRemoteChanges();
        return accepted;
    }
    @Override public void jobStateChange(ICraftingLink link) {
        var id = link.getCraftingID();
        if (!links.containsKey(id)) return;
        var remaining = ledger.reservations().get(id);
        boolean incomplete = remaining != null && remaining.remaining() > 0;
        ledger.close(id, incomplete);
        links.remove(id);
        if (link.isCanceled() || incomplete) {
            faulted = true;
            status = "故障：来源任务取消或交付不完整，点击重试继续未生产部分";
            LogUtils.getLogger().warn("传递器来源任务未完整结束：device={} link={} remaining={}", identity, id, remaining);
        }
        host.saveRemoteChanges();
    }
    @Override public ICraftingPlan preview(AEKey output, BigInteger amount, KeyCounter stock) {
        var grid = sourceGrid();
        if (!ready() || grid == null) throw new IllegalStateException("来源离线或暂停");
        return RemotePlanning.plan(world(), grid.getCraftingService(), output, amount, grid, stock);
    }
    @Override public void tick() {
        if (world() == null || world().isClientSide()) return;
        try {
            RemoteRoutes.register(this);
            long now = world().getGameTime();
            var receiverGrid = receiverGrid();
            if (receiverGrid != null && receiverBinding != null && receiverGrid != receiverBinding
                    && (!ledger.pending().isEmpty() || !ledger.buffered().isEmpty() || !links.isEmpty())) {
                deliveryHeld = true;
                status = "目的网络变更：点击确认网络后才能继续交付";
            }
            if (!deliveryHeld && receiverGrid != null) receiverBinding = receiverGrid;
            if (now >= nextRefresh) { nextRefresh = now + 40; refresh(); }
            deliver();
            if (!ready()) {
                if (!faulted && !deliveryHeld) status = paused ? "已暂停" : sourceGrid() == receiverGrid() && sourceGrid() != null ? "禁止连接同一网络" : "来源或接收网络离线";
                if (calculation != null) stopCalculation();
                return;
            }
            if (calculation != null && calculation.isDone()) finishCalculation(now);
            if (calculation == null && now >= nextCalculation && links.size() < MAX_TASKS) beginCalculation(now);
            if (calculation == null && ledger.pending().isEmpty()) status = links.isEmpty() ? ledger.buffered().isEmpty() ? "就绪" : "等待交付" : "来源执行中";
        } catch (RuntimeException failure) {
            faulted = true; status = "故障：" + failure.getMessage(); stopCalculation();
            LogUtils.getLogger().error("样板传递器调度失败，已停止新任务：device={}", identity, failure);
            host.saveRemoteChanges();
        }
    }
    private void refresh() {
        if (encoding == null) throw new IllegalStateException("远程样板编码器未装配");
        var next = new HashSet<AEKey>();
        if (sourceGrid() != null && sourceGrid() != receiverGrid()) {
            var view = new LocalCraftingViewImpl(sourceGrid().getCraftingService(), sourceGrid());
            var selected = new HashSet<AEKey>();
            for (int i = 0; i < filter.size(); i++) if (filter.getKey(i) != null) selected.add(filter.getKey(i));
            for (var key : view.getCraftables(k -> true)) if (selected.isEmpty() || selected.contains(key)) next.add(key);
        }
        boolean changed = !next.equals(outputs);
        if (changed) {
            outputs = Set.copyOf(next);
            patterns = next.stream().sorted(Comparator.comparing(Object::toString))
                    .map(k -> (IPatternDetails) encoding.encode(k, world().registryAccess())).toList();
        }
        // 活跃状态、优先级和能力变化都必须刷新，离线不能保留可接单标记。
        boolean active = ready();
        if (changed || active != publishedReady || priority != publishedPriority) {
            publishedReady = active; publishedPriority = priority;
            ICraftingProvider.requestUpdate(host.receiverNode());
        }
    }
    private void beginCalculation(long now) {
        var keys = new ArrayList<>(ledger.pending().keySet());
        if (keys.isEmpty()) return;
        for (int n = 0; n < keys.size(); n++) {
            var key = keys.get(Math.floorMod(cursor++, keys.size()));
            if (now < blockedUntil.getOrDefault(key, 0L)) continue;
            long limit = Math.min(CAPACITY, Math.multiplyExact(4096L, key.getAmountPerUnit()));
            long amount = ledger.batch(key, limit);
            if (amount == 0) continue;
            calculatingKey = key; calculatingAmount = amount; calculatingSource = sourceGrid();
            // 图构建和求解放到独立有界执行器；结果只在服务端 tick 接受。
            var grid = calculatingSource;
            var stock = new KeyCounter(); grid.getStorageService().getInventory().getAvailableStacks(stock);
            calculation = RemoteCalculations.submit(() -> RemotePlanning.plan(world(), grid.getCraftingService(), key,
                    BigInteger.valueOf(amount), grid, stock, null, CalculationStrategy.CRAFT_LESS));
            status = "计算来源计划";
            return;
        }
    }
    private void finishCalculation(long now) {
        try {
            var plan = calculation.get();
            if (sourceGrid() != calculatingSource || !ready()) return;
            if (plan.simulation()) {
                status = "缺料：" + plan.missingItems().size() + " 种；等待补料";
                blockedUntil.put(calculatingKey, now + 100);
                // 只移动未提交需求；其他设备接单失败时旧账保持不变。
                if (RemoteRoutes.enqueueAlternative(receiverGrid(), calculatingKey, identity, BigInteger.valueOf(calculatingAmount))) {
                    ledger.movePending(calculatingKey, BigInteger.valueOf(calculatingAmount));
                    host.saveRemoteChanges();
                }
                return;
            }
            long produced = plan.finalOutput().amount();
            if (!calculatingKey.equals(plan.finalOutput().what()) || produced <= 0 || produced > calculatingAmount)
                throw new IllegalStateException("来源计划产物与预约不匹配");
            var submitted = calculatingSource.getCraftingService().submitJob(plan, this, null, false, source);
            if (submitted.successful()) {
                var link = Objects.requireNonNull(submitted.link(), "来源提交必须返回 requester link");
                ledger.submitted(link.getCraftingID(), calculatingKey, produced);
                links.put(link.getCraftingID(), link);
                status = "来源执行中"; host.saveRemoteChanges();
            } else {
                status = "等待来源 CPU / 材料：" + submitted.errorCode();
                LogUtils.getLogger().debug("传递器提交等待：device={} reason={}", identity, submitted.errorCode());
                nextCalculation = now + 40;
                blockedUntil.put(calculatingKey, now + 40);
                if (RemoteRoutes.enqueueAlternative(receiverGrid(), calculatingKey, identity, BigInteger.valueOf(calculatingAmount))) {
                    ledger.movePending(calculatingKey, BigInteger.valueOf(calculatingAmount));
                    host.saveRemoteChanges();
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("远程计算被中断", failure);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("远程计算失败", failure.getCause());
        } finally { calculation = null; calculatingSource = null; }
    }
    private void deliver() {
        var grid = receiverGrid();
        if (deliveryHeld || grid == null || !host.receiverNode().isActive() || sourceGrid() == grid) return;
        var buffered = new ArrayList<>(ledger.buffered().entrySet());
        for (int n = 0; n < Math.min(16, buffered.size()); n++) {
            var entry = buffered.get(Math.floorMod(deliveryCursor++, buffered.size()));
            long amount = entry.getValue().min(BigInteger.valueOf(CAPACITY)).longValueExact();
            long inserted = StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), entry.getKey(), amount, receiver);
            if (inserted > 0) { ledger.delivered(entry.getKey(), inserted); host.saveRemoteChanges(); }
        }
    }
    private void stopCalculation() {
        if (calculation != null) calculation.cancel(true);
        calculation = null; calculatingSource = null;
    }
    @Override public void retry() {
        faulted = false; deliveryHeld = false; receiverBinding = receiverGrid();
        blockedUntil.clear(); nextCalculation = 0; changed();
    }
    @Override public void cancel() {
        stopCalculation();
        for (var link : List.copyOf(links.values())) link.cancel();
        for (var id : List.copyOf(links.keySet())) { ledger.close(id, false); links.remove(id); }
        ledger.cancelPending(); faulted = false; status = "已取消；缓冲继续交付"; changed();
    }
    @Override public void unload(boolean dismantle) {
        stopCalculation(); RemoteRoutes.unregister(this);
        if (dismantle) cancel();
    }
    @Override public List<String> orderLines() {
        var rows = new ArrayList<String>();
        ledger.pending().forEach((k, n) -> rows.add("待生产 " + k.getDisplayName().getString() + " × " + n));
        ledger.reservations().forEach((id, r) -> rows.add("来源执行 " + r.key().getDisplayName().getString() + " 待接收 " + r.remaining()));
        ledger.buffered().forEach((k, n) -> rows.add("待交付 " + k.getDisplayName().getString() + " × " + n));
        return List.copyOf(rows);
    }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putUUID("identity", identity); tag.putInt("priority", priority); tag.putBoolean("paused", paused); tag.putBoolean("deliveryHeld", deliveryHeld);
        tag.putBoolean("faulted", faulted); tag.putString("status", status); filter.writeToChildTag(tag, "filter", registries);
        tag.put("pending", writeAmounts(ledger.pending(), registries));
        tag.put("buffer", writeAmounts(ledger.buffered(), registries));
        var tasks = new ListTag();
        ledger.reservations().forEach((id, r) -> {
            var task = new CompoundTag(); task.putUUID("id", id); task.put("key", r.key().toTagGeneric(registries));
            task.putLong("remaining", r.remaining());
            var link = new CompoundTag(); Objects.requireNonNull(links.get(id), "预约缺少来源 link").writeToNBT(link);
            task.put("link", link); tasks.add(task);
        });
        tag.put("tasks", tasks);
    }
    private static ListTag writeAmounts(Map<AEKey, BigInteger> values, HolderLookup.Provider registries) {
        var list = new ListTag();
        values.forEach((key, amount) -> { var t = new CompoundTag(); t.put("key", key.toTagGeneric(registries)); t.putString("amount", amount.toString()); list.add(t); });
        return list;
    }
    private static Map<AEKey, BigInteger> readAmounts(ListTag list, HolderLookup.Provider registries) {
        var result = new LinkedHashMap<AEKey, BigInteger>();
        for (var entry : list) {
            var t = (CompoundTag) entry;
            var key = Objects.requireNonNull(AEKey.fromTagGeneric(registries, t.getCompound("key")), "存档 key 无效");
            if (result.put(key, new BigInteger(t.getString("amount"))) != null) throw new IllegalStateException("存档 key 重复");
        }
        return result;
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.isEmpty()) return;
        loading = true;
        try {
            identity = tag.getUUID("identity"); priority = tag.getInt("priority"); paused = tag.getBoolean("paused"); deliveryHeld = tag.getBoolean("deliveryHeld");
            faulted = tag.getBoolean("faulted"); status = tag.getString("status"); filter.readFromChildTag(tag, "filter", registries);
            links.clear(); var reservations = new LinkedHashMap<UUID, RemoteLedger.Reservation<AEKey>>();
            for (var entry : tag.getList("tasks", Tag.TAG_COMPOUND)) {
                var t = (CompoundTag) entry; var id = t.getUUID("id");
                var key = Objects.requireNonNull(AEKey.fromTagGeneric(registries, t.getCompound("key")), "来源预约 key 无效");
                var link = StorageHelper.loadCraftingLink(t.getCompound("link"), this);
                if (!id.equals(link.getCraftingID()) || links.put(id, link) != null) throw new IllegalStateException("来源 link 身份重复或不匹配");
                reservations.put(id, new RemoteLedger.Reservation<>(key, t.getLong("remaining")));
            }
            ledger = RemoteLedgerImpl.restore(MAX_KEYS, CAPACITY, readAmounts(tag.getList("pending", Tag.TAG_COMPOUND), registries),
                    readAmounts(tag.getList("buffer", Tag.TAG_COMPOUND), registries), reservations);
        } finally { loading = false; }
    }
}
