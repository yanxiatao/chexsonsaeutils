package git.chexson.chexsonsaeutils.menu.crafting;

import appeng.api.storage.ISubMenuHost;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.menu.me.crafting.*;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.guisync.GuiSync;
import appeng.crafting.execution.CraftingSubmitResult;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.contract.WideRootCraftingCpu;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.*;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningLevelRouter;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import git.chexson.chexsonsaeutils.crafting.request.CraftingRequestCompatibilityAccess;
import git.chexson.chexsonsaeutils.network.crafting.CraftingAmountRestorePayload;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.neoforged.neoforge.network.PacketDistributor;
import java.math.BigInteger;
import java.util.concurrent.Future;
import com.mojang.logging.LogUtils;

/** 原生确认页协议与交互的大数入口；精确根请求只提交给具有宽域交付契约的 CPU。 */
public final class WideCraftConfirmMenu extends CraftConfirmMenu {
    public static final MenuType<WideCraftConfirmMenu> TYPE = MenuTypeBuilder.create(WideCraftConfirmMenu::new, ISubMenuHost.class)
            .buildUnregistered(ResourceLocation.parse("chexsonsaeutils:wide_craft_confirm"));
    @GuiSync(1150) public String requestedAmount = "";
    @GuiSync(1151) public boolean finalTotal;
    @GuiSync(1152) public String deliveryAmount = "";
    private AEKey target;
    private BigInteger effective;
    private boolean leadingEquals;
    private Future<ICraftingPlan> calculation;
    private ICraftingPlan exactPlan;
    private ICraftingCPU selected;
    private final java.util.List<ICraftingCPU> cpus = new java.util.ArrayList<>();
    private int selectedIndex = -1;

    public WideCraftConfirmMenu(int id, Inventory inventory, ISubMenuHost host) {
        super(id, inventory, host);

    }
    @Override public MenuType<?> getType() { return TYPE; }
    /** 初始精确下单，保留原输入供返回页使用。 */
    public void configure(AEKey key, BigInteger original, BigInteger amount, boolean total, boolean equals, boolean auto) {
        target = key; requestedAmount = original.toString(); effective = amount; finalTotal = total; leadingEquals = equals;
        setAutoStart(auto);
        planExact(CalculationStrategy.REPORT_MISSING_ITEMS);
    }
    private IGrid grid() {
        var host = getActionHost();
        return host == null || host.getActionableNode() == null ? null : host.getActionableNode().getGrid();
    }
    private void planExact(CalculationStrategy strategy) {
        clearError(); exactPlan = null; deliveryAmount = "";
        if (calculation != null) calculation.cancel(true);
        var grid = grid();
        if (grid == null) { setValidMenu(false); return; }
        if (!requiresExactCpu()) {
            var requestSource = CraftingRequestCompatibilityAccess.get().netNew(getActionSource());
            calculation = grid.getCraftingService().beginCraftingCalculation(getLevel(), () -> requestSource, target,
                    effective.longValueExact(), strategy);
        } else {
            var stock = new KeyCounter();
            stock.addAll(grid.getStorageService().getCachedInventory());
            long started = System.nanoTime();
            // 编译也放进后台任务，避免确认菜单打开前在服务端线程等待。
            AEKey requestTarget = target;
            PlanAmount requestAmount = PlanAmount.of(effective);
            var level = getLevel();
            var source = getActionSource();
            calculation = git.chexson.chexsonsaeutils.crafting.progress.PlanningProgressFuture.submit(Util.backgroundExecutor(), () -> {
                var session = GraphPlanningEngine.createSession(grid.getCraftingService(), stock, requestTarget, strategy,
                        PlanningLevelRouter.graphBudgetMillis(), level, true, grid.getStorageService().getInventory(), grid);
                var result = session.solve(requestAmount, GraphPlanningSession.BudgetPolicy.ENFORCED);
                if (result.handled()) {
                    git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance.tag(result.plan(),
                            git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance.GRAPH, started, () -> source);
                    return result.plan();
                }
                var shortage = GraphPlanPostProcessor.shortagePlan(result, session.graph());
                if (shortage != null) return shortage;
                throw new IllegalStateException("大数规划拒绝：" + result.declineReason());
            });
        }
        setJob(calculation);
    }
    @Override public void broadcastChanges() {
        if (isServerSide() && effective != null) {
            if (calculation != null && calculation.isDone()) {
                try {
                    exactPlan = calculation.get();
                    deliveryAmount = git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier
                            .resolveFinalOutput(exactPlan).exactValue().toString();
                }
                catch (Exception failure) { LogUtils.getLogger().error("精确根请求规划失败：{}", requestedAmount, failure); }
                calculation = null;
            }
            var grid = grid();
            if (grid != null) refreshCpus(grid);
        }
        super.broadcastChanges();
        if (isServerSide() && effective != null && grid() != null) refreshCpus(grid());
    }
    @Override public void cycleSelectedCPU(boolean next) {
        if (isClientSide()) sendClientAction("cycleCpu", next);
        else {
            int lower = !requiresExactCpu() ? -1 : 0;
            if (!cpus.isEmpty()) {
                selectedIndex += next ? 1 : -1;
                if (selectedIndex < lower) selectedIndex = cpus.size() - 1;
                if (selectedIndex >= cpus.size()) selectedIndex = lower;
            }
            syncCpuSelection();
        }
    }
    /** 超 long 根数量或字节占用都要求执行器能接收精确计划。 */
    private boolean requiresExactCpu() {
        return effective.bitLength() >= 64 || (exactPlan != null
                && !git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier.resolveBytes(exactPlan).isFast());
    }

