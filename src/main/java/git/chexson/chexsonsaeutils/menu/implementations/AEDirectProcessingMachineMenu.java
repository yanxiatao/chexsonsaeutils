package git.chexson.chexsonsaeutils.menu.implementations;

import appeng.helpers.InventoryAction;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRulesRegistry;
import git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload;
import git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesTarget;
import net.neoforged.neoforge.network.PacketDistributor;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixInteraction;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixMenuLink;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixServerTarget;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixTarget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

public class AEDirectProcessingMachineMenu extends AEBaseMenu
        implements PatternMatrixTarget, PatternMatrixServerTarget, DirectProcessingPackRulesTarget {

    public static final MenuType<AEDirectProcessingMachineMenu> TYPE = MenuTypeBuilder
            .create(AEDirectProcessingMachineMenu::new, AEDirectProcessingMachineBlockEntity.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(ModIds.MODID + ":ae_direct_processing_machine")
            ));

    @GuiSync(40)
    public String detectedRecipeTypeSummary = "-";

    @GuiSync(41)
    public boolean machinePage;

    private final AEDirectProcessingMachineBlockEntity host;
    private final PatternMatrixMenuLink matrix;
    private final git.chexson.chexsonsaeutils.menu.directprocessing.MachineBindingMatrixSourceImpl machineMatrix;
    private long observedPackRulesEpoch = -1;

    public AEDirectProcessingMachineMenu(
            int id,
            Inventory playerInventory,
            AEDirectProcessingMachineBlockEntity host
    ) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        this.matrix = new PatternMatrixMenuLink(this, host);
        this.machineMatrix = new git.chexson.chexsonsaeutils.menu.directprocessing.MachineBindingMatrixSourceImpl(host);
        for (int slot = 0; slot < AEDirectProcessingMachineBlockEntity.MACHINE_BINDING_SLOTS; slot++) {
            addSlot(new MachineBindingSlot(host, slot), SlotSemantics.MACHINE_INPUT);
        }
        registerClientAction("machine_page", Boolean.class, page -> {
            machinePage = page;
            updateBindingSlots();
        });
        updateBindingSlots();
        createPlayerInventorySlots(playerInventory);
    }

    /** 切换当前库存页，服务端矩阵点击按页校验。 */
    public void selectMachinePage(boolean page) {
        machinePage = page;
        updateBindingSlots();
        sendClientAction("machine_page", page);
    }

    private void updateBindingSlots() {
        for (var slot : getSlots(SlotSemantics.MACHINE_INPUT)) {
            var binding = (MachineBindingSlot) slot;
            // 原版槽只负责持续同步真实标记库存；两页都通过虚拟矩阵交互。
            binding.setActive(false);
            binding.setSlotEnabled(true);
        }
    }

    @Override
    public void onServerDataSync(it.unimi.dsi.fastutil.shorts.ShortSet updatedFields) {
        super.onServerDataSync(updatedFields);
        updateBindingSlots();
    }

    @Override
    public void applyPackRulesSnapshot(String json) {
        DirectProcessingPackRulesRegistry.applyClientSnapshot(json);
    }

    public AEDirectProcessingMachineBlockEntity getHost() {
        return host;
    }

    @Override
    public void broadcastChanges() {
        updateBindingSlots();
        if (isServerSide()) {
            long epoch = DirectProcessingPackRulesRegistry.epoch();
            if (observedPackRulesEpoch != epoch && getPlayer() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, new DirectProcessingPackRulesPayload(containerId,
                        DirectProcessingPackRulesRegistry.current().toJson()));
                observedPackRulesEpoch = epoch;
            }
            detectedRecipeTypeSummary = getHost().getDetectedRecipeTypeSummaryForMenu();
            matrix.serverBroadcastChanges();
        }
        super.broadcastChanges();
    }

    /** 背包 shift 点击样板：直接写进全量样板库存的第一个可用槽。 */
    @Override
    protected int transferStackToMenu(ItemStack input) {
        if (machinePage) {
            return PatternMatrixInteraction.shiftInsertIntoMatrix(machineMatrix, input);
        }
        return PatternMatrixInteraction.shiftInsertIntoMatrix(host, input);
    }

    public int getContainerIdForPayload() {
        return containerId;
    }

    public boolean applyJeiImportRequestFromClient(MachineRecipeConfigImportRequest request) {
        if (request == null || getHost() == null) {
            return false;
        }
        if (!getHost().importUserConfigMappingForMenu(request)) {
            return false;
        }
        broadcastChanges();
        return true;
    }

    public Component getSummaryLine() {
        return Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.recipe_types",
                detectedRecipeTypeSummary
        );
    }

    /** 界面与矩阵面板共用的同步胶水。 */
    public PatternMatrixMenuLink matrixLink() {
        return matrix;
    }

    // ---------- 客户端视图 ----------

    @Override
    public boolean matchesContainerId(int containerId) {
        return matrix.matchesContainerId(containerId);
    }

    @Override
    public void applyMatrixEntries(int totalSlots, List<PatternMatrixEntry> entries) {
        matrix.applyMatrixEntries(totalSlots, entries);
    }

    @Override
    public void applyMatrixMatches(long[] matchBits, int matchCount) {
        matrix.applyMatrixMatches(matchBits, matchCount);
    }

    @Override
    public void applyMatrixRealStacks(List<PatternMatrixRealStack> realStacks) {
        matrix.applyMatrixRealStacks(realStacks);
    }

    @Override
    public PatternMatrixClientView matrixView() {
        return matrix.matrixView();
    }

    // ---------- 服务端请求 ----------

    @Override
    public void doAction(ServerPlayer player, InventoryAction action, int slot, long id) {
        if (id == PatternMatrixInteraction.MATRIX_MACHINE_ID) {
            if (machinePage && isServerSide() && player.containerMenu == this) {
                PatternMatrixInteraction.doAction(machineMatrix, action, slot, player,
                        this::getCarried, this::setCarried);
                broadcastChanges();
            }
            return;
        }
        if (id == PatternMatrixInteraction.MATRIX_PATTERN_ID) {
            if (!machinePage) matrix.handleMatrixClick(action, slot, player);
            return;
        }
        if (slot >= 0 && slot < slots.size() && slots.get(slot) instanceof MachineBindingSlot) return;
        super.doAction(player, action, slot, id);
    }

    @Override
    public void handleMatrixSearch(String query, ServerPlayer player) {
        matrix.handleMatrixSearch(query, player);
    }

    @Override
    public void handleMatrixVisibleRequest(int[] globalSlots, ServerPlayer player) {
        matrix.handleMatrixVisibleRequest(globalSlots, player);
    }

    private static final class MachineBindingSlot extends RestrictedInputSlot {

        private MachineBindingSlot(AEDirectProcessingMachineBlockEntity host, int slotIndex) {
            super(PlacableItemType.INSCRIBER_PLATE, host.getMachineBindingInventory(), slotIndex);
            setStackLimit(1);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return false; }

        @Override
        public ItemStack remove(int amount) { return ItemStack.EMPTY; }

        @Override
        public List<Component> getCustomTooltip(ItemStack carried) {
            if (!getItem().isEmpty()) {
                return null;
            }
            return List.of(Component.translatable("gui.chexsonsaeutils.ae_direct_processing_machine.binding_slot_tooltip"));
        }
    }
}
