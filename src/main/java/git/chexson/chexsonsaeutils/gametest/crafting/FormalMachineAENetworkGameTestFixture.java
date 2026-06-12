package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.items.storage.CreativeCellItem;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class FormalMachineAENetworkGameTestFixture {

    private static final BlockPos ENERGY_POS = new BlockPos(0, 1, 1);
    private static final BlockPos MACHINE_POS = new BlockPos(1, 1, 1);
    private static final BlockPos ME_CHEST_POS = new BlockPos(2, 1, 1);
    private static final BlockPos AUX_PROVIDER_POS = new BlockPos(1, 2, 1);

    private final GameTestHelper helper;
    private final HighCapacityCraftingMachineBlockEntity machine;
    private final MEChestBlockEntity meChest;
    private final CreativeEnergyCellBlockEntity energyCell;
    private final GameTestSimulationRequester simulationRequester;
    private final IActionSource actionSource = IActionSource.empty();
    private boolean networkConnectionsCreated;
    private PatternProviderBlockEntity auxiliaryProvider;

    private FormalMachineAENetworkGameTestFixture(
            GameTestHelper helper,
            HighCapacityCraftingMachineBlockEntity machine,
            MEChestBlockEntity meChest,
            CreativeEnergyCellBlockEntity energyCell
    ) {
        this.helper = helper;
        this.machine = machine;
        this.meChest = meChest;
        this.energyCell = energyCell;
        this.simulationRequester = new GameTestSimulationRequester(machine);
    }

    public static FormalMachineAENetworkGameTestFixture create(GameTestHelper helper) {
        helper.setBlock(MACHINE_POS, Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get());
        helper.setBlock(ME_CHEST_POS, AEBlocks.ME_CHEST.block());
        helper.setBlock(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block());

        HighCapacityCraftingMachineBlockEntity machine = helper.getBlockEntity(MACHINE_POS);
        helper.assertTrue(machine != null, "formal machine should exist in AE network fixture");
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS);
        helper.assertTrue(meChest != null, "ME chest should exist in formal machine fixture");
        CreativeEnergyCellBlockEntity energyCell = helper.getBlockEntity(ENERGY_POS);
        helper.assertTrue(energyCell != null, "energy cell should exist in formal machine fixture");
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
        return new FormalMachineAENetworkGameTestFixture(helper, machine, meChest, energyCell);
    }

    public HighCapacityCraftingMachineBlockEntity machine() {
        return machine;
    }

    public MEChestBlockEntity meChest() {
        return meChest;
    }

    public void assertNetworkReady() {
        helper.assertTrue(machine.getMainNode().isReady(), "formal machine node must be ready before network assertions");
        helper.assertTrue(meChest.getMainNode().isReady(), "ME chest node must be ready before network assertions");
        helper.assertTrue(energyCell.getMainNode().isReady(), "energy cell node must be ready before network assertions");
        ensureConnected();
        helper.assertTrue(machine.getMainNode().isActive(), "formal machine must be active on the AE network");
        helper.assertTrue(machine.getGrid() != null, "formal machine must expose a non-null AE grid");
        helper.assertTrue(machine.getGrid().getStorageService() != null, "formal machine storage service must exist");
        helper.assertTrue(machine.getGrid().getCraftingService() != null, "formal machine crafting service must exist");
    }

    /**
     * 当前 fixture 只搭建 formal machine、ME chest 与 energy cell。
     * 这里没有放入任何专用 AE2 crafting CPU 方块，因此只覆盖 planning lookup / simulation，
     * 不能把相关测试结果解读成真实 crafting CPU fast-path 已被验收。
     */
    public void assertNoDedicatedCraftingCpuPresent() {
        helper.assertTrue(machine.getGrid() != null, "formal machine must expose a non-null AE grid");
        helper.assertValueEqual(2, machine.getMainNode().getNode().getConnections().size(),
                "formal machine fixture should only wire ME chest and energy cell");
    }

    public void clearAeStorage() {
        IStorageService storageService = machine.getGrid().getStorageService();
        var inventory = storageService.getInventory();
        var available = storageService.getCachedInventory();
        for (var entry : available) {
            inventory.extract(entry.getKey(), entry.getLongValue(), Actionable.MODULATE, actionSource);
        }
    }

    public void removeStorageCell() {
        meChest.setCell(ItemStack.EMPTY);
    }

    public void installStorageCell() {
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
    }

    public void installCreativeStorageCell(ItemLike... items) {
        meChest.setCell(CreativeCellItem.ofItems(items));
    }

    public PatternProviderBlockEntity installPatternProvider(Block block, List<ItemStack> patterns) {
        helper.setBlock(AUX_PROVIDER_POS, block);
        BlockEntity blockEntity = helper.getBlockEntity(AUX_PROVIDER_POS);
        helper.assertTrue(blockEntity instanceof PatternProviderBlockEntity,
                "auxiliary provider must be an AE2 pattern provider block entity");
        PatternProviderBlockEntity provider = (PatternProviderBlockEntity) blockEntity;
        var patternInventory = provider.getLogic().getPatternInv();
        helper.assertTrue(patternInventory.size() >= patterns.size(),
                "auxiliary provider must expose enough pattern slots");
        for (int index = 0; index < patterns.size(); index++) {
            patternInventory.setItemDirect(index, patterns.get(index).copyWithCount(1));
        }
        provider.getLogic().updatePatterns();
        provider.saveChanges();
        auxiliaryProvider = provider;
        return provider;
    }

    public void assertAuxiliaryPatternProviderConnected() {
        helper.assertTrue(auxiliaryProvider != null, "auxiliary provider must be installed before provider wiring");
        PatternProviderBlockEntity provider = auxiliaryProvider;
        helper.assertTrue(provider.getMainNode().isReady(), "auxiliary provider node must be ready before provider wiring");
        ensureConnected();
        var machineNode = machine.getMainNode().getNode();
        var providerNode = provider.getMainNode().getNode();
        helper.assertTrue(machineNode != null, "formal machine node must exist before provider wiring");
        helper.assertTrue(providerNode != null, "auxiliary provider node must exist before provider wiring");
        if (machineNode.getConnections().stream().noneMatch(connection -> connection.getOtherSide(machineNode) == providerNode)) {
            GridHelper.createConnection(machineNode, providerNode);
        }
        helper.assertTrue(provider.getMainNode().isActive(), "auxiliary provider must be active after provider wiring");
        provider.getLogic().updatePatterns();
    }

    public void seedInputs(List<GenericStack> stacks) {
        IStorageService storageService = machine.getGrid().getStorageService();
        var inventory = storageService.getInventory();
        for (GenericStack stack : stacks) {
            long inserted = inventory.insert(stack.what(), stack.amount(), Actionable.MODULATE, actionSource);
            helper.assertTrue(inserted == stack.amount(),
                    "formal machine fixture failed to seed " + stack + ", inserted=" + inserted);
        }
    }

    public void assertSeedInputsVisible(List<GenericStack> stacks) {
        for (GenericStack stack : stacks) {
            helper.assertTrue(countStored((AEItemKey) stack.what()) >= stack.amount(),
                    "seed input must be visible in AE cached inventory before planning: " + stack);
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
        helper.assertTrue(machineNode != null, "formal machine node must exist before wiring");
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
