package git.chexson.chexsonsaeutils.menu.implementations;

import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

public class HighCapacityCraftingMachineMenu extends AEBaseMenu {

    public static final MenuType<HighCapacityCraftingMachineMenu> TYPE = MenuTypeBuilder
            .create(HighCapacityCraftingMachineMenu::new, HighCapacityCraftingMachineBlockEntity.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":high_capacity_crafting_machine")
            ));

    private static final String ACTION_NEXT_PAGE = "nextPage";
    private static final String ACTION_PREVIOUS_PAGE = "previousPage";
    private static final String ACTION_GOTO_PAGE = "gotoPage";
    private static final String ACTION_SEARCH_PATTERNS = "searchPatterns";
    private static final String ACTION_CLEAR_SEARCH = "clearSearch";

    private final HighCapacityCraftingMachineBlockEntity host;

    @GuiSync(20)
    public int pageIndex;
    @GuiSync(21)
    public int pageCount = 1;
    @GuiSync(22)
    public int totalPatternSlots;
    @GuiSync(23)
    public int activePatternSlots;
    @GuiSync(24)
    public int decodedPatternCount;
    @GuiSync(27)
    public int highlightedGlobalSlot = -1;
    @GuiSync(28)
    public int highlightedPageSlot = -1;
    @GuiSync(29)
    public int searchResultCount;
    @GuiSync(32)
    public String lastSearchQuery = "";
    @GuiSync(33)
    public int highlightedPageSlotMask;

    public HighCapacityCraftingMachineMenu(int id, Inventory playerInventory, HighCapacityCraftingMachineBlockEntity host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        setupInventorySlots();
        createPlayerInventorySlots(playerInventory);
        registerClientAction(ACTION_NEXT_PAGE, this::nextPageOnServer);
        registerClientAction(ACTION_PREVIOUS_PAGE, this::previousPageOnServer);
        registerClientAction(ACTION_GOTO_PAGE, Integer.class, this::gotoPageOnServer);
        registerClientAction(ACTION_SEARCH_PATTERNS, String.class, this::searchPatternsOnServer);
        registerClientAction(ACTION_CLEAR_SEARCH, this::clearSearchOnServer);
    }

    public HighCapacityCraftingMachineBlockEntity getHost() {
        return host;
    }

    protected void setupInventorySlots() {
        var patternInventory = getHost().getTerminalPatternInventory();
        for (int slot = 0; slot < getHost().getVisiblePatternSlots(); slot++) {
            addSlot(
                    new PatternSlot(
                            RestrictedInputSlot.PlacableItemType.MOLECULAR_ASSEMBLER_PATTERN,
                            patternInventory,
                            slot,
                            slot
                    ).setStackLimit(1),
                    SlotSemantics.ENCODED_PATTERN
            );
        }
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            pageIndex = getHost().getPageIndex();
            pageCount = getHost().getPageCount();
            totalPatternSlots = getHost().getTotalPatternSlots();
            activePatternSlots = getHost().getActivePatternSlots();
            decodedPatternCount = getHost().getDecodedPatternCount();
            highlightedGlobalSlot = getHost().getHighlightedGlobalSlot();
            highlightedPageSlot = getHost().getHighlightedPageSlot();
            searchResultCount = getHost().getSearchResultCount();
            lastSearchQuery = getHost().getLastSearchQuery();
            highlightedPageSlotMask = getHost().getHighlightedPageSlotMask();
        }
        super.broadcastChanges();
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

    public void searchPatterns(String query) {
        if (isClientSide()) {
            sendClientAction(ACTION_SEARCH_PATTERNS, query);
        } else {
            searchPatternsOnServer(query);
        }
    }

    public void clearSearch() {
        if (isClientSide()) {
            sendClientAction(ACTION_CLEAR_SEARCH);
        } else {
            clearSearchOnServer();
        }
    }

    public boolean canGoToPreviousPage() {
        return pageIndex > 0;
    }

    public boolean canGoToNextPage() {
        return pageIndex + 1 < pageCount;
    }

    public int getPatternPageSlotIndex(Slot slot) {
        return getSlots(SlotSemantics.ENCODED_PATTERN).indexOf(slot);
    }

    public Component getPatternSlotPageTooltip(int pageSlotIndex) {
        int visibleSlots = Math.max(1, getHost().getVisiblePatternSlots());
        int globalSlot = pageIndex * visibleSlots + pageSlotIndex;
        return Component.translatable(
                "gui.chexsonsaeutils.high_capacity_crafting_machine.slot_page_tooltip",
                globalSlot + 1,
                globalSlot / visibleSlots + 1,
                pageCount
        );
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
        getHost().gotoPage(pageIndex);
        broadcastChanges();
    }

    private void searchPatternsOnServer(String query) {
        getHost().searchAndHighlightNext(query);
        broadcastChanges();
    }

    private void clearSearchOnServer() {
        getHost().clearSearchState();
        broadcastChanges();
    }

    private final class PatternSlot extends RestrictedInputSlot {

        private final int pageSlotIndex;

        private PatternSlot(
                PlacableItemType valid,
                appeng.api.inventories.InternalInventory inv,
                int slotIndex,
                int pageSlotIndex
        ) {
            super(valid, inv, slotIndex);
            this.pageSlotIndex = pageSlotIndex;
        }

        @Override
        public ItemStack getDisplayStack() {
            if (isRemote()) {
                final ItemStack is = super.getDisplayStack();
                if (!is.isEmpty() && is.getItem() instanceof appeng.crafting.pattern.EncodedPatternItem iep) {
                    final ItemStack out = iep.getOutput(is);
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            }
            return super.getDisplayStack();
        }

        @Override
        public List<Component> getCustomTooltip(ItemStack carried) {
            ItemStack stack = getHost().getTerminalPatternInventory().getStackInSlot(pageSlotIndex);
            if (!stack.isEmpty()) {
                return null;
            }
            return List.of(getPatternSlotPageTooltip(pageSlotIndex));
        }
    }
}
