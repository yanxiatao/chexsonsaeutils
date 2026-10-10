package git.chexson.chexsonsaeutils.bootstrap.patterntransmitter;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.remote.*;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload;
import git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/** 只从玩家当前有效菜单读取权威计划；来源数据在服务器授权范围内采集，后台只做独立预览。 */
public final class RemotePreviewAssembly {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> { var t = new Thread(task, "chexsonsaeutils-remote-preview"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    static { POOL.allowCoreThreadTimeOut(true); }
    private RemotePreviewAssembly() {}
    /** 世界关闭必须撤销后台预览并释放菜单、玩家和来源网引用；不影响持久化执行订单。 */
    public static void stop() {
        SESSIONS.values().forEach(s -> { if (s.future != null) s.future.cancel(true); });
        SESSIONS.clear(); POOL.purge();
        RemoteRoutes.clear();
    }
    /** 关闭原生确认菜单立即释放预览，避免静态缓存继续持有玩家与网格。 */
    public static void closed(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        if (event.getEntity().level().isClientSide()) return;
        var session = SESSIONS.remove(event.getEntity().getUUID());
        if (session != null && session.future != null) session.future.cancel(true);
        POOL.purge();
    }
    private record Candidate(RemoteEndpoint endpoint, IGrid source, boolean ready, String label, String status, RemoteStock stock) {}
    private static final class Session {
        final CraftConfirmMenu menu;
        final ICraftingPlan plan;
        final long started;
        Future<?> future;
        List<Row> rows;
        Request latest;
        Session(CraftConfirmMenu menu, ICraftingPlan plan, long started, Request request) {
            this.menu = menu; this.plan = plan; this.started = started; this.latest = request;
        }
    }
    public static void request(Request request, ServerPlayer player) {
        if (!(player.containerMenu instanceof CraftConfirmMenu menu) || menu.containerId != request.menuId()
                || !menu.stillValid(player) || !(menu instanceof RemoteConfirmPlan access)) return;
        var plan = access.remotePlan();
        if (plan == null) { reply(player, request, List.of(), "接收网络仍在计算计划"); return; }
        if (request.page() < 0) return;
        long now = player.serverLevel().getGameTime();
        var old = SESSIONS.get(player.getUUID());
        if (!request.refresh() && old != null && old.menu == menu && old.plan == plan && old.rows != null && now - old.started < 200) {
            reply(player, request, old.rows, "完整需求试算；不预留材料，执行时按批次选择来源"); return;
        }
        if (old != null && old.future != null && !old.future.isDone()) {
            if (old.menu == menu && old.plan == plan) {
                old.latest = request;
                reply(player, request, List.of(), "正在计算来源计划，完成后自动更新"); return;
            }
            old.future.cancel(true);
        }
        if (old != null && now - old.started < 20) { reply(player, request, List.of(), "刷新间隔为一秒"); return; }
        var receiver = menu.getActionSource().machine().map(h -> h.getActionableNode()).map(n -> n.getGrid()).orElse(null);
        if (receiver == null) { reply(player, request, List.of(), "接收网络离线"); return; }
        var demand = RemoteDemand.from(plan);
        if (demand.isEmpty()) { reply(player, request, List.of(), "本次计划没有远程样板（本地库存或配方已满足需求）"); return; }
        var candidates = new LinkedHashMap<AEKey, List<Candidate>>();
        var stockByGrid = new IdentityHashMap<IGrid, RemoteStock>();
        demand.keySet().stream().sorted(Comparator.comparing(Object::toString)).forEach(key -> {
            var list = new ArrayList<Candidate>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
            for (var endpoint : RemoteRoutes.sources(receiver, key)) {
                var source = endpoint.sourceGrid();
                if (source == null || !seen.add(source)) continue;
                var stock = stockByGrid.computeIfAbsent(source, grid -> {
                    var counter = new KeyCounter(); grid.getStorageService().getInventory().getAvailableStacks(counter);
                    return new RemoteStock(counter, git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.capture(grid, counter));
                });
                list.add(new Candidate(endpoint, source, endpoint.ready(), endpoint.label(), endpoint.status(), stock));
            }
            candidates.put(key, list);
        });
        var session = new Session(menu, plan, now, request); SESSIONS.put(player.getUUID(), session);
        // 清理关闭的菜单及退出玩家，不让静态表持有旧世界。
        SESSIONS.entrySet().removeIf(e -> {
            var value = e.getValue();
            boolean closed = value.menu.getPlayer().containerMenu != value.menu || value.menu.getPlayer().isRemoved();
            if (closed && value.future != null) value.future.cancel(true);
            return closed;
        });
        try {
            session.future = POOL.submit(() -> {
                try {
                    var rows = compute(demand, candidates);
                    player.getServer().execute(() -> {
                        if (SESSIONS.get(player.getUUID()) != session || player.containerMenu != menu
                                || ((RemoteConfirmPlan) menu).remotePlan() != plan) return;
                        session.rows = rows;
                        reply(player, session.latest, rows, "完整需求试算；同一来源共享扣账，其他来源库存独立");
                    });
                } catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted()) return;
                    LogUtils.getLogger().error("接收网络远程预览失败：player={} menu={}", player.getUUID(), menu.containerId, failure);
                    player.getServer().execute(() -> {
                        if (SESSIONS.get(player.getUUID()) == session && player.containerMenu == menu
                                && ((RemoteConfirmPlan) menu).remotePlan() == plan)
                            reply(player, session.latest, List.of(), "来源预览失败：" + shortText(failure.getMessage(), 450));
                    });
                }
            });
        } catch (RejectedExecutionException failure) {
            SESSIONS.remove(player.getUUID());
            LogUtils.getLogger().warn("远程预览队列已满，拒绝请求：player={}", player.getUUID());
            reply(player, request, List.of(), "预览繁忙，请稍后刷新");
        }
    }
    private static List<Row> compute(Map<AEKey, BigInteger> demand, Map<AEKey, List<Candidate>> candidates) {
        var rows = new ArrayList<Row>();
        for (var entry : candidates.entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            var key = entry.getKey(); var amount = demand.get(key);
            boolean selected = false;
            for (var candidate : entry.getValue()) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                if (!candidate.ready()) {
                    appendRows(rows, candidate.label(), key, amount, "不可接单：" + candidate.status(), List.of()); continue;
                }
                try {
                    var plan = RemotePlanning.plan(candidate.endpoint().world(), candidate.source().getCraftingService(), key, amount,
                            candidate.source(), candidate.stock().snapshot(), candidate.stock().truth());
                    boolean fitsCpu = candidate.source().getCraftingService().getCpus().stream()
                            .anyMatch(cpu -> !cpu.isBusy() && cpu.getAvailableStorage() >= plan.bytes());
                    String status = plan.simulation() ? "缺料" : fitsCpu ? "材料与 CPU 可用" : "等待来源 CPU";
                    var details = new ArrayList<String>();
                    details.add("来源 CPU 需要 " + plan.bytes() + " B；副产物和容器留在来源网络");
                    details.add("需求最小单位：" + amount);
                    var usedWide = WideAmountPlanCarrier.resolveWideLedgers(plan).entries(git.chexson.chexsonsaeutils.bigint.WideAmountKind.USED);
                    plan.usedItems().forEach(e -> details.add("从来源存储：" + e.getKey().getDisplayName().getString() + " × "
                            + usedWide.getOrDefault(e.getKey(), BigInteger.valueOf(e.getLongValue()))));
                    var missingWide = WideAmountPlanCarrier.resolveWideLedgers(plan).entries(git.chexson.chexsonsaeutils.bigint.WideAmountKind.MISSING);
                    plan.missingItems().forEach(e -> details.add("缺料：" + e.getKey().getDisplayName().getString() + " × "
                            + missingWide.getOrDefault(e.getKey(), BigInteger.valueOf(e.getLongValue()))));
                    plan.patternTimes().forEach((pattern, times) -> details.add("来源子配方：" + pattern.getPrimaryOutput().what().getDisplayName().getString() + " × "
                            + WideAmountPlanCarrier.resolveWideCrafts(plan).entries().getOrDefault(pattern, BigInteger.valueOf(times))));
                    if (!selected && !plan.simulation()) {
                        selected = true; status = "优先候选 · " + status;
                        // 同源重复设备不重复读库存；选中的多个目标从同一快照扣除实际 used。
                        candidate.stock().reserve(plan);
                    }
                    appendRows(rows, candidate.label(), key, amount, status, details);
                } catch (RuntimeException failure) {
                    LogUtils.getLogger().warn("单个来源预览拒绝：source={} output={}", candidate.label(), key, failure);
                    appendRows(rows, candidate.label(), key, amount, "来源规划失败", List.of(String.valueOf(failure.getMessage())));
                }
            }
            if (entry.getValue().isEmpty()) appendRows(rows, "来源不可用", key, amount, "来源离线或过滤已移除", List.of());
        }
        return List.copyOf(rows);
    }
    private static void appendRows(List<Row> rows, String source, AEKey output, BigInteger amount, String status, List<String> details) {
        var lines = new ArrayList<String>();
        for (var line : details) for (int offset = 0; offset < line.length(); offset += 480) lines.add(line.substring(offset, Math.min(line.length(), offset + 480)));
        int parts = Math.max(1, (lines.size() + 31) / 32);
        for (int part = 0; part < parts; part++) rows.add(new Row(shortText(source, 256), shortText(output.getDisplayName().getString(), 256),
                amount.toString().length() <= 2048 ? amount.toString() : "精确数量见详情", shortText(status + (parts > 1 ? " · 详情 " + (part + 1) + "/" + parts : ""), 256),
                lines.subList(Math.min(lines.size(), part * 32), Math.min(lines.size(), (part + 1) * 32))));
    }
    private static String shortText(String text, int max) { return text == null ? "未知原因" : text.length() > max ? text.substring(0, max - 1) + "…" : text; }
    private static void reply(ServerPlayer player, Request request, List<Row> rows, String message) {
        var search = request.search().toLowerCase(Locale.ROOT);
        rows = rows.stream().filter(r -> (r.source() + " " + r.output() + " " + r.status()).toLowerCase(Locale.ROOT).contains(search))
                .filter(r -> !request.problemsOnly() || r.status().contains("缺料") || r.status().contains("等待")
                        || r.status().contains("失败") || r.status().contains("不可") || r.status().contains("离线")).toList();
        int pages = Math.max(1, (rows.size() + 7) / 8); int page = Math.min(request.page(), pages - 1);
        PacketDistributor.sendToPlayer(player, new Response(request.menuId(), request.queryId(), page, pages, message,
                rows.subList(Math.min(rows.size(), page * 8), Math.min(rows.size(), (page + 1) * 8))));
    }
}
