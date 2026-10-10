package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.StyleManager;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.solve.MenuProvenanceHost;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance;
import git.chexson.chexsonsaeutils.bigint.WideAmountMenuSource;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummary;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.text.NumberFormat;

/**
 * 我们的合成确认屏（S1）：AE2 {@code CraftConfirmScreen} 的子类，沿用 AE2 的
 * {@code craft_confirm.json} 风格与全部控件（背景、按钮、滚动条、缺料红底、CPU 切换、
 * 开始/取消/重规划、提交都走父类与 AE2 菜单原逻辑），只做两件事——把超 {@code long}
 * 的缺料真值经 M1 同步字段带过来交给 renderer mixin 显示，以及把标题的"字节数"行
 * 换成规划溯源（耗时 / 字节 / 求解档位）。
 *
 * <p>为什么继承而非新造：注入 {@code CraftConfirmScreen} 的 mixin（continuation、parallelCpu）
 * 必须继续生效，继承父类字节码即自动继承这些注入行为。
 *
 * <p>标题与宽域快照都在 {@link #updateBeforeRender()} 的父类调用之后改写：AE2 每帧重设
 * {@code TEXT_ID_DIALOG_TITLE}，后写覆盖前写，因此不需要注入 AE2 原版屏。
 */
public final class WideAmountCraftConfirmScreen extends CraftConfirmScreen implements WideAmountSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    private String cachedRaw;
    @Nullable
    private WideLedgerSnapshot cachedSnapshot;
    private boolean loggedMissingProvenance = false;

    public WideAmountCraftConfirmScreen(CraftConfirmMenu menu, Inventory playerInventory, Component title) {
        // 与 AE2 注册自己的确认屏用的是同一份风格文档（assets/ae2/screens/craft_confirm.json，
        // 本仓库已同名覆盖为 AE2 风格 + guis/craftingreport.png），控件名一致，无需自绘贴图。
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/craft_confirm.json"));
    }

    @Override
    protected void updateBeforeRender() {
        // 先让父类（含 client mixin 的 TAIL 注入）跑完标题、CPU 状态、滚动范围，再覆写我们的标题。
        super.updateBeforeRender();
        chexsonsaeutils$refreshTitle();
        chexsonsaeutils$refreshWideSnapshot();
    }

    /**
     * 标题溯源：优先菜单同步字段（跨规划 mod 可靠），回退 summary 自带分量（单 mod 路径）。
     * 两处都没有求解档位时保持 AE2 原标题，不编一个假的耗时出来。
     */
    private void chexsonsaeutils$refreshTitle() {
        CraftingPlanSummary plan = getMenu().getPlan();
        if (getMenu() instanceof git.chexson.chexsonsaeutils.crafting.progress.PlanningProgressMenu progress
                && !progress.planningPhase().isEmpty()) {
            Component phase = Component.translatable("gui.chexsonsaeutils.planning.phase." + progress.planningPhase().toLowerCase(java.util.Locale.ROOT));
            Component status = progress.planningPhase().equals("EXTERNAL") || progress.planningPhase().equals("NATIVE")
                    ? Component.translatable("gui.chexsonsaeutils.planning.indeterminate", phase,
                            NumberFormat.getInstance().format(progress.planningElapsedMillis()))
                    : Component.translatable("gui.chexsonsaeutils.planning.progress", phase,
                            NumberFormat.getInstance().format(progress.planningSteps()),
                            NumberFormat.getInstance().format(progress.planningElapsedMillis()));
            setTextContent(AEBaseScreen.TEXT_ID_DIALOG_TITLE, GuiText.CraftingPlan.text(status));
            return;
        }
        if (plan == null) return;
        java.math.BigInteger exactBytes = git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes.resolve(plan,
                getMenu() instanceof WideAmountMenuSource source ? source.chexsonsaeutils$syncedExactBytes() : "");
        long millis = -1L;
        String solverId = "";
        if (getMenu() instanceof MenuProvenanceHost host
                && !host.chexsonsaeutils$menuProvenanceSolver().isEmpty()) {
            millis = host.chexsonsaeutils$menuProvenanceMillis();
            solverId = host.chexsonsaeutils$menuProvenanceSolver();
        } else if (plan instanceof EnhancedCraftingPlanSummary enhanced
                && !enhanced.chexsonsaeutils$solver().isEmpty()) {
            millis = enhanced.chexsonsaeutils$planningMillis();
            solverId = enhanced.chexsonsaeutils$solver();
        }
        if (solverId.isEmpty()) {
            if (!loggedMissingProvenance) {
                loggedMissingProvenance = true;
                LOGGER.debug("Craft confirm summary carries no planning provenance; keeping AE2 title");
            }
            if (!exactBytes.equals(java.math.BigInteger.valueOf(plan.getUsedBytes()))) {
                setTextContent(AEBaseScreen.TEXT_ID_DIALOG_TITLE, GuiText.CraftingPlan.text(
                        GuiText.BytesUsed.text(NumberFormat.getInstance().format(
                                exactBytes))));
            }
            return;
        }
        Component solver = Component.translatable(PlanningProvenance.solverTranslationKey(solverId));
        Component provenance = Component.translatable(
                "gui.chexsonsaeutils.crafting_plan.provenance",
                NumberFormat.getInstance().format(Math.max(0L, millis)),
                chexsonsaeutils$formatBytes(exactBytes),
                solver);
        setTextContent(AEBaseScreen.TEXT_ID_DIALOG_TITLE, GuiText.CraftingPlan.text(provenance));
    }

    private static String chexsonsaeutils$formatBytes(java.math.BigInteger bytes) {
        String[] units = { "", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q" };
        java.math.BigDecimal value = new java.math.BigDecimal(bytes);
        int unit = 0;
        while (value.compareTo(java.math.BigDecimal.valueOf(1000)) >= 0 && unit < units.length - 1) {
            value = value.movePointLeft(3);
            unit++;
        }
        return (unit == 0 ? bytes.toString()
                : value.round(new java.math.MathContext(4)).toString()) + units[unit] + " B";
    }

    @Override
    protected void renderTooltip(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
        if (getMenu() instanceof git.chexson.chexsonsaeutils.menu.crafting.WideCraftConfirmMenu exact
                && !exact.requestedAmount.isEmpty() && mouseX >= leftPos && mouseX < leftPos + imageWidth
                && mouseY >= topPos && mouseY < topPos + 17) {
            var lines = new java.util.ArrayList<Component>();
            lines.add(Component.translatable("gui.chexsonsaeutils.wide_request.order_amount", exact.requestedAmount));
            if (!exact.deliveryAmount.isEmpty())
                lines.add(Component.translatable("gui.chexsonsaeutils.wide_request.delivery_amount", exact.deliveryAmount));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    @Override
    @Nullable
    public WideLedgerSnapshot chexsonsaeutils$wideSnapshot() {
        return cachedSnapshot == null || cachedSnapshot.isEmpty() ? null : cachedSnapshot;
    }

    /**
     * 惰性解析：只有 M1 同步字段变化时才重算快照，避免每帧解析字符串。
     * 字段为空或取不到注册表（客户端尚未进世界）时清空缓存，renderer 退回 AE2 显示。
     */
    private void chexsonsaeutils$refreshWideSnapshot() {
        if (!(getMenu() instanceof WideAmountMenuSource source)) {
            cachedRaw = null;
            cachedSnapshot = null;
            return;
        }
        String raw = source.chexsonsaeutils$syncedWideAmounts();
        if (raw == null) {
            raw = "";
        }
        if (raw.isEmpty()) {
            cachedRaw = raw;
            cachedSnapshot = null;
            return;
        }
        // 字符串没变且已有缓存：直接复用。
        if (raw.equals(cachedRaw) && cachedSnapshot != null) {
            return;
        }
        RegistryAccess registries = chexsonsaeutils$registries();
        if (registries == null) {
            return;
        }
        cachedSnapshot = WidePlanLedgerCodec.decode(raw, registries);
        cachedRaw = raw;
    }

    @Nullable
    private RegistryAccess chexsonsaeutils$registries() {
        return this.minecraft != null && this.minecraft.level != null
                ? this.minecraft.level.registryAccess()
                : null;
    }
}