    private void refreshCpus(IGrid grid) {
        cpus.clear();
        for (var cpu : grid.getCraftingService().getCpus()) {
            if ((!requiresExactCpu() || cpu instanceof WideRootCraftingCpu)
                    && !cpu.isBusy() && (exactPlan == null || (requiresExactCpu() && cpu instanceof WideRootCraftingCpu wide ? wide.canSubmitWideJob(exactPlan) : cpu.getAvailableStorage() >= exactPlan.bytes()))) cpus.add(cpu);
        }
        cpus.sort(java.util.Comparator.comparingInt(ICraftingCPU::getCoProcessors)
                .thenComparingLong(ICraftingCPU::getAvailableStorage).reversed());
        syncCpuSelection();
    }
    private void syncCpuSelection() {
        if (selectedIndex >= cpus.size()) selectedIndex = -1;
        if (requiresExactCpu() && selectedIndex == -1 && !cpus.isEmpty()) selectedIndex = 0;
        selected = selectedIndex < 0 || cpus.isEmpty() ? null : cpus.get(selectedIndex);
        noCPU = cpus.isEmpty();
        cpuBytesAvail = selected == null ? 0 : selected.getAvailableStorage();
        cpuCoProcessors = selected == null ? 0 : selected.getCoProcessors();
        cpuName = selected == null ? null : selected.getName();
        if (selected != null && (cpuName == null || cpuName.getString().isEmpty()))
            cpuName = net.minecraft.network.chat.Component.literal("#" + (selectedIndex + 1));
    }
    @Override public void startJob() {
        if (isClientSide()) { sendClientAction("startJob"); return; }
        clearError();
        if (!stillValid(getPlayer()) || grid() == null || exactPlan == null) return;
        var mode = CraftingContinuationSubmitBridge.getConfirmMode(this);
        if (exactPlan.simulation() && !mode.allowsSimulationStart()) return;
        if (selected == null && requiresExactCpu()) { submitError = new SyncableSubmitResult(CraftingSubmitResult.NO_CPU_FOUND); setAutoStart(false); return; }
        var result = CraftingContinuationSubmitBridge.withContinuationMode(mode,
                () -> selected instanceof WideRootCraftingCpu wide && requiresExactCpu()
                        ? wide.submitWideJob(grid(), exactPlan, getActionSource())
                        : grid().getCraftingService().submitJob(exactPlan, null, selected, true, getActionSource()));
        setAutoStart(false);
        if (result.successful()) getHost().returnToMainMenu(getPlayer(), this);
        else {
            submitError = new SyncableSubmitResult(result);
            LogUtils.getLogger().warn("精确下单提交拒绝：amount={} reason={} detail={}", requestedAmount, result.errorCode(), result.errorDetail());
        }
    }
    @Override public void replan() {
        if (isClientSide()) { sendClientAction("replan"); return; }
        if (target == null) { goBack(); return; }
        planExact(CalculationStrategy.CRAFT_LESS);
    }
    @Override public void goBack() {
        if (isClientSide()) { sendClientAction("back"); return; }
        if (!(getPlayer() instanceof ServerPlayer player) || target == null || getLocator() == null) return;
        CraftAmountMenu.open(player, getLocator(), target, 1);
        if (player.containerMenu instanceof CraftAmountMenu amount) {
            CraftingRequestCompatibilityAccess.get().finalTotal(amount, finalTotal);
            PacketDistributor.sendToPlayer(player, new CraftingAmountRestorePayload(amount.containerId, new BigInteger(requestedAmount), leadingEquals));
        }
    }
}
