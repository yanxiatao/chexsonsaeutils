package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.networking.GridHelper;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

public final class MultipleFormalMachinesGameTestFixture {

    private static final BlockPos ENERGY_POS = new BlockPos(0, 1, 1);
    private static final BlockPos MACHINE_A_POS = new BlockPos(1, 1, 1);
    private static final BlockPos MACHINE_B_POS = new BlockPos(2, 1, 1);
    private static final BlockPos ME_CHEST_POS = new BlockPos(3, 1, 1);

    private final GameTestHelper helper;
    private final HighCapacityCraftingMachineBlockEntity machineA;
    private final HighCapacityCraftingMachineBlockEntity machineB;
    private final MEChestBlockEntity meChest;
    private final CreativeEnergyCellBlockEntity energyCell;
    private boolean connected;

    private MultipleFormalMachinesGameTestFixture(
            GameTestHelper helper,
            HighCapacityCraftingMachineBlockEntity machineA,
            HighCapacityCraftingMachineBlockEntity machineB,
            MEChestBlockEntity meChest,
            CreativeEnergyCellBlockEntity energyCell
    ) {
        this.helper = helper;
        this.machineA = machineA;
        this.machineB = machineB;
        this.meChest = meChest;
        this.energyCell = energyCell;
    }

    public static MultipleFormalMachinesGameTestFixture create(GameTestHelper helper) {
        helper.setBlock(MACHINE_A_POS, Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get());
        helper.setBlock(MACHINE_B_POS, Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get());
        helper.setBlock(ME_CHEST_POS, AEBlocks.ME_CHEST.block());
        helper.setBlock(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());

        HighCapacityCraftingMachineBlockEntity machineA = helper.getBlockEntity(MACHINE_A_POS);
        HighCapacityCraftingMachineBlockEntity machineB = helper.getBlockEntity(MACHINE_B_POS);
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
        CreativeEnergyCellBlockEntity energyCell = helper.getBlockEntity(ENERGY_POS);
        helper.assertTrue(machineA != null, "machine A should exist");
        helper.assertTrue(machineB != null, "machine B should exist");
        helper.assertTrue(meChest != null, "ME chest should exist");
        helper.assertTrue(energyCell != null, "energy cell should exist");
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
        return new MultipleFormalMachinesGameTestFixture(helper, machineA, machineB, meChest, energyCell);
    }

    public void assertNetworkReady() {
        helper.assertTrue(machineA.getMainNode().isReady(), "machine A node must be ready");
        helper.assertTrue(machineB.getMainNode().isReady(), "machine B node must be ready");
        helper.assertTrue(meChest.getMainNode().isReady(), "ME chest node must be ready");
        helper.assertTrue(energyCell.getMainNode().isReady(), "energy cell node must be ready");
        ensureConnected();
        helper.assertTrue(machineA.getMainNode().isActive(), "machine A must be active");
        helper.assertTrue(machineB.getMainNode().isActive(), "machine B must be active");
        helper.assertTrue(machineA.getGrid() == machineB.getGrid(), "both machines must join the same AE grid");
    }

    public HighCapacityCraftingMachineBlockEntity machineA() {
        return machineA;
    }

    public HighCapacityCraftingMachineBlockEntity machineB() {
        return machineB;
    }

    private void ensureConnected() {
        if (connected) {
            return;
        }
        var machineANode = machineA.getMainNode().getNode();
        var machineBNode = machineB.getMainNode().getNode();
        var chestNode = meChest.getMainNode().getNode();
        var energyNode = energyCell.getMainNode().getNode();
        helper.assertTrue(machineANode != null, "machine A node must exist");
        helper.assertTrue(machineBNode != null, "machine B node must exist");
        helper.assertTrue(chestNode != null, "ME chest node must exist");
        helper.assertTrue(energyNode != null, "energy node must exist");
        if (machineANode.getConnections().stream()
                .noneMatch(connection -> connection.getOtherSide(machineANode) == machineBNode)) {
            GridHelper.createConnection(machineANode, machineBNode);
        }
        if (machineANode.getConnections().stream()
                .noneMatch(connection -> connection.getOtherSide(machineANode) == chestNode)) {
            GridHelper.createConnection(machineANode, chestNode);
        }
        if (machineANode.getConnections().stream()
                .noneMatch(connection -> connection.getOtherSide(machineANode) == energyNode)) {
            GridHelper.createConnection(machineANode, energyNode);
        }
        connected = true;
    }
}
