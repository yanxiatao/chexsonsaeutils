package git.chexson.chexsonsaeutils.client.gui.implementations;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportBatches;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixInteraction;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.AE2Button;
import appeng.menu.SlotSemantics;
import git.chexson.chexsonsaeutils.client.gui.matrix.AssemblerMatrixSlot;
import git.chexson.chexsonsaeutils.client.gui.matrix.GuiAssemblerMatrix;
import git.chexson.chexsonsaeutils.client.gui.matrix.MatrixText;
import git.chexson.chexsonsaeutils.client.integration.jei.JeiMachineRecipeTypeHint;
import git.chexson.chexsonsaeutils.client.integration.jei.JeiRuntimeHolder;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportReasonCode;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportStatus;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.directprocessing.DirectProcessingJeiImportPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 直连处理机的实体样板页与机器标记页，共用现有背景并通过左侧按钮切换。 */
public class AEDirectProcessingMachineScreen extends GuiAssemblerMatrix<AEDirectProcessingMachineMenu> {

    private static final int MATRIX_COLUMNS = 9;
    private static final int MATRIX_ROWS = 4;
    private static final int MATRIX_LEFT = 8;
    private static final int MATRIX_TOP = 31;
    /** 三行信息由样式文档定位，这里只负责填内容。 */
    private static final String RECIPE_TYPES_TEXT = "recipe_types";
    private static final String PATTERN_COUNT_TEXT = "pattern_count";
    private static final String PATTERN_STATUS_TEXT = "pattern_status";
    private static final String PLAYER_INVENTORY_TITLE_TEXT = "player_inventory_title";

    private final AE2Button importJeiButton;
    private List<JeiMachineRecipeTypeHint> currentJeiHints = List.of();
    private final java.util.Map<MachineIdentity, List<JeiMachineRecipeTypeHint>> hintsByMachine = new java.util.LinkedHashMap<>();
    private final AE2Button patternsButton;
    private final AE2Button machinesButton;
    private int hintRefreshFrames;
    private final git.chexson.chexsonsaeutils.client.gui.matrix.MachineMarkerClientView machineView =
            new git.chexson.chexsonsaeutils.client.gui.matrix.MachineMarkerClientView();
    private boolean suppressJeiTooltipUntilMouseLeave;
    private boolean dismissJeiTooltipThisFrame;
    private TooltipSource currentFrameTooltipSource = TooltipSource.NONE;
    private int refreshedChangeCount = -1;
    private int supportedPatterns;
    private int unsupportedPatterns;
    private int needsConfigPatterns;
    private int unsafePatterns;

    public AEDirectProcessingMachineScreen(
            AEDirectProcessingMachineMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(
                menu,
                playerInventory,
                title,
                StyleManager.loadStyleDoc("/screens/ae_direct_processing_machine.json"),
                menu.matrixLink(),
                MATRIX_COLUMNS,
                MATRIX_ROWS,
                MATRIX_LEFT,
                MATRIX_TOP,
                MatrixSearchMatchers.create()
        );
        patternsButton = widgets.addButton("patterns_page",
                Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.patterns_page"),
                button -> menu.selectMachinePage(false));
        machinesButton = widgets.addButton("machines_page",
                Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.machines_page"),
                button -> menu.selectMachinePage(true));
        this.importJeiButton = widgets.addButton(
                "jei_import",
                Component.literal("JEI"),
                button -> importCurrentJeiHints()
        );
    }

    @Override
    protected PatternMatrixClientView matrixViewForDisplay() {
        return menu.machinePage ? machineView.update(menu.getSlots(SlotSemantics.MACHINE_INPUT).stream()
                .map(Slot::getItem).toList()) : menu.matrixView();
    }

    @Override
    protected long matrixActionId() {
        return menu.machinePage ? PatternMatrixInteraction.MATRIX_MACHINE_ID : PatternMatrixInteraction.MATRIX_PATTERN_ID;
    }

