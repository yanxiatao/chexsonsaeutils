package git.chexson.chexsonsaeutils.menu.implementations;

import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportReasonCode;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AEDirectProcessingMachineMenu extends UpgradeableMenu<AEDirectProcessingMachineBlockEntity> {

    public static final MenuType<AEDirectProcessingMachineMenu> TYPE = MenuTypeBuilder
            .create(AEDirectProcessingMachineMenu::new, AEDirectProcessingMachineBlockEntity.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":ae_direct_processing_machine")
            ));

    private static final String ACTION_NEXT_PAGE = "nextPage";
    private static final String ACTION_PREVIOUS_PAGE = "previousPage";
    private static final String ACTION_GOTO_PAGE = "gotoPage";
    private static final String ACTION_IMPORT_JEI_HINTS = "importJeiHints";

    @GuiSync(40)
    public String detectedRecipeTypeSummary = "-";
    @GuiSync(41)
    public int visiblePatternSlots;
    @GuiSync(46)
    public boolean waitingOutputReturn;
    @GuiSync(49)
    public int pageIndex;
    @GuiSync(50)
    public int pageCount = 1;
    @GuiSync(51)
    public int visibleSupportedPatterns;
    @GuiSync(52)
    public int visibleUnsupportedPatterns;
    @GuiSync(53)
    public int visibleNeedsConfigPatterns;
    @GuiSync(54)
    public int visibleUnsafePatterns;
    @GuiSync(55)
    public String visiblePatternStatusSnapshot = "";

    private String parsedPatternStatusSnapshot = "";
    private MachineSupportStatus[] visiblePatternStatuses = new MachineSupportStatus[0];
    private MachineSupportReasonCode[] visiblePatternReasons = new MachineSupportReasonCode[0];

    public AEDirectProcessingMachineMenu(
            int id,
            Inventory playerInventory,
            AEDirectProcessingMachineBlockEntity host
    ) {
        super(TYPE, id, playerInventory, host);
        registerClientAction(ACTION_NEXT_PAGE, this::nextPageOnServer);
        registerClientAction(ACTION_PREVIOUS_PAGE, this::previousPageOnServer);
        registerClientAction(ACTION_GOTO_PAGE, Integer.class, this::gotoPageOnServer);
        registerClientAction(ACTION_IMPORT_JEI_HINTS, MachineRecipeConfigImportRequest.class, this::importJeiHintsOnServer);
    }

    @Override
    protected void setupInventorySlots() {
        addSlot(new MachineBindingSlot(getHost(), 0), SlotSemantics.MACHINE_INPUT);
        var patternInventory = getHost().getTerminalPatternInventory();
        for (int slot = 0; slot < getHost().getVisiblePatternSlots(); slot++) {
            addSlot(new DirectProcessingPatternSlot(getHost(), patternInventory, slot, slot), SlotSemantics.ENCODED_PATTERN);
        }
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            detectedRecipeTypeSummary = getHost().getDetectedRecipeTypeSummaryForMenu();
            visiblePatternSlots = getHost().getVisiblePatternSlots();
            waitingOutputReturn = getHost().isWaitingForOutputReturn();
            pageIndex = getHost().getPageIndex();
            pageCount = getHost().getPageCount();
            visibleSupportedPatterns = countVisibleSupportedPatterns();
            visibleUnsupportedPatterns =
                    getHost().countVisiblePatternStatusForMenu(MachineSupportStatus.UNSUPPORTED_UNREADABLE);
            visibleNeedsConfigPatterns =
                    getHost().countVisiblePatternStatusForMenu(MachineSupportStatus.NEEDS_CONFIG_MAPPING);
            visibleUnsafePatterns =
                    getHost().countVisiblePatternStatusForMenu(MachineSupportStatus.UNSAFE_DYNAMIC);
            visiblePatternStatusSnapshot = getHost().getVisiblePatternStatusSnapshotForMenu();
        }
        standardDetectAndSendChanges();
    }

    public void nextPage() {
        if (isClientSide()) {
            sendClientAction(ACTION_NEXT_PAGE);
        } else {
            nextPageOnServer();
        }
    }

    public void previousPage() {
        if (isClientSide()) {
            sendClientAction(ACTION_PREVIOUS_PAGE);
        } else {
            previousPageOnServer();
        }
    }

    public void gotoPage(int pageIndex) {
        if (isClientSide()) {
            sendClientAction(ACTION_GOTO_PAGE, pageIndex);
        } else {
            gotoPageOnServer(pageIndex);
        }
    }

    public boolean canGoToPreviousPage() {
        return pageIndex > 0;
    }

    public boolean canGoToNextPage() {
        return pageIndex + 1 < pageCount;
    }

    public void importJeiHints(MachineRecipeConfigImportRequest request) {
        if (request == null) {
            return;
        }
        if (isClientSide()) {
            sendClientAction(ACTION_IMPORT_JEI_HINTS, request);
        } else {
            importJeiHintsOnServer(request);
        }
    }

    public Component getSummaryLine() {
        return Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.recipe_types",
                detectedRecipeTypeSummary
        );
    }

    public Component getPageLine() {
        int visibleSlots = Math.max(1, visiblePatternSlots);
        return Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.page_line",
                pageIndex + 1,
                pageCount,
                pageIndex * visibleSlots + 1,
                Math.min(getHost().getTotalPatternSlots(), (pageIndex + 1) * visibleSlots),
                getHost().getTotalPatternSlots()
        );
    }

    public Component getVisiblePatternStatusLine() {
        return Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.visible_status",
                visibleSupportedPatterns,
                visibleUnsupportedPatterns,
                visibleNeedsConfigPatterns,
                visibleUnsafePatterns
        );
    }

    public int getPatternPageSlotIndex(Slot slot) {
        return getSlots(SlotSemantics.ENCODED_PATTERN).indexOf(slot);
    }

    public List<Component> getPatternSlotTooltip(int pageSlotIndex) {
        ensureVisiblePatternSnapshotParsed();
        int visibleSlots = Math.max(1, visiblePatternSlots);
        int globalSlot = pageIndex * visibleSlots + pageSlotIndex;
        MachineSupportStatus status = getVisiblePatternStatus(pageSlotIndex);
        MachineSupportReasonCode reason = getVisiblePatternReason(pageSlotIndex);
        List<Component> lines = new ArrayList<>(2);
        lines.add(Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.pattern_status_line",
                localizedStatus(status)
        ));
        Component detailLine = reason == MachineSupportReasonCode.NONE
                ? Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.slot_summary_line",
                globalSlot + 1,
                pageIndex + 1,
                pageCount
        )
                : Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.pattern_reason_line",
                localizedReason(reason)
        );
        lines.add(detailLine);
        return List.copyOf(lines);
    }

    private MachineSupportStatus getVisiblePatternStatus(int pageSlotIndex) {
        if (pageSlotIndex < 0 || pageSlotIndex >= visiblePatternStatuses.length) {
            return MachineSupportStatus.UNSUPPORTED_UNREADABLE;
        }
        MachineSupportStatus status = visiblePatternStatuses[pageSlotIndex];
        return status == null ? MachineSupportStatus.UNSUPPORTED_UNREADABLE : status;
    }

    private MachineSupportReasonCode getVisiblePatternReason(int pageSlotIndex) {
        if (pageSlotIndex < 0 || pageSlotIndex >= visiblePatternReasons.length) {
            return MachineSupportReasonCode.PATTERN_DECODE_FAILED;
        }
        MachineSupportReasonCode reason = visiblePatternReasons[pageSlotIndex];
        return reason == null ? MachineSupportReasonCode.PATTERN_DECODE_FAILED : reason;
    }

    private void ensureVisiblePatternSnapshotParsed() {
        String snapshot = visiblePatternStatusSnapshot == null ? "" : visiblePatternStatusSnapshot;
        if (snapshot.equals(parsedPatternStatusSnapshot)) {
            return;
        }
        parsedPatternStatusSnapshot = snapshot;
        if (snapshot.isBlank()) {
            visiblePatternStatuses = new MachineSupportStatus[0];
            visiblePatternReasons = new MachineSupportReasonCode[0];
            return;
        }
        String[] entries = snapshot.split(";");
        visiblePatternStatuses = new MachineSupportStatus[entries.length];
        visiblePatternReasons = new MachineSupportReasonCode[entries.length];
        for (int index = 0; index < entries.length; index++) {
            String[] parts = entries[index].split(":", 2);
            visiblePatternStatuses[index] = parseStatus(parts.length > 0 ? parts[0] : "");
            visiblePatternReasons[index] = parseReason(parts.length > 1 ? parts[1] : "");
        }
    }

    private static MachineSupportStatus parseStatus(String value) {
        try {
            int ordinal = Integer.parseInt(value);
            MachineSupportStatus[] values = MachineSupportStatus.values();
            return ordinal >= 0 && ordinal < values.length
                    ? values[ordinal]
                    : MachineSupportStatus.UNSUPPORTED_UNREADABLE;
        } catch (NumberFormatException ignored) {
            return MachineSupportStatus.UNSUPPORTED_UNREADABLE;
        }
    }

    private static MachineSupportReasonCode parseReason(String value) {
        try {
            int ordinal = Integer.parseInt(value);
            MachineSupportReasonCode[] values = MachineSupportReasonCode.values();
            return ordinal >= 0 && ordinal < values.length
                    ? values[ordinal]
                    : MachineSupportReasonCode.PATTERN_DECODE_FAILED;
        } catch (NumberFormatException ignored) {
            return MachineSupportReasonCode.PATTERN_DECODE_FAILED;
        }
    }

    private int countVisibleSupportedPatterns() {
        return getHost().countVisiblePatternStatusForMenu(MachineSupportStatus.SUPPORTED_EXPLICIT)
                + getHost().countVisiblePatternStatusForMenu(MachineSupportStatus.SUPPORTED_CONFIG)
                + getHost().countVisiblePatternStatusForMenu(MachineSupportStatus.SUPPORTED_GENERIC);
    }

    private void nextPageOnServer() {
        getHost().nextPage();
        broadcastChanges();
    }

    private void previousPageOnServer() {
        getHost().previousPage();
        broadcastChanges();
    }

    private void gotoPageOnServer(int pageIndex) {
        getHost().setActivePage(pageIndex);
        broadcastChanges();
    }

    private void importJeiHintsOnServer(MachineRecipeConfigImportRequest request) {
        if (getHost().importUserConfigMappingForMenu(request)) {
            broadcastChanges();
        }
    }

    private static final class MachineBindingSlot extends RestrictedInputSlot {

        private final AEDirectProcessingMachineBlockEntity host;

        private MachineBindingSlot(AEDirectProcessingMachineBlockEntity host, int slotIndex) {
            super(PlacableItemType.INSCRIBER_PLATE, host.getMachineBindingInventory(), slotIndex);
            this.host = host;
            setStackLimit(1);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return host.isSupportedBindingMachine(stack);
        }

        @Override
        public List<Component> getCustomTooltip(ItemStack carried) {
            if (!getItem().isEmpty()) {
                return null;
            }
            return List.of(Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.binding_slot_tooltip"));
        }
    }

    private static final class DirectProcessingPatternSlot extends RestrictedInputSlot {

        private final AEDirectProcessingMachineBlockEntity host;
        private final int pageSlotIndex;

        private DirectProcessingPatternSlot(
                AEDirectProcessingMachineBlockEntity host,
                appeng.api.inventories.InternalInventory inventory,
                int slotIndex,
                int pageSlotIndex
        ) {
            super(PlacableItemType.MOLECULAR_ASSEMBLER_PATTERN, inventory, slotIndex);
            this.host = host;
            this.pageSlotIndex = pageSlotIndex;
            setStackLimit(1);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return host.isProcessingPattern(stack);
        }

        @Override
        public List<Component> getCustomTooltip(ItemStack carried) {
            AEDirectProcessingMachineBlockEntity menuHost = this.host;
            int globalSlot = menuHost.toGlobalPatternSlotIndex(pageSlotIndex);
            ItemStack stack = menuHost.getPatternAt(globalSlot);
            if (!stack.isEmpty()) {
                return null;
            }
            return List.of(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.pattern_slot_empty_tooltip"
            ));
        }
    }

    private static Component localizedStatus(MachineSupportStatus status) {
        String key = status == null ? "unsupported_unreadable" : status.name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.status." + key);
    }

    private static Component localizedReason(MachineSupportReasonCode reason) {
        String key = reason == null ? "malformed_data" : reason.name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.reason." + key);
    }
}
