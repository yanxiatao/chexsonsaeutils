package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.storage.ISubMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.guisync.GuiSync;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.crafting.solve.ConfirmMenuProvenanceRegistry;
import git.chexson.chexsonsaeutils.crafting.solve.MenuProvenanceHost;
import git.chexson.chexsonsaeutils.crafting.solve.PartialPlanMissingSummary;
import git.chexson.chexsonsaeutils.bigint.WideAmountMenuSource;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummary;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.core.AELog;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuContinuationMixin extends AEBaseMenu
        implements CraftingContinuationSubmitBridge.ContinuationModeHost, MenuProvenanceHost, WideAmountMenuSource {
    @Unique
    private static final String chexsonSetContinuationMode = "chexsonSetContinuationMode";

    @Shadow
    private CraftingPlanSummary plan;

    @Shadow
    private ICraftingPlan result;

    @Unique private ICraftingPlan chexsonsaeutils$publishedPlan;
    @Unique private CraftingPlanSummary chexsonsaeutils$publishedSummary;

    @Unique
    private CraftingContinuationMode chexsonsaeutils$continuationMode = CraftingContinuationMode.defaultMode();

    /**
     * 标题 provenance 走菜单 @GuiSync（neoecoae 同款做法）：AE2 的
     * DataSynchronization 扫描 {@code getDeclaredFields}，mixin 注入的字段是
     * 类上的真实字段，因此被自动同步。不依赖 summary 对象与 plan packet——
     * 其他规划 mod（data_energistics 的 Trinity 等）接管 planJob、自建
     * summary、绕过 AE2 的 fromJob/sendPacketToClient 时，此路径依然生效。
     * 同步键取 9210/9211（AE2 与常见 addon 用小数值，避开冲突）。
     */
    @GuiSync(9210)
    public long chexsonsaeutils$provenanceMillis = -1L;

    @GuiSync(9211)
    public String chexsonsaeutils$provenanceSolver = "";

    /**
     * M1：超 {@code long} 的计划数量的宽域真值，编码后随菜单同步到客户端确认屏。
     *
     * AE2 的 {@code CraftingPlanSummaryEntry} 数量字段是 {@code long}，图规划出账时
     * 已被 {@code Ae2Amounts.toLong} 饱和成 {@code Long.MAX_VALUE}，真值经普通通道丢失。
     * 宽域账由图规划出口直接挂在计划对象上（{@link WideAmountPlanCarrier}），这里读本菜单
     * {@link #result} 的载体，用 {@link WidePlanLedgerCodec} 编成
     * {@code kind|base64(AEKey)|decimal} 字符串同步。
     * 键取 9212（避开 AE2 本类的 1/2/3/6/7/8 与上面 provenance 的 9210/9211）。
     * 无溢出键时恒为空串——客户端据此原样沿用 AE2 显示，观感零变。
     */
    @GuiSync(9212)
    public String chexsonsaeutils$wideAmounts = "";

    /** 字节真值独立于数量账同步，不能从 summary 的饱和 long 恢复。 */
    @GuiSync(9213)
    public String chexsonsaeutils$exactBytes = "";

    @Override
    public String chexsonsaeutils$syncedExactBytes() { return chexsonsaeutils$exactBytes; }

    protected CraftConfirmMenuContinuationMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$initContinuationMode(int id, Inventory inventory, ISubMenuHost host, CallbackInfo ci) {
        chexsonsaeutils$continuationMode = CraftingContinuationMode.defaultMode();
        registerClientAction(chexsonSetContinuationMode, CraftingContinuationMode.class,
                this::chexsonsaeutils$applyContinuationMode);
        // provenance 登记必须放在构造 TAIL：planJob 会被其他规划 mod 在 HEAD
        // 取消（它们自建 beginCraftingCalculation 调用），TAIL 注入根本不执行；
        // 构造函数无法被取消，是唯一可靠的登记点。
        if (!isClientSide()) {
            ConfirmMenuProvenanceRegistry.register(getPlayer(), this);
        }
    }

    /**
     * 标题 provenance 的挂载点：{@code broadcastChanges} 里 plan packet 发送
     * 之前。此处 {@code this.plan} 已由 fromJob 赋值（fromJob 的 INVOKE
     * AFTER 太早——返回值还在栈上未落字段），packet 持同一引用、序列化在其后，
     * 所以补挂能随包同步。菜单侧锚点不受其他规划 mod 的 summary 替换流影响。
     */
    @Inject(method = "setPlan", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$publishProvenanceOnPlanSet(CraftingPlanSummary plan, CallbackInfo ci) {
        if (isClientSide()) {
            return;
        }
        chexsonsaeutils$syncWideAmounts();
        var provenance = this.result == null ? null : PlanningProvenance.peek(this.result);
        if (provenance == null) {
            return;
        }
        this.chexsonsaeutils$provenanceMillis = provenance.millis();
        this.chexsonsaeutils$provenanceSolver = provenance.solver();
        if (plan instanceof EnhancedCraftingPlanSummary enhanced
                && enhanced.chexsonsaeutils$solver().isEmpty()) {
            enhanced.chexsonsaeutils$setProvenance(provenance.millis(), provenance.solver());
        }
        chexsonsaeutils$syncWideAmounts();
    }

    /**
     * broadcastChanges 侧的补充发布：AE2 原生路径不经 setPlan（直接写字段），
     * 所以 plan packet 发送前再发布一次。
     */
    @Inject(
            method = "broadcastChanges",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/menu/AEBaseMenu;sendPacketToClient"
                            + "(Lappeng/core/network/ClientboundPacket;)V"
            ),
            remap = false
    )
    private void chexsonsaeutils$publishProvenanceBeforePlanPacket(CallbackInfo ci) {
        // 宽域账与 provenance 是否命中无关：只要有溢出键就要下发（图规划缺料真值），
        // 故先无条件同步，再处理 provenance。
        chexsonsaeutils$syncWideAmounts();
        if (this.result == null) {
            return;
        }
        var provenance = PlanningProvenance.peek(this.result);
        if (provenance == null) {
            return;
        }
        long deliveredMillis = provenance.elapsedMillisAt(System.nanoTime());
        this.chexsonsaeutils$provenanceMillis = deliveredMillis;
        this.chexsonsaeutils$provenanceSolver = provenance.solver();
        if (this.plan instanceof EnhancedCraftingPlanSummary enhanced) {
            enhanced.chexsonsaeutils$setProvenance(deliveredMillis, provenance.solver());
        }
    }

    /**
     * AE2 的 {@code fromJob} 只在 {@code simulation && !canEmitFor(key)} 时才算缺口，
     * 并把计划申报的缺料直接累进"可用数量"——于是海量级残缺计划里"网里有样板能产"的那批
     * 缺口既不上屏、又被当成存量报。这里在返回值落字段之前把缺口挪回 missing 那一栏，
     * packet 与 {@code this.plan} 拿到的都是还原后的概要。
     */
    @Redirect(
            method = "broadcastChanges",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/menu/me/crafting/CraftingPlanSummary;fromJob"
                            + "(Lappeng/api/networking/IGrid;Lappeng/api/networking/security/IActionSource;"
                            + "Lappeng/api/networking/crafting/ICraftingPlan;)"
                            + "Lappeng/menu/me/crafting/CraftingPlanSummary;"
            ),
            remap = false
    )
    private CraftingPlanSummary chexsonsaeutils$revealDeclaredMissing(
            appeng.api.networking.IGrid grid,
            IActionSource actionSource,
            ICraftingPlan job
    ) {
        var summary = PartialPlanMissingSummary.revealDeclaredMissing(
                CraftingPlanSummary.fromJob(grid, actionSource, job), job);
        git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.attach(summary, job);
        return summary;
    }

    /**
     * 每次重新规划时清空上一轮 provenance 与宽域账并重登记（HEAD——其他 mod 在
     * HEAD 取消 planJob 时，注入顺序按 mixin 优先级，我们仍有机会执行；
     * 真正的保底登记在构造 TAIL）。
     */
    @Inject(method = "planJob", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$resetProvenanceOnReplan(
            appeng.api.stacks.AEKey what, int amount,
            appeng.api.networking.crafting.CalculationStrategy strategy,
            CallbackInfoReturnable<Boolean> cir) {
        if (isClientSide()) {
            return;
        }
        this.chexsonsaeutils$provenanceMillis = -1L;
        this.chexsonsaeutils$provenanceSolver = "";
        this.chexsonsaeutils$wideAmounts = "";
        this.chexsonsaeutils$exactBytes = "";
        this.chexsonsaeutils$publishedPlan = null;
        this.chexsonsaeutils$publishedSummary = null;
        ConfirmMenuProvenanceRegistry.register(getPlayer(), this);
    }

    /**
     * 服务端：读本菜单当前计划自带的宽域载体，编码写进同步字段。无溢出键（含计划根本没有
     * 载体——AE2 原生计划、别的规划 mod 接管的计划）时置空串，客户端原样沿用 AE2 显示。
     */
    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$refreshCurrentPlanAmounts(CallbackInfo ci) {
        chexsonsaeutils$syncWideAmounts();
    }

    @Unique
    private void chexsonsaeutils$syncWideAmounts() {
        if (isClientSide()) {
            return;
        }
        if (this.result == chexsonsaeutils$publishedPlan && this.plan == chexsonsaeutils$publishedSummary) return;
        chexsonsaeutils$publishedPlan = this.result;
        chexsonsaeutils$publishedSummary = this.plan;
        if (this.plan != null && this.result != null) {
            git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.attach(this.plan, this.result);
            if (net.neoforged.fml.ModList.get().isLoaded("neoecoae"))
                git.chexson.chexsonsaeutils.integration.craftingdisplay.neoecoae.NeoEcoCraftingPreview.apply(this.plan, this.result);
            git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer.attachPatternTimes(this.plan, this.result);
        }
        this.chexsonsaeutils$exactBytes = this.result == null ? ""
                : WideAmountPlanCarrier.resolveBytes(this.result).exactValue().toString();
        boolean ownWidePlan = this.result instanceof WideAmountPlanCarrier
                || !WideAmountPlanCarrier.resolveWideLedgers(this.result).isEmpty()
                || !WideAmountPlanCarrier.resolveWideCrafts(this.result).isEmpty();
        WideLedgerSnapshot snapshot = ownWidePlan
                ? git.chexson.chexsonsaeutils.crafting.status.PlanSummaryAmounts.fromPlan(this.result).displaySnapshot()
                : WideLedgerSnapshot.empty();
        if (snapshot.isEmpty()) {
            this.chexsonsaeutils$wideAmounts = "";
            return;
        }
        this.chexsonsaeutils$wideAmounts =
                WidePlanLedgerCodec.encode(snapshot, getPlayer().level().registryAccess());
    }

    /** 客户端经 {@link WideAmountMenuSource} 读取 M1 同步字段（字段编译期不在 AE2 类上）。 */
    @Override
    public String chexsonsaeutils$syncedWideAmounts() {
        return this.chexsonsaeutils$wideAmounts == null ? "" : this.chexsonsaeutils$wideAmounts;
    }

    @Override
    public void chexsonsaeutils$setMenuProvenance(long millis, String solver) {
        this.chexsonsaeutils$provenanceMillis = millis;
        this.chexsonsaeutils$provenanceSolver = solver == null ? "" : solver;
    }

    @Override
    public long chexsonsaeutils$menuProvenanceMillis() {
        return this.chexsonsaeutils$provenanceMillis;
    }

    @Override
    public String chexsonsaeutils$menuProvenanceSolver() {
        return this.chexsonsaeutils$provenanceSolver == null ? "" : this.chexsonsaeutils$provenanceSolver;
    }

    @Override
    public CraftingContinuationMode getContinuationMode() {
        return chexsonsaeutils$continuationMode;
    }

    @Override
    public void setContinuationMode(CraftingContinuationMode mode) {
        chexsonsaeutils$continuationMode = normalize(mode);
    }

    @Override
    public void requestContinuationMode(CraftingContinuationMode mode) {
        setContinuationMode(mode);
        if (isClientSide()) {
            sendClientAction(chexsonSetContinuationMode, chexsonsaeutils$continuationMode);
        }
    }

    @Unique
    private void chexsonsaeutils$applyContinuationMode(CraftingContinuationMode mode) {
        setContinuationMode(mode);
    }

    @Unique
    private static CraftingContinuationMode normalize(CraftingContinuationMode mode) {
        return mode == null ? CraftingContinuationMode.defaultMode() : mode;
    }

    @Redirect(
            method = "startJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingPlan;simulation()Z"
            ),
            remap = false
    )
    private boolean chexsonsaeutils$allowSimulationSubmit(ICraftingPlan plan) {
        return plan.simulation() && chexsonsaeutils$continuationMode != CraftingContinuationMode.IGNORE_MISSING;
    }

    @Redirect(
            method = "startJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingService;submitJob(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/IActionSource;)Lappeng/api/networking/crafting/ICraftingSubmitResult;"
            ),
            remap = false
    )
    private ICraftingSubmitResult chexsonsaeutils$submitWithContinuationMode(
            ICraftingService craftingService,
            ICraftingPlan plan,
            ICraftingRequester requester,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    ) {
        ICraftingSubmitResult submitResult = CraftingContinuationSubmitBridge.withContinuationMode(
                chexsonsaeutils$continuationMode,
                () -> craftingService.submitJob(plan, requester, target, prioritizePower, src)
        );
        if (submitResult.errorCode() == CraftingSubmitErrorCode.CPU_OFFLINE) {
            AELog.warn("[chexsonsaeutils-submit] Confirmation rejected: targetType=%s, mode=%s, detail=%s",
                    target == null ? "auto" : target.getClass().getName(),
                    chexsonsaeutils$continuationMode, submitResult.errorDetail());
        }
        return submitResult;
    }
}