    @Override
    protected Component statusLine() {
        if (!menu.machinePage) return super.statusLine();
        var view = matrixViewForDisplay();
        return view.filterActive()
                ? Component.translatable("gui.chexsonsaeutils.matrix.machine_count_matched",
                        view.matchCount(), view.getNonEmptySlots(), view.getTotalSlots())
                : Component.translatable("gui.chexsonsaeutils.matrix.machine_count", view.getNonEmptySlots(), view.getTotalSlots());
    }

    @Override
    protected void init() {
        super.init();
        // 背包标题那一行改放本机的样板状态计数
        setTextHidden(PLAYER_INVENTORY_TITLE_TEXT, true);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        refreshStatusCounts();
        setTextHidden(RECIPE_TYPES_TEXT, true);
        setTextHidden("binding_slot", true);
        patternsButton.active = menu.machinePage;
        machinesButton.active = !menu.machinePage;
        importJeiButton.visible = menu.machinePage;
        setTextHidden(PATTERN_COUNT_TEXT, false);
        setTextContent(PATTERN_COUNT_TEXT, statusLine());
        setTextHidden(PATTERN_STATUS_TEXT, menu.machinePage);
        if (!menu.machinePage) setTextContent(PATTERN_STATUS_TEXT, getStatusLine());
        if (hintRefreshFrames-- <= 0) {
            currentJeiHints = collectCurrentJeiHints();
            hintRefreshFrames = 20;
        }
        importJeiButton.active = !currentJeiHints.isEmpty();
        importJeiButton.setMessage(Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.jei_import_button",
                currentJeiHints.size()
        ));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        currentFrameTooltipSource = resolveTooltipSource(mouseX, mouseY);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        try {
            if (currentFrameTooltipSource == TooltipSource.RECIPE_TYPES) {
                guiGraphics.renderTooltip(font, font.split(menu.getSummaryLine(), Math.min(280, width - 16)), mouseX, mouseY);
                return;
            }
            if (!shouldRenderJeiTooltip(mouseX, mouseY)) {
                return;
            }
            List<Component> tooltip = buildJeiTooltip();
            if (!tooltip.isEmpty()) {
                guiGraphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
            }
        } finally {
            dismissJeiTooltipThisFrame = false;
            currentFrameTooltipSource = TooltipSource.NONE;
        }
    }

    @Override
    public void drawFG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(graphics, offsetX, offsetY, mouseX, mouseY);
        if (!menu.machinePage) return;
        int x = 8;
        int y = imageHeight - 95;
        int available = imageWidth - x - 8;
        String summary = menu.getSummaryLine().getString();
        if (font.width(summary) > available) {
            summary = font.plainSubstrByWidth(summary, Math.max(0, available - font.width("…"))) + "…";
        }
        graphics.enableScissor(leftPos + x, topPos + y, leftPos + x + available, topPos + y + font.lineHeight);
        graphics.drawString(font, summary, x, y, 0x404040, false);
        graphics.disableScissor();
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> tooltip = super.getTooltipFromContainerItem(stack);
        if (currentFrameTooltipSource != TooltipSource.SLOT_ITEM) {
            return tooltip;
        }
        Slot slot = getSlotUnderMouse();
        if (menu.machinePage || !(slot instanceof AssemblerMatrixSlot cell)) {
            return tooltip;
        }
        int globalSlot = cell.getActuallySlot();
        return MatrixText.shiftDetailTooltipDistinct(
                tooltip,
                hasShiftDown(),
                slot,
                stack,
                globalSlot,
                () -> patternStatusLines(globalSlot)
        );
    }

    private List<Component> patternStatusLines(int globalSlot) {
        PatternMatrixClientView view = menu.matrixView();
        MachineSupportStatus status = statusAt(view.statusCodeAt(globalSlot));
        MachineSupportReasonCode reason = reasonAt(view.reasonCodeAt(globalSlot));
        List<Component> lines = new ArrayList<>(2);
        lines.add(Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.pattern_status_line",
                localizedStatus(status)
        ));
        if (reason != MachineSupportReasonCode.NONE) {
            lines.add(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.pattern_reason_line",
                    localizedReason(reason)
            ));
        }
        return List.copyOf(lines);
    }

    /** 全量样板的状态计数：只在视图变化时重扫一遍，视图没变就复用上一轮结果。 */
    private void refreshStatusCounts() {
        PatternMatrixClientView view = menu.matrixView();
        if (view.changeCount() == refreshedChangeCount) {
            return;
        }
        refreshedChangeCount = view.changeCount();
        int supported = 0;
        int unsupported = 0;
        int needsConfig = 0;
        int unsafe = 0;
        for (int slot = 0; slot < view.getTotalSlots(); slot++) {
            if (!view.hasDisplayStack(slot)) {
                continue;
            }
            switch (statusAt(view.statusCodeAt(slot))) {
                case SUPPORTED_EXPLICIT, SUPPORTED_CONFIG, SUPPORTED_GENERIC -> supported++;
                case NEEDS_CONFIG_MAPPING -> needsConfig++;
                case UNSAFE_DYNAMIC -> unsafe++;
                case PENDING_REFRESH -> { }
                default -> unsupported++;
            }
        }
        supportedPatterns = supported;
        unsupportedPatterns = unsupported;
        needsConfigPatterns = needsConfig;
        unsafePatterns = unsafe;
    }

    private Component getStatusLine() {
        return Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.status_counts",
                supportedPatterns,
                unsupportedPatterns,
                needsConfigPatterns,
                unsafePatterns
        );
    }

    private static MachineSupportStatus statusAt(int statusCode) {
        MachineSupportStatus[] values = MachineSupportStatus.values();
        return statusCode >= 0 && statusCode < values.length
                ? values[statusCode]
                : MachineSupportStatus.UNSUPPORTED_UNREADABLE;
    }

    private static MachineSupportReasonCode reasonAt(int reasonCode) {
        MachineSupportReasonCode[] values = MachineSupportReasonCode.values();
        return reasonCode >= 0 && reasonCode < values.length
                ? values[reasonCode]
                : MachineSupportReasonCode.NONE;
    }

    private boolean shouldRenderJeiTooltip(int mouseX, int mouseY) {
        if (!importJeiButton.visible || !importJeiButton.isMouseOver(mouseX, mouseY)) {
            suppressJeiTooltipUntilMouseLeave = false;
            return false;
        }
        if (dismissJeiTooltipThisFrame
                || suppressJeiTooltipUntilMouseLeave
                || currentFrameTooltipSource != TooltipSource.JEI_BUTTON) {
            return false;
        }
        return true;
    }

    private TooltipSource resolveTooltipSource(int mouseX, int mouseY) {
        Slot slot = getSlotUnderMouse();
        if (slot != null && slot.hasItem()) {
            return TooltipSource.SLOT_ITEM;
        }
        if (menu.machinePage && mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 7
                && mouseY >= topPos + imageHeight - 95 && mouseY < topPos + imageHeight - 95 + font.lineHeight) {
            return TooltipSource.RECIPE_TYPES;
        }
        if (importJeiButton.isMouseOver(mouseX, mouseY)) {
            return TooltipSource.JEI_BUTTON;
        }
        return TooltipSource.NONE;
    }

    private void importCurrentJeiHints() {
        try {
            collectCurrentJeiHints();
            List<MachineRecipeConfigImportRequest> batches = new ArrayList<>();
            for (var entry : hintsByMachine.entrySet()) {
                MachineRecipeConfigImportRequest request = buildJeiImportRequest(entry.getKey(), entry.getValue());
                if (request != null) batches.addAll(
                        MachineRecipeImportBatches.split(
                                minecraft.level.registryAccess(), request,
                                JeiRuntimeHolder.collectSignatureHintsForMachine(
                                        entry.getKey().machineItemId(), entry.getKey().blockId())));
            }
            for (MachineRecipeConfigImportRequest batch : batches) {
                PacketDistributor.sendToServer(new DirectProcessingJeiImportPayload(menu.getContainerIdForPayload(), batch));
            }
        } catch (RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().error("无法构建直连处理机 JEI 导入包", exception);
            getPlayer().displayClientMessage(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.import_failed"), false);
        }
        dismissJeiTooltipThisFrame = true;
        clearJeiButtonFocus();
        suppressJeiTooltipUntilMouseLeave = true;
    }

    private void clearJeiButtonFocus() {
        importJeiButton.setFocused(false);
        setFocused((GuiEventListener) null);
    }

    private List<JeiMachineRecipeTypeHint> collectCurrentJeiHints() {
        hintsByMachine.clear();
        if (!JeiRuntimeHolder.hasRuntime()) return List.of();
        java.util.Map<ResourceLocation, JeiMachineRecipeTypeHint> types = new java.util.LinkedHashMap<>();
        for (Slot slot : menu.getSlots(SlotSemantics.MACHINE_INPUT)) {
            MachineIdentity identity = MachineIdentity.fromBindingStack(slot.getItem());
            if (identity == null || hintsByMachine.containsKey(identity)) continue;
            List<JeiMachineRecipeTypeHint> hints = JeiRuntimeHolder.collectVisibleHintsForMachine(
                    identity.machineItemId(), identity.blockId());
            hintsByMachine.put(identity, hints);
            for (JeiMachineRecipeTypeHint hint : hints) types.putIfAbsent(hint.recipeTypeId(), hint);
        }
        return List.copyOf(types.values());
    }

    private MachineRecipeConfigImportRequest buildJeiImportRequest(
            MachineIdentity identity, List<JeiMachineRecipeTypeHint> hints
    ) {
        if (hints.isEmpty()) return null;
        Set<ResourceLocation> recipeTypeIds = new LinkedHashSet<>();
        int defaultTicks = 20;
        for (JeiMachineRecipeTypeHint hint : hints) {
            if (hint == null || hint.recipeTypeId() == null) {
                continue;
            }
            recipeTypeIds.add(hint.recipeTypeId());
            defaultTicks = Math.max(1, hint.defaultTicks());
        }
        if (recipeTypeIds.isEmpty()) {
            return null;
        }
        return new MachineRecipeConfigImportRequest(
                identity.machineItemId(),
                identity.blockId(),
                List.copyOf(recipeTypeIds),
                defaultTicks,
                "generic",
                "any",
                true,
                "[]"
        );
    }

    private List<Component> buildJeiTooltip() {
        List<Component> tooltip = new ArrayList<>();
        if (!JeiRuntimeHolder.hasRuntime()) {
            tooltip.add(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.jei_unavailable"
            ));
            return tooltip;
        }
        if (currentJeiHints.isEmpty()) {
            tooltip.add(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.jei_no_candidates"
            ));
            return tooltip;
        }
        tooltip.add(Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.jei_candidates_title"
        ));
        int limit = Math.min(3, currentJeiHints.size());
        for (int index = 0; index < limit; index++) {
            tooltip.add(Component.literal(currentJeiHints.get(index).recipeTypeId().toString()));
        }
        if (currentJeiHints.size() > limit) {
            tooltip.add(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.jei_candidates_more",
                    currentJeiHints.size() - limit
            ));
        }
        return tooltip;
    }

    private static Component localizedStatus(MachineSupportStatus status) {
        String key = status == null ? "unsupported_unreadable" : status.name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.status." + key);
    }

    private static Component localizedReason(MachineSupportReasonCode reason) {
        String key = reason == null ? "malformed_data" : reason.name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.reason." + key);
    }

    private enum TooltipSource {
        NONE,
        SLOT_ITEM,
        RECIPE_TYPES,
        JEI_BUTTON
    }
}
