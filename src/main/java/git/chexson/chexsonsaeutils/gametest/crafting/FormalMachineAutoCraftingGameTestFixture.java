package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.items.storage.CreativeCellItem;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class FormalMachineAutoCraftingGameTestFixture {

    private static final BlockPos ENERGY_POS = new BlockPos(0, 1, 1);
    private static final BlockPos MACHINE_POS = new BlockPos(1, 1, 1);
    private static final BlockPos ME_CHEST_POS = new BlockPos(2, 1, 1);
    private static final int CPU_SIZE_X = 4;
    private static final int CPU_SIZE_Y = 4;
    private static final int CPU_SIZE_Z = 4;
    private static final List<BlockPos> CPU_STORAGE_POSITIONS = createCpuPositions(false);
    private static final List<BlockPos> CPU_ACCELERATOR_POSITIONS = createCpuPositions(true);
    private static final BlockPos CPU_STORAGE_POS = CPU_STORAGE_POSITIONS.getFirst();

    private final GameTestHelper helper;
    private final HighCapacityCraftingMachineBlockEntity machine;
    private final MEChestBlockEntity meChest;
    private final CreativeEnergyCellBlockEntity energyCell;
    private final CraftingBlockEntity cpuStorage;
    private final GameTestSimulationRequester simulationRequester;
    private final GameTestCraftingRequester craftingRequester;
    private boolean networkConnectionsCreated;

    private FormalMachineAutoCraftingGameTestFixture(
            GameTestHelper helper,
            HighCapacityCraftingMachineBlockEntity machine,
            MEChestBlockEntity meChest,
            CreativeEnergyCellBlockEntity energyCell,
            CraftingBlockEntity cpuStorage,
            GameTestCraftingRequester.AcceptancePolicy acceptancePolicy
    ) {
        this.helper = helper;
        this.machine = machine;
        this.meChest = meChest;
        this.energyCell = energyCell;
        this.cpuStorage = cpuStorage;
        this.simulationRequester = new GameTestSimulationRequester(machine);
        this.craftingRequester = new GameTestCraftingRequester(machine, acceptancePolicy);
    }

    public static FormalMachineAutoCraftingGameTestFixture create(GameTestHelper helper) {
        return create(helper, GameTestCraftingRequester.AcceptancePolicy.acceptAll());
    }

    public static FormalMachineAutoCraftingGameTestFixture create(
            GameTestHelper helper,
            GameTestCraftingRequester.AcceptancePolicy acceptancePolicy
    ) {
        helper.setBlock(MACHINE_POS, Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get());
        helper.setBlock(ME_CHEST_POS, AEBlocks.ME_CHEST.block());
        helper.setBlock(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());
        for (BlockPos cpuStoragePos : CPU_STORAGE_POSITIONS) {
            helper.setBlock(cpuStoragePos, AEBlocks.CRAFTING_STORAGE_256K.block());
        }
        for (BlockPos acceleratorPos : CPU_ACCELERATOR_POSITIONS) {
            helper.setBlock(acceleratorPos, AEBlocks.CRAFTING_ACCELERATOR.block());
        }

        HighCapacityCraftingMachineBlockEntity machine = helper.getBlockEntity(MACHINE_POS);
        helper.assertTrue(machine != null, "formal machine should exist in auto-crafting fixture");
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
        helper.assertTrue(meChest != null, "ME chest should exist in auto-crafting fixture");
        CreativeEnergyCellBlockEntity energyCell = helper.getBlockEntity(ENERGY_POS);
        helper.assertTrue(energyCell != null, "energy cell should exist in auto-crafting fixture");
        CraftingBlockEntity cpuStorage = helper.getBlockEntity(CPU_STORAGE_POS);
        helper.assertTrue(cpuStorage != null, "crafting CPU storage block should exist in auto-crafting fixture");
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
        return new FormalMachineAutoCraftingGameTestFixture(
                helper,
                machine,
                meChest,
                energyCell,
                cpuStorage,
                acceptancePolicy
        );
    }

    public HighCapacityCraftingMachineBlockEntity machine() {
        return machine;
    }

    public GameTestCraftingRequester requester() {
        return craftingRequester;
    }

    public CraftingCPUCluster cpuCluster() {
        return cpuStorage.getCluster();
    }

    public CraftingBlockEntity cpuStorage() {
        return cpuStorage;
    }

    public void assertNetworkReady() {
        helper.assertTrue(machine.getMainNode().isReady(), "formal machine node must be ready before network assertions");
        helper.assertTrue(meChest.getMainNode().isReady(), "ME chest node must be ready before network assertions");
        helper.assertTrue(energyCell.getMainNode().isReady(), "energy cell node must be ready before network assertions");
        helper.assertTrue(cpuStorage.getMainNode().isReady(), "crafting CPU node must be ready before network assertions");
        ensureConnected();
        helper.assertTrue(machine.getMainNode().isActive(), "formal machine must be active on the AE network");
        helper.assertTrue(machine.getGrid() != null, "formal machine must expose a non-null AE grid");
        helper.assertTrue(machine.getGrid().getCraftingService() != null, "formal machine crafting service must exist");
        helper.assertTrue(cpuStorage.getCluster() != null, "dedicated crafting CPU must form a valid cluster");
    }

    public void installCreativeStorageCell(ItemLike... items) {
        meChest.setCell(CreativeCellItem.ofItems(items));
    }

    public void clearAeStorage() {
        IStorageService storageService = machine.getGrid().getStorageService();
        var inventory = storageService.getInventory();
        var available = storageService.getCachedInventory();
        for (var entry : available) {
            inventory.extract(entry.getKey(), entry.getLongValue(), Actionable.MODULATE, craftingRequester.getActionSource());
        }
    }

    public Collection<appeng.api.crafting.IPatternDetails> lookupCraftables(AEItemKey output) {
        ICraftingService craftingService = machine.getGrid().getCraftingService();
        Collection<appeng.api.crafting.IPatternDetails> craftables = craftingService.getCraftingFor(output);
        machine.recordAeCraftingLookupForTest(!craftables.isEmpty());
        if (!craftables.isEmpty()) {
            machine.recordNetworkPatternExposureForTest();
        }
        return craftables;
    }

    public Future<ICraftingPlan> beginCraftingPlanFuture(AEItemKey output, long amount) {
        return beginCraftingPlanFuture(output, amount, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    public Future<ICraftingPlan> beginCraftingPlanFuture(
            AEItemKey output,
            long amount,
            CalculationStrategy strategy
    ) {
        ICraftingService craftingService = machine.getGrid().getCraftingService();
        return craftingService.beginCraftingCalculation(
                helper.getLevel(),
                simulationRequester,
                output,
                amount,
                strategy
        );
    }

    public ICraftingPlan beginCraftingPlan(AEItemKey output, long amount) throws Exception {
        ICraftingPlan plan = beginCraftingPlanFuture(output, amount).get(10, TimeUnit.SECONDS);
        machine.recordAeCraftingPlanForTest(plan != null && plan.missingItems().isEmpty());
        return plan;
    }

    public void recordPlanResult(ICraftingPlan plan) {
        machine.recordAeCraftingPlanForTest(plan != null && plan.missingItems().isEmpty());
    }

    public ICraftingSubmitResult submitCraftingPlan(ICraftingPlan plan) {
        long startedAt = System.nanoTime();
        ICraftingSubmitResult result = machine.getGrid().getCraftingService().submitJob(
                plan,
                craftingRequester,
                null,
                false,
                craftingRequester.getActionSource()
        );
        machine.recordSubmitBenchmarkForTest(System.nanoTime() - startedAt, result != null && result.successful());
        craftingRequester.trackLink(result == null ? null : result.link());
        return result;
    }

    public ICraftingSubmitResult submitCraftingPlanStandalone(ICraftingPlan plan) {
        long startedAt = System.nanoTime();
        ICraftingSubmitResult result = machine.getGrid().getCraftingService().submitJob(
                plan,
                null,
                null,
                true,
                craftingRequester.getActionSource()
        );
        machine.recordSubmitBenchmarkForTest(System.nanoTime() - startedAt, result != null && result.successful());
        return result;
    }

    public long insertStored(AEItemKey key, long amount) {
        return machine.getGrid().getStorageService().getInventory().insert(
                key,
                amount,
                Actionable.MODULATE,
                craftingRequester.getActionSource()
        );
    }

    public long countStored(AEItemKey key) {
        return machine.getGrid().getStorageService().getCachedInventory().get(key);
    }

    public long countStoredLive(AEItemKey key) {
        return machine.getGrid().getStorageService().getInventory().getAvailableStacks().get(key);
    }

    public void assertStoredLive(AEItemKey key, long minimumAmount) {
        helper.assertTrue(countStoredLive(key) >= minimumAmount,
                "AE storage should expose at least " + minimumAmount + " of " + key);
    }

    private static List<BlockPos> createCpuPositions(boolean acceleratorsOnly) {
        List<BlockPos> positions = new ArrayList<>(CPU_SIZE_X * CPU_SIZE_Y * CPU_SIZE_Z);
        for (int x = 0; x < CPU_SIZE_X; x++) {
            for (int y = 0; y < CPU_SIZE_Y; y++) {
                for (int z = 0; z < CPU_SIZE_Z; z++) {
                    BlockPos pos = new BlockPos(3 + x, y, z);
                    if (isAcceleratorPosition(pos) == acceleratorsOnly) {
                        positions.add(pos);
                    }
                }
            }
        }
        return List.copyOf(positions);
    }

    private static boolean isAcceleratorPosition(BlockPos pos) {
        return pos.getX() == 6 && pos.getZ() == 3;
    }

    private void ensureConnected() {
        if (networkConnectionsCreated) {
            return;
        }
        var machineNode = machine.getMainNode().getNode();
        var chestNode = meChest.getMainNode().getNode();
        var energyNode = energyCell.getMainNode().getNode();
        var cpuNode = cpuStorage.getMainNode().getNode();
        helper.assertTrue(machineNode != null, "formal machine node must exist before wiring");
        helper.assertTrue(chestNode != null, "ME chest node must exist before wiring");
        helper.assertTrue(energyNode != null, "energy cell node must exist before wiring");
        helper.assertTrue(cpuNode != null, "crafting CPU node must exist before wiring");
        if (machineNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(machineNode) == chestNode)) {
            GridHelper.createConnection(machineNode, chestNode);
        }
        if (machineNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(machineNode) == energyNode)) {
            GridHelper.createConnection(machineNode, energyNode);
        }
        if (machineNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(machineNode) == cpuNode)) {
            GridHelper.createConnection(machineNode, cpuNode);
        }
        networkConnectionsCreated = true;
    }
}
