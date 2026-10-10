package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher;
import cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade;
import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实 Facade.prepare 与适配器 submit 验证批次契约，避免类字节字符串断言。 */
final class NeoEcoBatchDispatchContractTest {

    /** SDK 验收使用真实模组与必需前置，必须实际加载成功后再执行行为断言。 */
    @BeforeAll
    static void requiredModsAreLoaded() {
        assertTrue(ModList.get().isLoaded("neoecoae"), "NeoEco 必须在 FML 中真实加载");
        assertTrue(ModList.get().isLoaded("ldlib2"), "NeoEco 必需前置 ldlib2 必须真实加载");
    }

    private static final AEItemKey INPUT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey OUTPUT = AEItemKey.of(Items.IRON_BLOCK);
    private static final AEItemKey REMAINDER = AEItemKey.of(Items.BUCKET);
    private static final IPatternDetails PATTERN = new IPatternDetails() {
        @Override
        public AEItemKey getDefinition() {
            return OUTPUT;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(OUTPUT, 3L));
        }
    };

    @Test
    void genericDispatcherPreparesAndPushesTheRealFacadeBatchOnce() {
        var inventory = inventory();
        var host = new RecordingHost(4L, true);
        var energy = new RecordingEnergyService();
        PreparedBatchDispatcher dispatcher = host;
        PreparedBatch prepared = prepare(dispatcher, inventory, energy);

        assertNotNull(prepared);
        assertInstanceOf(PreparedBatchImpl.class, prepared);
        assertEquals(4L, prepared.craftCount());
        assertEquals(List.of(new GenericStack(INPUT, 8L)), prepared.inputTotal());
        assertEquals(List.of(new GenericStack(OUTPUT, 12L)), prepared.outputs());
        assertEquals(List.of(new GenericStack(REMAINDER, 4L)), prepared.remainders());
        assertEquals(CraftingCpuHelper.calculatePatternPower(new KeyCounter[]{counter(INPUT, 2L)}) * 4L,
                prepared.power());
        assertEquals(20L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(prepared.push(inventory));
        assertEquals(12L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(1, host.dispatches);
        assertEquals(4L, host.dispatchedCount);
        // 宿主收到单发明细；整批乘法只发生一次。
        assertEquals(List.of(new GenericStack(INPUT, 2L)), host.inputs);
        assertEquals(List.of(new GenericStack(OUTPUT, 3L)), host.outputs);
        assertEquals(List.of(new GenericStack(REMAINDER, 1L)), host.remainders);
        assertEquals(0, host.singlePushes);
        assertEquals(0, energy.modulatedCalls);
        assertEquals(0.0D, energy.modulatedPower);
    }

    @Test
    void rejectedRealSubmissionReturnsMaterialsAndDoesNotChargeEnergy() {
        var inventory = inventory();
        var host = new RecordingHost(4L, false);
        var energy = new RecordingEnergyService();
        PreparedBatch prepared = prepare(host, inventory, energy);

        assertNotNull(prepared);
        assertFalse(prepared.push(inventory));
        assertEquals(20L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(1, host.dispatches);
        assertEquals(0, energy.modulatedCalls);
        assertEquals(0, host.singlePushes);
    }

    @Test
    void zeroHostCapacityIsNotApplicableAndNeverDispatches() {
        var inventory = inventory();
        var host = new RecordingHost(0L, true);
        var energy = new RecordingEnergyService();

        assertNull(prepare(host, inventory, energy));
        assertEquals(20L, inventory.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0, host.dispatches);
        assertEquals(0, energy.modulatedCalls);
    }

    private static PreparedBatch prepare(PreparedBatchDispatcher dispatcher,
            ListCraftingInventory inventory, IEnergyService energy) {
        return dispatcher.prepareBatch(PATTERN, new KeyCounter[]{counter(INPUT, 2L)},
                counter(OUTPUT, 3L), counter(REMAINDER, 1L), inventory, 8L, energy, null, UUID.randomUUID());
    }

    private static ListCraftingInventory inventory() {
        var inventory = new ListCraftingInventory(ignored -> {});
        inventory.insert(INPUT, 20L, Actionable.MODULATE);
        return inventory;
    }

    private static KeyCounter counter(AEItemKey key, long amount) {
        var counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    /** 普通测试宿主调用真实公开 Facade；记录实际批量和单发明细。 */
    private static final class RecordingHost
            implements ICraftingProvider, PreparedBatchDispatcher, ECOFastPathDispatchProvider {
        private final long capacity;
        private final boolean accepted;
        private int dispatches;
        private int singlePushes;
        private long dispatchedCount;
        private List<GenericStack> inputs;
        private List<GenericStack> outputs;
        private List<GenericStack> remainders;

        private RecordingHost(long capacity, boolean accepted) {
            this.capacity = capacity;
            this.accepted = accepted;
        }

        @Override
        public Preparation eco$prepareFastPath(ECOBatchDispatchContext context) {
            if (capacity <= 0L) {
                return null;
            }
            return new Preparation(capacity, null, false, batch -> {
                dispatches++;
                dispatchedCount = batch.craftCount();
                inputs = context.inputItems();
                outputs = context.outputs();
                remainders = context.containerItems();
                return accepted;
            });
        }

        @Override
        public PreparedBatch prepareBatch(IPatternDetails pattern, KeyCounter[] inputs,
                KeyCounter outputs, KeyCounter remainders, ListCraftingInventory inventory,
                long requested, IEnergyService energy, Level level, UUID jobId) {
            var prepared = ECOFastPathFacade.prepare(this, pattern, inputs, outputs, remainders,
                    inventory, requested, CraftingCpuHelper.calculatePatternPower(inputs), energy, level, jobId);
            return prepared == null ? null : new PreparedBatchImpl(prepared);
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(PATTERN);
        }

        @Override
        public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputHolder) {
            singlePushes++;
            return accepted;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    private static final class RecordingEnergyService implements IEnergyService {
        private double modulatedPower;
        private int modulatedCalls;

        @Override
        public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
            if (mode == Actionable.MODULATE) {
                modulatedPower += amount;
                modulatedCalls++;
            }
            return amount;
        }

        @Override
        public double getIdlePowerUsage() {
            return 0.0D;
        }

        @Override
        public double getChannelPowerUsage() {
            return 0.0D;
        }

        @Override
        public double getAvgPowerUsage() {
            return 0.0D;
        }

        @Override
        public double getAvgPowerInjection() {
            return 0.0D;
        }

        @Override
        public boolean isNetworkPowered() {
            return true;
        }

        @Override
        public double injectPower(double amount, Actionable mode) {
            return 0.0D;
        }

        @Override
        public double getStoredPower() {
            return Double.MAX_VALUE;
        }

        @Override
        public double getMaxStoredPower() {
            return Double.MAX_VALUE;
        }

        @Override
        public double getEnergyDemand(double maxRequired) {
            return 0.0D;
        }
    }

}
