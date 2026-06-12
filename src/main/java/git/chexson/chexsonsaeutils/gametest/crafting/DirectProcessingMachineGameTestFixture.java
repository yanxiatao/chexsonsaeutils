package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridHelper;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.List;

public final class DirectProcessingMachineGameTestFixture {

    private static final BlockPos ENERGY_POS = new BlockPos(0, 1, 1);
    private static final BlockPos MACHINE_POS = new BlockPos(1, 1, 1);
    private static final BlockPos ME_CHEST_POS = new BlockPos(2, 1, 1);

    private final GameTestHelper helper;
    private final AEDirectProcessingMachineBlockEntity machine;
    private final MEChestBlockEntity meChest;
    private final CreativeEnergyCellBlockEntity energyCell;
    private final IActionSource actionSource = IActionSource.empty();
    private boolean networkConnectionsCreated;

    private DirectProcessingMachineGameTestFixture(
            GameTestHelper helper,
            AEDirectProcessingMachineBlockEntity machine,
            MEChestBlockEntity meChest,
            CreativeEnergyCellBlockEntity energyCell
    ) {
        this.helper = helper;
        this.machine = machine;
        this.meChest = meChest;
        this.energyCell = energyCell;
    }

    public static DirectProcessingMachineGameTestFixture create(GameTestHelper helper) {
        helper.setBlock(MACHINE_POS, Chexsonsaeutils.AE_DIRECT_PROCESSING_MACHINE_BLOCK.get());
        helper.setBlock(ME_CHEST_POS, AEBlocks.ME_CHEST.block());
        helper.setBlock(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());

        AEDirectProcessingMachineBlockEntity machine = helper.getBlockEntity(MACHINE_POS);
        helper.assertTrue(machine != null, "direct processing machine should exist in fixture");
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
        helper.assertTrue(meChest != null, "ME chest should exist in direct processing fixture");
        CreativeEnergyCellBlockEntity energyCell = helper.getBlockEntity(ENERGY_POS);
        helper.assertTrue(energyCell != null, "energy cell should exist in direct processing fixture");
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
        return new DirectProcessingMachineGameTestFixture(helper, machine, meChest, energyCell);
    }

    public AEDirectProcessingMachineBlockEntity machine() {
        return machine;
    }

    public void assertNetworkReady() {
        helper.assertTrue(machine.getMainNode().isReady(), "direct machine node must be ready");
        helper.assertTrue(meChest.getMainNode().isReady(), "ME chest node must be ready");
        helper.assertTrue(energyCell.getMainNode().isReady(), "energy cell node must be ready");
        ensureConnected();
        helper.assertTrue(machine.getMainNode().isActive(), "direct machine must be active on the AE network");
        helper.assertTrue(machine.getGrid() != null, "direct machine must expose a non-null AE grid");
        helper.assertTrue(machine.getGrid().getStorageService() != null, "direct machine storage service must exist");
        helper.assertTrue(machine.getGrid().getCraftingService() != null, "direct machine crafting service must exist");
    }

    public void bindMachine(ItemLike machineItem) {
        machine.setMachineBindingStack(new ItemStack(machineItem));
    }

    public void installProcessingPattern(int slot, ItemLike input, long inputAmount, ItemLike output, long outputAmount) {
        machine.setPatternAt(slot, encodeProcessingPattern(input, inputAmount, output, outputAmount));
    }

    public void installProcessingPattern(int slot, List<GenericStack> inputs, List<GenericStack> outputs) {
        machine.setPatternAt(slot, encodeProcessingPattern(inputs, outputs));
    }

    public void installSpeedCards(int count) {
        int desired = Math.max(0, Math.min(count, machine.getUpgrades().size()));
        for (int slot = 0; slot < machine.getUpgrades().size(); slot++) {
            machine.getUpgrades().setItemDirect(slot, slot < desired ? AEItems.SPEED_CARD.stack() : ItemStack.EMPTY);
        }
    }

    public ItemStack encodeProcessingPattern(ItemLike input, long inputAmount, ItemLike output, long outputAmount) {
        return encodeProcessingPattern(
                List.of(new GenericStack(AEItemKey.of(input), Math.max(1L, inputAmount))),
                List.of(new GenericStack(AEItemKey.of(output), Math.max(1L, outputAmount)))
        );
    }

    public ItemStack encodeProcessingPattern(List<GenericStack> inputs, List<GenericStack> outputs) {
        return PatternDetailsHelper.encodeProcessingPattern(
                inputs == null ? List.of() : List.copyOf(inputs),
                outputs == null ? List.of() : List.copyOf(outputs)
        );
    }

    public void seedInputs(List<GenericStack> stacks) {
        IStorageService storageService = machine.getGrid().getStorageService();
        var inventory = storageService.getInventory();
        for (GenericStack stack : stacks) {
            long inserted = inventory.insert(stack.what(), stack.amount(), Actionable.MODULATE, actionSource);
            helper.assertValueEqual(stack.amount(), inserted, "direct fixture failed to seed " + stack);
        }
    }

    public void removeStorageCell() {
        meChest.setCell(ItemStack.EMPTY);
    }

    public void installStorageCell() {
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
    }

    public long countStored(AEItemKey key) {
        return machine.getGrid().getStorageService().getCachedInventory().get(key);
    }

    private void ensureConnected() {
        if (networkConnectionsCreated) {
            return;
        }
        var machineNode = machine.getMainNode().getNode();
        var chestNode = meChest.getMainNode().getNode();
        var energyNode = energyCell.getMainNode().getNode();
        helper.assertTrue(machineNode != null, "direct machine node must exist before wiring");
        helper.assertTrue(chestNode != null, "ME chest node must exist before wiring");
        helper.assertTrue(energyNode != null, "energy cell node must exist before wiring");
        if (machineNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(machineNode) == chestNode)) {
            GridHelper.createConnection(machineNode, chestNode);
        }
        if (machineNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(machineNode) == energyNode)) {
            GridHelper.createConnection(machineNode, energyNode);
        }
        networkConnectionsCreated = true;
    }
}
