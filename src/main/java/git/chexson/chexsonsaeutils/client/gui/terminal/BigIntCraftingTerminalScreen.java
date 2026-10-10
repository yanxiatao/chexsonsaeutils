package git.chexson.chexsonsaeutils.client.gui.terminal;

import appeng.api.client.AEKeyRendering;
import appeng.api.config.SortOrder;
import appeng.api.config.SortDir;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.client.gui.me.items.CraftingTermScreen;
import appeng.client.gui.me.common.Repo;
import appeng.client.gui.me.common.RepoSlot;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.me.common.PinnedKeys;
import appeng.client.gui.style.StyleManager;
import appeng.core.localization.ButtonToolTips;
import appeng.api.stacks.AmountFormat;
import appeng.menu.me.common.GridInventoryEntry;
import git.chexson.chexsonsaeutils.menu.terminal.BigIntCraftingTerminalMenu;
import git.chexson.chexsonsaeutils.terminal.display.*;
import git.chexson.chexsonsaeutils.terminal.sync.TerminalEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** 继承原版完整终端行为；精确数量直接在 tooltip 中完整显示。 */
public final class BigIntCraftingTerminalScreen extends CraftingTermScreen<BigIntCraftingTerminalMenu> {
    private final TerminalAmountFormatter formatter = new TerminalAmountFormatterImpl();
    private final Repo unscrolled = new Repo(() -> 0, this);
    private final Map<AEKey, TerminalEntry> exact = new HashMap<>();
    private final Map<AEKey, GridInventoryEntry> ordered = new HashMap<>();
    private final Map<TerminalEntry, String> labels = new HashMap<>();
    private final TerminalTooltipLines tooltipLines = new TerminalTooltipLinesImpl();
    private final Map<TerminalEntry, List<String>> fullLabels = new HashMap<>();
    private AEKey tooltipKey;
    private int tooltipOffset, tooltipRows, tooltipMaximum;
    private Set<GridInventoryEntry> lastNative = Set.of();
    private String lastSearch;
    private Object lastSettings;
    private List<net.minecraft.world.item.ItemStack> lastCells = List.of();
    private long lastRevision = -1;
    public BigIntCraftingTerminalScreen(BigIntCraftingTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StyleManager.loadStyleDoc("/screens/bigint_crafting_terminal.json"));
    }
    @Override public void init() {
        super.init();
        fullLabels.clear(); tooltipKey = null; tooltipOffset = 0; tooltipMaximum = 0;
        // 保留 RepoSlot 类型，使原版点击、拖动、滚轮、快捷键、固定行和配方浏览器识别继续生效。
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot instanceof RepoSlot original)
                menu.slots.set(i, new ExactRepoSlot(original.getRepoViewIndex(), slot.x, slot.y));
        }
        menu.requestSnapshot();
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        var settings = List.of(getSortBy(), getSortDir(), getSortDisplay(), Set.copyOf(getSortKeyTypes()),
                Set.copyOf(PinnedKeys.getPinnedKeys()), hasShiftDown());
        var nativeEntries = Set.copyOf(repo.getAllEntries());
        var cells = menu.getViewCells();
        boolean sameCells = cells.size() == lastCells.size();
        for (int i = 0; sameCells && i < cells.size(); i++)
            sameCells = net.minecraft.world.item.ItemStack.matches(cells.get(i), lastCells.get(i));
        if (lastRevision == menu.view().revision() && nativeEntries.equals(lastNative)
                && Objects.equals(lastSearch, repo.getSearchString()) && settings.equals(lastSettings) && sameCells) return;
        lastRevision = menu.view().revision(); lastNative = nativeEntries;
        lastSearch = repo.getSearchString(); lastSettings = settings;
        lastCells = cells.stream().map(net.minecraft.world.item.ItemStack::copy).toList();
        exact.clear(); menu.view().entries().forEach(e -> exact.put(e.key(), e));
        labels.keySet().retainAll(menu.view().entries());
        fullLabels.keySet().retainAll(menu.view().entries());
        if (getSortBy() != SortOrder.AMOUNT || !menu.view().active()) { ordered.clear(); return; }
        // 公共 Repo 负责原版搜索、类型、视图单元及固定行筛选；零滚动副本只提供完整筛选结果。
        unscrolled.setRowSize(repo.getRowSize());
        unscrolled.setSearchString(repo.getSearchString());
        unscrolled.setPartitionList(createPartitionList(cells));
        unscrolled.handleUpdate(true, new ArrayList<>(repo.getAllEntries()));
        var normal = new ArrayList<GridInventoryEntry>();
        int pinned = unscrolled.getPinnedEntries().size();
        int start = unscrolled.hasPinnedRow() ? unscrolled.getRowSize() : 0;
        for (int i = 0; i < unscrolled.size() - pinned; i++) normal.add(unscrolled.get(start + i));
        if (hasShiftDown() && !ordered.isEmpty()) {
            // Shift 下保留当前映射位置，仅更新对应条目的原生数据；原版 Repo 同时保留移除条目的空位。
            var byKey = new HashMap<AEKey, GridInventoryEntry>(); nativeEntries.forEach(e -> byKey.put(e.getWhat(), e));
            ordered.replaceAll((key, entry) -> byKey.getOrDefault(entry.getWhat(),
                    new GridInventoryEntry(entry.getSerial(), entry.getWhat(), 0, 0, false)));
            return;
        }
        var sorted = new ArrayList<>(normal);
        Comparator<GridInventoryEntry> comparator = (a, b) -> TerminalAmountComparison.compare(
                exact.get(a.getWhat()), a, exact.get(b.getWhat()), b);
        if (getSortDir() == SortDir.DESCENDING) comparator = comparator.reversed();
        sorted.sort(comparator);
        ordered.clear();
        for (int i = 0; i < normal.size(); i++) ordered.put(normal.get(i).getWhat(), sorted.get(i));
    }
    private final class ExactRepoSlot extends RepoSlot {
        private ExactRepoSlot(int index, int x, int y) { super(repo, index, x, y); }
        @Override public GridInventoryEntry getEntry() {
            var entry = super.getEntry();
            if (entry == null || PinnedKeys.isPinned(entry.getWhat())) return entry;
            return ordered.getOrDefault(entry.getWhat(), entry);
        }
    }
    @Override public void renderSlot(GuiGraphics graphics, Slot slot) {
        if (!(slot instanceof RepoSlot virtual)) { super.renderSlot(graphics, slot); return; }
        var entry = virtual.getEntry();
        if (entry == null || !menu.getLinkStatus().connected()) return;
        var precision = exact.get(entry.getWhat());
        try { AEKeyRendering.drawInGui(minecraft, graphics, slot.x, slot.y, entry.getWhat()); }
        catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("精确终端无法渲染键 {}", entry.getWhat(), failure);
            return;
        }
        if (entry.isCraftable() && (isViewOnlyCraftable() || entry.getStoredAmount() <= 0)) {
            StackSizeRenderer.renderSizeLabel(graphics, font, slot.x, slot.y, "+");
            return;
        }
        String label = precision != null && menu.view().active()
                ? labels.computeIfAbsent(precision, e -> formatter.compact(e.key(), e.amount()) + (e.unverified() == null ? "" : "?")) : "?";
        float scale = Math.min(config.isUseLargeFonts() ? 0.85f : 0.666f, 17f / Math.max(1, font.width(label)));
        graphics.pose().pushPose(); graphics.pose().translate(slot.x + 17, slot.y + 17, 200);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, label, -font.width(label), -font.lineHeight, 0xFFFFFF, true); graphics.pose().popPose();
        if (entry.isCraftable()) StackSizeRenderer.renderSizeLabel(graphics, font, slot.x - 11, slot.y - 11, "+", false);
    }
    @Override protected void renderGridInventoryEntryTooltip(GuiGraphics graphics, GridInventoryEntry entry, int x, int y) {
        var lines = new ArrayList<>(AEKeyRendering.getTooltip(entry.getWhat()));
        var precision = exact.get(entry.getWhat());
        if (!entry.getWhat().equals(tooltipKey)) {
            tooltipKey = entry.getWhat(); tooltipOffset = 0;
        }
        tooltipMaximum = 0;
        if (precision != null && menu.view().active()) {
            var quantity = fullLabels.computeIfAbsent(precision, e -> tooltipLines.wrap(
                    ButtonToolTips.StoredAmount.text(formatter.full(e.key(), e.amount())).getString(),
                    Math.max(64, Math.min(420, width - 24)), font::width));
            tooltipRows = Math.max(1, (height - 24) / font.lineHeight - lines.size() - 5);
            tooltipMaximum = Math.max(0, quantity.size() - tooltipRows);
            tooltipOffset = Math.min(tooltipOffset, tooltipMaximum);
            int end = Math.min(quantity.size(), tooltipOffset + tooltipRows);
            for (int i = tooltipOffset; i < end; i++) lines.add(Component.literal(quantity.get(i)));
            if (tooltipMaximum > 0) lines.add(Component.translatable("gui.chexsonsaeutils.bigint.tooltip_scroll",
                    tooltipOffset + 1, end, quantity.size()));
            if (precision.unverified() != null)
                lines.add(Component.translatable("gui.chexsonsaeutils.bigint.unverified", precision.unverified()));
            lines.add(Component.translatable("gui.chexsonsaeutils.bigint.detail_hint"));
        } else lines.add(Component.translatable(menu.view().revision() == 0
                ? "gui.chexsonsaeutils.bigint.loading" : "gui.chexsonsaeutils.bigint.read_failed"));
        if (entry.getRequestableAmount() > 0) lines.add(ButtonToolTips.RequestableAmount.text(
                entry.getWhat().formatAmount(entry.getRequestableAmount(), AmountFormat.FULL)));
        if (entry.isCraftable() && !(isViewOnlyCraftable() || entry.getStoredAmount() <= 0))
            lines.add(ButtonToolTips.Craftable.text());
        if (minecraft.options.advancedItemTooltips) lines.add(ButtonToolTips.Serial.text(entry.getSerial()));
        if (entry.getWhat() instanceof AEItemKey item) {
            var stack = item.getReadOnlyStack();
            graphics.renderTooltip(font, lines, stack.getTooltipImage(), stack, x, y);
        } else graphics.renderComponentTooltip(font, lines, x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!hasShiftDown() && menu.getCarried().isEmpty() && dy != 0 && tooltipMaximum > 0 && hoveredSlot instanceof RepoSlot virtual
                && isHovering(virtual.x, virtual.y, 16, 16, x, y)) {
            var entry = virtual.getEntry();
            if (entry != null && entry.getWhat().equals(tooltipKey)) {
                tooltipOffset = Math.max(0, Math.min(tooltipMaximum,
                        tooltipOffset - (int) Math.signum(dy) * Math.max(1, tooltipRows / 3)));
                return true;
            }
        }
        return super.mouseScrolled(x, y, dx, dy);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (hasControlDown() && key == GLFW.GLFW_KEY_C && hoveredSlot instanceof RepoSlot virtual) {
            var entry = virtual.getEntry(); var precision = entry == null ? null : exact.get(entry.getWhat());
            if (precision != null && menu.view().active()) {
                minecraft.keyboardHandler.setClipboard(precision.amount().unbounded() ? "∞" : precision.amount().value().toString());
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
