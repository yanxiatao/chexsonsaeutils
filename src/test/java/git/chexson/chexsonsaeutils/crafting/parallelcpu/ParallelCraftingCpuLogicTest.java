package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ParallelCraftingCpuLogicTest {

    private static final AEItemKey OUTPUT = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
    private static final AEItemKey REMAINDER = AEItemKey.of(new ItemStack(Items.BUCKET));

    @Test
    void exactCellInitialExtractionTakesEntireWideUsedAndNeverRefillsConsumedPool() {
        var demand = BigInteger.TEN.pow(100).add(BigInteger.valueOf(17));
        var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(new ItemStack(Items.STICK), null);
        cell.insertExact(OUTPUT, demand.multiply(BigInteger.TWO), Actionable.MODULATE, IActionSource.empty());
        var pool = new WideCraftingInventory(ignored -> {});
        var plan = planWithWideUsed(OUTPUT, demand);
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, cell, pool, IActionSource.empty(), null));
        assertEquals(demand, pool.amount(OUTPUT));
        assertEquals(demand, cell.exactAmount(OUTPUT));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
        assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
        pool.extractExact(OUTPUT, demand, Actionable.MODULATE);
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, cell, IActionSource.empty(), () -> true));
        assertEquals(demand, cell.exactAmount(OUTPUT));
    }

    @Test
    void exactCellContinuationSettlesOnlyPersistedPlannedRemainderInOneOperation() {
        var demand = BigInteger.TEN.pow(80);
        var plan = planWithWideUsed(OUTPUT, demand);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insertExact(OUTPUT, BigInteger.valueOf(19), Actionable.MODULATE);
        ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
        var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(new ItemStack(Items.STICK), null);
        cell.insertExact(OUTPUT, demand.multiply(BigInteger.TWO), Actionable.MODULATE, IActionSource.empty());
        var budget = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(demand.subtract(BigInteger.valueOf(19)), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, cell, IActionSource.empty(), () -> budget.getAndIncrement() == 0));
        assertEquals(demand, pool.amount(OUTPUT));
        assertEquals(demand.add(BigInteger.valueOf(19)), cell.exactAmount(OUTPUT));
        assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
    }

    @Test
    void exactCellPartialReceiptDoesNotInventMissingMaterialOrRepeatCollectedAmount() {
        var max = BigInteger.valueOf(Long.MAX_VALUE);
        var demand = max.multiply(BigInteger.TEN);
        var plan = planWithWideUsed(OUTPUT, demand);
        var cell = new git.chexson.chexsonsaeutils.cell.InfinityCellInventory(new ItemStack(Items.STICK), null);
        var have = max.multiply(BigInteger.TWO).add(BigInteger.valueOf(7));
        cell.insertExact(OUTPUT, have, Actionable.MODULATE, IActionSource.empty());
        var pool = new WideCraftingInventory(ignored -> {});
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, cell, pool, IActionSource.empty(), null));
        assertEquals(have, pool.amount(OUTPUT));
        assertEquals(BigInteger.ZERO, cell.exactAmount(OUTPUT));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
        assertEquals(demand.subtract(have), job.initialItemsRemaining.amount(OUTPUT));
        cell.insertExact(OUTPUT, demand, Actionable.MODULATE, IActionSource.empty());
        assertEquals(demand.subtract(have), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, cell, IActionSource.empty(), () -> true));
        assertEquals(demand, pool.amount(OUTPUT));
        assertEquals(have, cell.exactAmount(OUTPUT));
    }

    @Test
    void resumedJobRecognizesExplicitSupplyWithoutDiscardingInventoryOrWaitingOutputs() {
        BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
        var plan = planWithWideUsed(OUTPUT, amount);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        job.initialItemsRemaining.insertExact(OUTPUT, amount, Actionable.MODULATE);
        ((WideCraftingInventory) job.waitingFor).insertExact(OUTPUT, amount, Actionable.MODULATE);
        var supply = new LyingInfiniteStorage(OUTPUT);
        ParallelCraftingCpuLogic.refreshJobUnboundedSupply(job, supply, IActionSource.empty());
        assertTrue(job.isUnboundedSupply(OUTPUT));
        assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
        assertEquals(amount, WideCraftingInventory.amount(job.waitingFor, OUTPUT));
        var keys = new java.util.HashSet<AEKey>();
        var pool = new WideCraftingInventory(ignored -> {});
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, supply, pool, IActionSource.empty(), keys));
        assertTrue(keys.contains(OUTPUT));
        assertEquals(BigInteger.ZERO, pool.amount(OUTPUT));
    }

    @Test
    void infiniteSupplyDoesNotExemptUnconfiguredComponentVariant() {
        var named = new ItemStack(Items.IRON_INGOT);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("有限变体"));
        var variant = AEItemKey.of(named);
        assertEquals(OUTPUT.getPrimaryKey(), variant.getPrimaryKey());
        var plan = planWithWideUsed(variant, BigInteger.TEN);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        job.noteUnboundedSupply(OUTPUT);
        assertTrue(job.isUnboundedSupply(OUTPUT));
        assertFalse(job.isUnboundedSupply(variant));
        var reservation = ParallelPlanReservation.build(List.of(), Map.of(), null, java.util.Set.of(OUTPUT));
        assertTrue(reservation.isUnboundedSupply(OUTPUT));
        assertFalse(reservation.isUnboundedSupply(variant));
        var pool = new WideCraftingInventory(ignored -> {});
        assertNotNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, new LyingInfiniteStorage(OUTPUT),
                pool, IActionSource.empty(), new java.util.HashSet<>()));
        assertEquals(BigInteger.ZERO, pool.amount(variant));
    }

    @Test
    void resumedFiniteStorageRevokesOldInfiniteIdentityWithoutChangingMaterialOrWaiting() {
        BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
        var job = new ParallelExecutingCraftingJob(planWithWideUsed(OUTPUT, amount), ignored -> {},
                (CraftingLink) null, null);
        job.noteUnboundedSupply(OUTPUT);
        job.initialItemsRemaining.insertExact(OUTPUT, amount, Actionable.MODULATE);
        ((WideCraftingInventory) job.waitingFor).insertExact(OUTPUT, amount, Actionable.MODULATE);
        var finite = new WideNetworkStorage(OUTPUT, amount);
        class ExactFiniteView implements appeng.api.storage.MEStorage,
                git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply {
            @Override public Snapshot readExactSupply() {
                return new Snapshot(Map.of(OUTPUT, finite.remainingTotal()), java.util.Set.of());
            }
            @Override public long extract(AEKey key, long count, Actionable mode, IActionSource source) {
                return finite.extract(key, count, mode, source);
            }
            @Override public Component getDescription() { return Component.literal("有限大数库存"); }
        }
        ParallelCraftingCpuLogic.refreshJobUnboundedSupply(job, new ExactFiniteView(), IActionSource.empty());
        assertFalse(job.isUnboundedSupply(OUTPUT));
        assertEquals(amount, job.initialItemsRemaining.amount(OUTPUT));
        assertEquals(amount, WideCraftingInventory.amount(job.waitingFor, OUTPUT));
        assertEquals(amount, finite.remainingTotal());
    }

    @Test
    void undeclaredSimulationSuccessAndRealRefusalIsMissingMaterial() {
        var plan = planWithWideUsed(OUTPUT, BigInteger.TEN);
        var keys = new java.util.HashSet<AEKey>();
        var pool = new WideCraftingInventory(ignored -> {});
        var storage = new appeng.api.storage.MEStorage() {
            @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                return mode == Actionable.SIMULATE ? amount : 0;
            }
            @Override public Component getDescription() { return Component.literal("拒收的普通盘"); }
        };
        assertNotNull(ParallelCraftingCpuLogic.tryExtractInitialItems(plan, storage, pool, IActionSource.empty(), keys));
        assertTrue(keys.isEmpty());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        var missing = new KeyCounter(); missing.add(OUTPUT, 10);
        ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null, storage, IActionSource.empty(), pool);
        assertFalse(job.isUnboundedSupply(OUTPUT));
        assertEquals(BigInteger.TEN, WideCraftingInventory.amount(job.waitingFor, OUTPUT));
    }


    @Test
    void plannedInitialExtractionNeverExceedsWideUsedEvenAfterPoolIsConsumed() {
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        BigInteger needed = max.multiply(BigInteger.TWO).add(BigInteger.valueOf(17));
        var plan = planWithWideUsed(OUTPUT, needed);
        var storage = new WideNetworkStorage(OUTPUT, needed.multiply(BigInteger.TEN));
        var pool = new WideCraftingInventory(ignored -> {});
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                plan, storage, pool, IActionSource.empty(), new java.util.HashSet<>()));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
        assertEquals(max.add(BigInteger.valueOf(17)), job.initialItemsRemaining.amount(OUTPUT));
        var asks = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(max, ParallelCraftingCpuLogic.extractPlannedInitialItems(job, pool, storage,
                IActionSource.empty(), () -> asks.getAndIncrement() < 1));
        assertEquals(BigInteger.valueOf(17), job.initialItemsRemaining.amount(OUTPUT));
        assertEquals(BigInteger.valueOf(17), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, storage, IActionSource.empty(), () -> true));
        assertEquals(needed, pool.amount(OUTPUT));
        pool.extractExact(OUTPUT, needed, Actionable.MODULATE);
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, storage, IActionSource.empty(), () -> true));
        assertEquals(needed.multiply(BigInteger.valueOf(9)), storage.remainingTotal());
        assertEquals(3, storage.extractAsks, "初抽和两次续取后封账，空池也不重新向网络要料");
    }

    @Test
    void plannedInitialExtractionDoesNotInventMaterialDemandFromTasks() {
        var pattern = new TestPattern(OUTPUT, new IPatternDetails.IInput[0]);
        var plan = new CraftingPlan(new GenericStack(OUTPUT, 1), 0, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, Long.MAX_VALUE));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        var pool = new WideCraftingInventory(ignored -> {});
        var storage = new WideNetworkStorage(OUTPUT, BigInteger.valueOf(Long.MAX_VALUE).pow(2));
        ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, storage, IActionSource.empty(), () -> true));
        assertEquals(0, storage.extractAsks);
        assertEquals(BigInteger.ZERO, pool.amount(OUTPUT));
    }

    @Test
    void shortPlannedExtractionKeepsOnlyUndeliveredAmountForNextTick() {
        var plan = planWithWideUsed(OUTPUT, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, (CraftingLink) null, null);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insert(OUTPUT, Long.MAX_VALUE, Actionable.MODULATE);
        ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
        var first = new WideNetworkStorage(OUTPUT, BigInteger.valueOf(3));
        assertEquals(BigInteger.valueOf(3), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, first, IActionSource.empty(), () -> true));
        assertEquals(BigInteger.valueOf(7), job.initialItemsRemaining.amount(OUTPUT));
        var second = new WideNetworkStorage(OUTPUT, BigInteger.valueOf(50));
        assertEquals(BigInteger.valueOf(7), ParallelCraftingCpuLogic.extractPlannedInitialItems(
                job, pool, second, IActionSource.empty(), () -> true));
        assertEquals(BigInteger.valueOf(43), second.remainingTotal());
        assertEquals(BigInteger.ZERO, job.initialItemsRemaining.amount(OUTPUT));
    }

    @Test
    void plannedInitialRemainderSurvivesJobSaveWithoutResettingTheBudget() {
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        var plan = planWithWideUsed(OUTPUT, max.multiply(BigInteger.valueOf(3)));
        var link = new CraftingLink(appeng.crafting.execution.CraftingCpuHelper.generateLinkData(
                UUID.randomUUID(), true, false), (appeng.api.networking.crafting.ICraftingCPU) null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {}, link, null);
        var pool = new WideCraftingInventory(ignored -> {});
        pool.insert(OUTPUT, Long.MAX_VALUE, Actionable.MODULATE);
        ParallelCraftingCpuLogic.initializePlannedInitialItems(job, plan, pool);
        var storage = new WideNetworkStorage(OUTPUT, max.multiply(BigInteger.valueOf(10)));
        var asks = new java.util.concurrent.atomic.AtomicInteger();
        ParallelCraftingCpuLogic.extractPlannedInitialItems(job, pool, storage,
                IActionSource.empty(), () -> asks.getAndIncrement() < 1);
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var restored = new ParallelExecutingCraftingJob(job.writeToNBT(registries), registries,
                ignored -> {}, new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L));
        assertEquals(max, restored.initialItemsRemaining.amount(OUTPUT));
        assertEquals(max, ParallelCraftingCpuLogic.extractPlannedInitialItems(restored, pool,
                storage, IActionSource.empty(), () -> true));
        assertEquals(BigInteger.ZERO, ParallelCraftingCpuLogic.extractPlannedInitialItems(restored,
                pool, storage, IActionSource.empty(), () -> true));
        assertEquals(max.multiply(BigInteger.valueOf(8)), storage.remainingTotal());
    }

    @Test
    void wallClockBudgetRemainsTheOnlyExhaustionGuard() {
        var ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(1L));
        ledger.resetForTick(1L, 1L);

        assertFalse(ledger.hasTimeBudget(2L));
        assertTrue(ledger.isExhausted());
        assertTrue(ledger.exhaustedTypes().contains(ParallelCpuGridBudgetLedger.BudgetType.TIME));
    }

    @Test
    void unavailableBatchFallsBackWithoutBeingClassifiedAsPushRejection() {
        assertEquals(
                ParallelCraftingCpuLogic.PreparedBatchPushResult.NOT_BATCHED,
                ParallelCraftingCpuLogic.pushPreparedBatchCandidate(
                        null, new ListCraftingInventory(ignored -> {
                        })));
        assertEquals(
                ParallelCraftingCpuLogic.PreparedBatchPushResult.NOT_BATCHED,
                ParallelCraftingCpuLogic.pushPreparedBatchCandidate(
                        preparedBatch(1L, List.of(), List.of(), 0.0D),
                        new ListCraftingInventory(ignored -> {
                        })));
    }

    @Test
    void rejectedPreparedBatchIsClassifiedAsPushRejection() {
        PreparedBatch rejected = new PreparedBatch() {
            @Override
            public boolean push(ListCraftingInventory inventory) {
                return false;
            }

            @Override
            public long craftCount() {
                return 2L;
            }

            @Override
            public List<GenericStack> inputTotal() {
                return List.of();
            }

            @Override
            public List<GenericStack> outputs() {
                return List.of();
            }

            @Override
            public List<GenericStack> remainders() {
                return List.of();
            }

            @Override
            public double power() {
                return 0.0D;
            }
        };

        assertEquals(
                ParallelCraftingCpuLogic.PreparedBatchPushResult.REJECTED,
                ParallelCraftingCpuLogic.pushPreparedBatchCandidate(
                        rejected, new ListCraftingInventory(ignored -> {
                        })));
    }

    @Test
    void officialBatchSuccessAccountsCompleteCraftCountInOfficialOrder() {
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 17L;
        var energy = new RecordingEnergyService();
        PreparedBatch prepared = preparedBatch(
                12L,
                List.of(new GenericStack(OUTPUT, 36L)),
                List.of(new GenericStack(REMAINDER, 12L)),
                48.0D);

        ParallelCraftingCpuLogic.applyPreparedBatchSuccess(job, task, prepared, energy);

        assertEquals(5L, task.value);
        assertEquals(36L, job.waitingFor.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(12L, job.waitingFor.extract(REMAINDER, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(12.0D, job.timeTracker.getStartedUnits());
        assertEquals(48.0D, energy.modulatedPower);
        assertEquals(1, energy.modulatedCalls);
    }

    private static ICraftingPlan emptyPlan() {
        return new ICraftingPlan() {
            private final KeyCounter empty = new KeyCounter();

            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return empty;
            }

            @Override
            public KeyCounter emittedItems() {
                return empty;
            }

            @Override
            public KeyCounter missingItems() {
                return empty;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return Map.of();
            }
        };
    }

    private static PreparedBatch preparedBatch(
            long count,
            List<GenericStack> outputs,
            List<GenericStack> remainders,
            double power
    ) {
        return new PreparedBatch() {
            @Override
            public boolean push(ListCraftingInventory inventory) {
                return true;
            }

            @Override
            public long craftCount() {
                return count;
            }

            @Override
            public List<GenericStack> inputTotal() {
                return List.of();
            }

            @Override
            public List<GenericStack> outputs() {
                return outputs;
            }

            @Override
            public List<GenericStack> remainders() {
                return remainders;
            }

            @Override
            public double power() {
                return power;
            }
        };
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

    /**
     * 销账的豁免必须与封顶同一条判据：<b>只看这一格</b>是不是把该件原样退回。
     *
     * <p>原先按样板级判（该样板任一格自偿还就整笔不销账），于是同一张样板里
     * 模具槽吃过的键，普通消耗格的提走量也不进配额账 ⇒ 份额永远扣不完 ⇒
     * 实机 {@code site=batch minecraft:glass ate=11992458237 share=11992458236}。
     */
    @Test
    void billingExemptionIsPerSlotNotPerPattern() {
        var glass = AEItemKey.of(Items.GLASS);
        var mold = new TestInput(new GenericStack(glass, 1L), glass);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 6L));

        ParallelCraftingCpuLogic.recordTakenInputs(job, pattern,
                new KeyCounter[]{holds(glass, 4L), holds(glass, 3L)});

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertEquals(3L, eaten == null ? 0L : eaten.getLong(glass),
                "只有模具槽那一格豁免，普通格的 3 枚必须销到配额上");
        assertEquals(7L, job.accountTaken.getLong(glass), "流水账两格都得记");
    }

    /**
     * NeoEco 批量快路径的销账<b>并回同一个出口</b>：豁免只剩槽级那一条判据，不再有第二本账。
     *
     * <p>改前 {@code recordNeoEcoTakenInputs} 自己写两本账、并且用<b>样板级</b> {@code isRepaidKey}
     * 判豁免：这张样板里有一格把玻璃原样退回，于是快路径提走的 16 枚<b>整笔</b>不进配额账
     * ⇒ 份额永远扣不完 ⇒ 下一次还能按满额取。现在它只把按键汇总的明细按一发样本摊回槽号
     * （每格 1 枚 × 8 发），再交回唯一出口 {@code recordTakenInputs}。
     */
    @Test
    void neoEcoFastPathWriteoffRidesTheSingleSlotLevelExit() {
        var glass = AEItemKey.of(Items.GLASS);
        var mold = new TestInput(new GenericStack(glass, 1L), glass);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));

        ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                new KeyCounter[]{holds(glass, 1L), holds(glass, 1L)}, 8L,
                List.of(new GenericStack(glass, 16L)), outflow(glass, 16L), null);

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertEquals(8L, eaten == null ? 0L : eaten.getLong(glass),
                "摊回槽号后仍按槽级判据销账：只有模具槽那一格豁免，普通格的 8 枚必须进配额账");
        assertEquals(16L, job.accountTaken.getLong(glass),
                "流水账两格都得记：与常规推送同一个出口、同一口径");

        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(92L, reservation.remainingQuota(pattern, glass),
                "吃掉 8 枚份额后还剩 92 枚读得回来：整笔豁免就是份额永远扣不完");
    }

    /**
     * 同一个出口的<b>摊不平</b>形状（独立复核定性的零用例分支）：快路径给的汇总比
     * {@code 样本 × 份数} 多出 4 枚（工具耐久、组件变化这类对不上）。
     *
     * <p>模具槽那 8 枚只进流水账、不进配额账。改前是把整笔 20 枚都搬到普通格上 ⇒ 配额被多销 8 枚
     * ⇒ 份额提前归零 ⇒ 尾段那一格永远开不了工，正是实机 {@code over=[ate=N share=N−1]} 那一型。
     */
    @Test
    void unbalancedFastPathTotalNeverBillsTheMouldShareOnTheConsumingSlot() {
        var glass = AEItemKey.of(Items.GLASS);
        var mold = new TestInput(new GenericStack(glass, 1L), glass);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));

        ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                new KeyCounter[]{holds(glass, 1L), holds(glass, 1L)}, 8L,
                List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), null);

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertEquals(12L, eaten == null ? 0L : eaten.getLong(glass),
                "算得清的模具槽 8 枚不许搬到普通格：配额只销 20−8");
        assertEquals(20L, job.accountTaken.getLong(glass), "流水账仍要记满：提走了就是提走了");

        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(88L, reservation.remainingQuota(pattern, glass),
                "配额读回 88：多销那 8 枚就是把尾段那一格夹死");
    }

    /**
     * 出账量<b>由池子的宽域现量差派生</b>（F1 的派生侧）：一发真需越出 {@code long} 时，
     * 现量差照样是精确值——而对方那份按键汇总的 {@code long} 读数在这组数上早已钉在天花板。
     *
     * <p>量级照实机 2026-09-26 那一跑：池里 {@code 1.82e19} 枚（{@code >long}），这一批真出账
     * {@code 9e18} 枚，对方 {@code KeyCounter} 交给我们的 {@code inputTotal()} 只能是
     * {@link Long#MAX_VALUE} 那个哨兵。
     */
    @Test
    void neoEcoOutflowIsMeasuredFromThePoolWideReadingNotFromTheReportedTotal() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var pool = new WideCraftingInventory(ignored -> {
        });
        var inPool = BigInteger.valueOf(Long.MAX_VALUE)
                .add(BigInteger.valueOf(9_000_000_000_000_000_000L));
        pool.insertExact(glass, inPool, Actionable.MODULATE);
        var reported = List.of(new GenericStack(glass, Long.MAX_VALUE));
        var prototype = new KeyCounter[]{holds(glass, 1L)};

        var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(pattern, prototype, reported);
        var before = ParallelCraftingCpuLogic.readPoolWide(pool, keys);
        pool.extractExact(glass, BigInteger.valueOf(9_000_000_000_000_000_000L), Actionable.MODULATE);
        var outflow = ParallelCraftingCpuLogic.measurePoolOutflow(pool, keys, before);

        assertEquals(BigInteger.valueOf(9_000_000_000_000_000_000L), outflow.getExact(glass),
                "出账＝前后两次宽域现量之差：越界那一截是精确值，不钉天花板");
        assertEquals(inPool.subtract(BigInteger.valueOf(9_000_000_000_000_000_000L)),
                WideCraftingInventory.amount(pool, glass),
                "池子剩下的也是宽域真值：派生不靠任何一份饱和读数");
    }

    /**
     * 销账量<b>不许从对方报的饱和读数里取</b>（F1 的后果侧，实机 {@code ate=9223372036854775807
     * share=1056640106232500} 那一型），而且这条结论<b>必须由生产本体给出</b>：驱动
     * {@code ParallelCraftingCpuLogic#pushPreparedBatch} 本体，宿主 {@code push} 真从我们这块池子里
     * 扣料，{@code inputTotal()} 交回 {@link Long#MAX_VALUE} 那份哨兵读数。
     *
     * <p>形状：一发样本这一格记 500 枚，宿主按它自己那份配方每发真扣 2300 枚 ⇒ 池子净差值大于
     * {@code 样本 × 份数} ⇒ 走"摊不平"分岔 ⇒ 残量被 {@code takingSlot} 归到<b>有槽</b>那一号。
     * 上一刀把销账量换成池子差值对本症状是<b>数值空操作</b>（{@code extractExact} 全有全无 ⇒
     * 两个读数同值），真正的洞是那一号压根没吃过 {@code capByRemainingShare} ⇒ 配额账顶到天花板
     * ⇒ {@code quotaExact} 恒 0 ⇒ {@code usableFor=0} ⇒ {@code locked502}。
     *
     * <p>验红：把 {@code #attach} 里的封顶摘回"只夹 {@code target == shape} 那一支"，这条红在
     * "只许销 1000 枚"那一句；把本体的 {@code planReservation} 漏接成 {@code null} 同样红。
     */
    @Test
    void neoEcoWriteoffNeverBillsTheSaturatedReadingTheOtherSideReports() {
        var water = AEItemKey.of(Items.WATER_BUCKET);
        var consumed = new TestInput(new GenericStack(water, 500L), null);
        var pattern = new TestPattern(water, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 5_000L;
        job.tasks.put(pattern, task);
        // 实机形状：这一族只分到 1000 枚，池子里却躺着 9.22e18 枚
        MaterialAllocationFixtures.assign(job, pattern, share(water, 1_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insertExact(water, BigInteger.valueOf(Long.MAX_VALUE), Actionable.MODULATE);
        // 宿主每发真扣 2300 枚（样本只记 500），交回的按键读数钉在天花板上
        var host = new FakeNeoEcoHost(pattern, water, 2_300L, -1L, Long.MAX_VALUE);

        long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                job, pool, new ParallelPoolLedger(), null, null,
                pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                new RecordingEnergyService(), 1L, null, null, reservation);

        assertEquals(2L, pushed, "发数仍按样本那一维夹：1000/500 = 2 发");
        // F5：这里原有 `assertEquals(1, host.prepares, ...)` 已恒真——本 fixture 的 count 恒等于
        // line913 夹过的 requested，affordable 与 craftCount 出自同一算式同一份份额 ⇒ 两次读数逐字相等，
        // "第二次申报没有发生"在这个形状上没有观察点。折回那一支真正的牙在
        // #reportedCeilingKeyIsFoldedByTheSampleDimensionNotSkipped（贪心宿主 prepares=2）与
        // #requestSideFoldIsObservableAsEcoFoldTimesAndFirings（ecoFold=1/4000）上，这里不再钉恒等数字。
        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertNotNull(eaten, "普通消耗格那一截必须进配额账");
        assertEquals(1_000L, eaten.getLong(water),
                "物理被扣 4600 枚也只许按份额销 1000 枚：有槽那一号不再是绕过封顶的漏口");
        assertTrue(eaten.getExact(water).compareTo(BigInteger.valueOf(1_000L)) <= 0L,
                "已吃 ≤ 份额：拿饱和读数入账就是 ate=Long.MAX 而 share=1000");
        assertEquals(4_600L, job.accountTaken.getLong(water),
                "流水账收未夹原值：超销那一截必须在账上看得见，否则下一跑对这一型又是盲的");
        assertEquals(0L, reservation.remainingQuota(pattern, water),
                "份额照实销尽：夹住不等于把这一族的口粮抹到别人头上");
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.valueOf(4_600L)),
                WideCraftingInventory.amount(pool, water),
                "池子真扣 4600 枚：封顶只夹账，不动物理交割");
    }

    /**
     * 同一个 {@code (槽号,键)} 被 {@code attach} 两次时必须<b>累加</b>，不许第二次抹掉第一次（F2）。
     *
     * <p>形状取自"摊不平"这一支：样本 1 枚/发 × 8 发＝8 枚先归回<b>自偿还格</b>，残量 12 枚在
     * "没有非自偿还格可退"时被 {@code takingSlot} 退到<b>同一格</b>。老写法是
     * {@code WideKeyCounter#setExact}（覆盖）：{@code holder} 那本 {@code add} 了两笔（20），
     * 宽域那本却只剩第二笔（12）⇒ 账比物理出账少一截。
     */
    @Test
    void repeatedAttachOnTheSameSlotAndKeyAccumulatesInsteadOfOverwriting() {
        var glass = AEItemKey.of(Items.GLASS);
        // 唯一一格就是自偿还格 ⇒ 残量只能退到它自己：两次 attach 落在同一个 (index,key)
        var mold = new TestInput(new GenericStack(glass, 1L), glass);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));

        ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                new KeyCounter[]{holds(glass, 1L)}, 8L,
                List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), null);

        assertEquals(20L, job.accountTaken.getLong(glass),
                "同一 (槽号,键) 两次 attach 必须累加：覆盖就抹掉了第一次那 8 枚");
        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertEquals(0L, eaten == null ? 0L : eaten.getLong(glass),
                "自偿还格整格豁免：累加不改豁免判据，配额账仍一枚不进");
    }

    /**
     * "归不到任何一格"那一号（{@code slot == null}）不再是绕过封顶的销账口（F3）：它吃的是
     * 正常格<b>同一份</b> {@code quotaExact} 算式，残量超份额就<b>照实少销</b>到份额上限——
     * 既不是原样多销，也不是静默归零。
     *
     * <p>取料侧 {@code clampByRemainingQuota} 只夹样本槽看得见的键，这一截在它视野之外：
     * 不补这一夹，归不到格的残量就能把整族份额一口吃空。还原那一行（去掉封顶）时这条用例
     * 红在"销账被夹到 30"与"份额读回 0"上。
     */
    @Test
    void unattributableResidualIsCappedByTheSameQuotaFormula() {
        var glass = AEItemKey.of(Items.GLASS);
        var bucket = AEItemKey.of(Items.BUCKET);
        // 样本槽里只有 bucket ⇒ glass 这一截归不到任何一格，只能挂最后一号（slot==null）
        var consumed = new TestInput(new GenericStack(bucket, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 30L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                new KeyCounter[]{holds(bucket, 1L)}, 1L,
                List.of(new GenericStack(glass, 100L)), outflow(glass, 100L), reservation);

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertNotNull(eaten, "夹住不等于不销账：这一截照样要走配额账，只是不许超份额");
        assertEquals(30L, eaten.getLong(glass),
                "残量 100 超份额 30：销账被同一份 quotaExact 夹到 30（照实少销，不是归零）");
        assertEquals(100L, job.accountTaken.getLong(glass),
                "流水账收未夹原值：只有配额账吃夹后的数，超销那一截必须在诊断账上留着");
        assertEquals(0L, reservation.remainingQuota(pattern, glass),
                "夹完就是份额用尽——多销那 70 枚从来不是这张样板的口粮");
    }

    /**
     * 请求侧那一刀（本刀的正题）：<b>让"物理被扣的量"结构性不可能越份额</b>。
     *
     * <p>宿主按机器容量自己定发数，第一次申报就是请求那一维的数倍（实机 {@code units=500} 对
     * {@code 每发=1000} 那一型：它每发真扣 2 枚，一发样本只记 1 枚）。越界就<b>少请求</b>——
     * 照夹后的发数向同一位宿主另要一批，既不夹销账、不归零，更不是在这里拒收。
     *
     * <p>摘掉 {@code #pushPreparedBatch} 里那道 push 前复核（{@code dispatchFiringsInsideQuota}）：
     * 那一批 {@code craftCount=4000} 大于请求的 1200 发，会被现成的 dispatch 上限整批判成拒收
     * （{@code pushed=0}），这条红在"实收 600 发"那一句——正是用户红线点名的"把拒规划当修复"。
     */
    @Test
    void neoEcoFastPathReasksFewerFiringsInsteadOfBillingOverShare() {
        var water = AEItemKey.of(Items.WATER_BUCKET);
        var consumed = new TestInput(new GenericStack(water, 1L), null);
        var pattern = new TestPattern(water, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 5_000L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(water, 1_200L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(water, 20_000L, Actionable.MODULATE);
        // 老实交总量的宿主：每发 2 枚，第一次按机器容量申报 4000 发（远超份额折得出的发数）
        var host = new FakeNeoEcoHost(pattern, water, 2L, 4_000L, 0L);

        long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                job, pool, new ParallelPoolLedger(), null, null,
                pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                new RecordingEnergyService(), 1L, null, null, reservation);

        assertEquals(600L, pushed,
                "1200 枚份额 ÷ 每发 2 枚 = 600 发：越界就少请求，照实夹到份额另要一批");
        assertEquals(2, host.prepares, "同一位宿主被另要了一批更小的：少请求，不是拒收");
        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertNotNull(eaten);
        assertEquals(1_200L, eaten.getLong(water), "配额账正好销满 1200 枚：一枚都不许多");
        assertEquals(1_200L, job.accountTaken.getLong(water),
                "物理出账 == 请求量：这一型不必靠销账侧兜底，流水账也不虚增");
        assertEquals(BigInteger.valueOf(18_800L), WideCraftingInventory.amount(pool, water),
                "池子只少了这一批真交出去的那 1200 枚");
    }

    /**
     * 回归对照（R1(b)）：份额充足 ⇒ 请求量与 {@code 69872f5} 之前逐字相同（防封顶做成普遍缩批）。
     *
     * <p>样本 1 枚/发、宿主每发也扣 1 枚、份额 5000 枚 → 请求 100 发 → 应实收 100 发、销账 100 枚。
     * 把 {@code dispatchFiringsInsideQuota} 做成无条件缩批就会把这一条红在 pushed=100 上。
     */
    @Test
    void quotaCapLeavesSufficientShareUntouched() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 500L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 5_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(glass, 10_000L, Actionable.MODULATE);
        // 每发 1 枚、无 greedy 覆写、无读数覆写 → 与样本逐字同向
        var host = new FakeNeoEcoHost(pattern, glass, 1L, 0L, 0L);

        long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                job, pool, new ParallelPoolLedger(), null, null,
                pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                new RecordingEnergyService(), 1L, null, null, reservation);

        assertEquals(500L, pushed, "500 发任务、份额 5000/1=5000 不夹——与 69872f5 前逐字相同");
        assertEquals(500L, job.accountTaken.getLong(glass), "流水账 == 物理出账 == 请求量");
        assertEquals(500L, MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getLong(glass),
                "配额账 == 流水账：份额充足时封顶不缩批");
    }

    /**
     * N1（P1，崩 lane）：宿主 {@code prepare} 交回 null（mixin {@code eco$prepareFastPath} 在
     * 机器 capacity≤0 时明写返回 null）时，{@code #pushPreparedBatch} 必须走"这一批不适用"的出口，
     * 安静地把这一条样板留在快路径之外——不抛异常、不崩 tick。
     *
     * <p>旧实现在拿到 {@code prepared} 之后直接 {@code prepared.craftCount()} /
     * {@code prepared.inputTotal()} ⇒ CPU tick 内 NPE，整条 lane 崩（本条红在异常上）。
     */
    @Test
    void nullPrepareFromHostQuietsTheSampleInsteadOfCrashingTheLane() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 5_000L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(glass, 20_000L, Actionable.MODULATE);

        long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                job, pool, new ParallelPoolLedger(), null, null,
                pattern, job.tasks.entrySet().iterator().next(), List.of(new NullPrepareHost(pattern)),
                new RecordingEnergyService(), 1L, null, null, reservation);

        assertEquals(0L, pushed, "宿主交回 null ⇒ 这一条样板安静地不走快路径，返回 0 而非崩 tick");
        assertEquals(BigInteger.valueOf(20_000L), WideCraftingInventory.amount(pool, glass),
                "没有 prepare 成功就没有物理出账：池子一枚不少");
    }

    /**
     * N2（P1，静默永久关掉快路径）①：申报里含一个"配给表无此族"的键 ⇒ 该键没有配额口径，
     * 不参与折发数 ⇒ 快路径照走（旧实现把它当"用完"夹成 0 ⇒ {@code return 0} ⇒ 红）。
     */
    @Test
    void reportedKeyWithoutAnyQuotaBasisDoesNotFoldTheFastPathAway() {
        var glass = AEItemKey.of(Items.GLASS);
        var snow = AEItemKey.of(Items.SNOW_BLOCK);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 100L;
        job.tasks.put(pattern, task);
        // 配给表里有 glass，但没有 snow 这一族。
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 100L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertFalse(reservation.hasQuotaBasis(pattern, snow),
                "配给表里没有这一族 ⇒ 没有配额口径");

        long affordable = ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                reservation, pattern, new KeyCounter[]{holds(glass, 1L)},
                List.of(new GenericStack(snow, 10L)), 10L);

        assertEquals(10L, affordable,
                "样本没看见、配给表也没这一族的键 ⇒ 不折发数（旧实现 return 0 就是关掉快路径）");
    }

    /**
     * N2（P1）②（控制臂，防把两型一起放开）：申报里的键确有份额且已用完 ⇒ 仍照实 {@code return 0}。
     */
    @Test
    void reportedKeyWithSpentQuotaStillFoldsTheFastPathToZero() {
        var glass = AEItemKey.of(Items.GLASS);
        var bucket = AEItemKey.of(Items.BUCKET);
        var consumed = new TestInput(new GenericStack(bucket, 1L), null);
        var pattern = new TestPattern(bucket, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 100L;
        job.tasks.put(pattern, task);
        var planned = new WideKeyCounter();
        planned.set(bucket, 5_000L);
        planned.set(glass, 100L);
        MaterialAllocationFixtures.assign(job, pattern, planned);
        job.addPlanMaterialTaken(pattern, glass, 100L);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertTrue(reservation.hasQuotaBasis(pattern, glass),
                "这一族确有配给（原配 100 枚）⇒ 有配额口径");
        assertEquals(0L, reservation.remainingQuota(pattern, glass), "但份额已用完 ⇒ 读出 0");

        long affordable = ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                reservation, pattern, new KeyCounter[]{holds(bucket, 1L)},
                List.of(new GenericStack(glass, 10L)), 10L);

        assertEquals(0L, affordable, "确有份额且已用完 ⇒ 仍照实 return 0，不与'没有口径'一并放开");
    }

    /**
     * F1（P1）①：配给表里<b>确有这一族、但条目是 0 值</b>（{@code #mergePlannedShare} 保住"0 值条目
     * 也在册"的形状）⇒ 这一族<b>有配额口径</b> ⇒ 请求侧照旧折（不许放过），份额仍只由
     * {@code ParallelPlanReservation#quotaExact} 交（这里交 0）。
     *
     * <p>摘掉那一号判据（回到 {@code shareOf(...).signum() > 0}）时这条红两处：
     * {@code hasQuotaBasis} 读出 {@code false}、{@code affordable} 读出 {@code 10}。
     */
    @Test
    void reportedKeyWithZeroValuedPlanEntryStillFoldsTheRequestSide() {
        var glass = AEItemKey.of(Items.GLASS);
        var bucket = AEItemKey.of(Items.BUCKET);
        var consumed = new TestInput(new GenericStack(bucket, 1L), null);
        var pattern = new TestPattern(bucket, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 100L;
        job.tasks.put(pattern, task);
        var planned = new WideKeyCounter();
        planned.set(bucket, 5_000L);
        planned.set(glass, 0L);
        MaterialAllocationFixtures.assign(job, pattern, planned);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        assertTrue(reservation.hasQuotaBasis(pattern, glass),
                "0 值条目也在册 ⇒ 这一族确有配额口径（份额 0 不是没口径）");
        assertEquals(0L, reservation.remainingQuota(pattern, glass), "份额真值仍只由 quotaExact 交：这里是 0");

        long affordable = ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                reservation, pattern, new KeyCounter[]{holds(bucket, 1L)},
                List.of(new GenericStack(glass, 10L)), 10L);

        assertEquals(0L, affordable,
                "份额 0 的族照样折请求量：放过它就是让物理多拿的那一截无主（over= 也不亮）");
    }

    /**
     * F1（P1）②（控制臂，防把"没口径"那一型顺手放开成"有口径"）：表里<b>压根没有这一族</b>，
     * 以及<b>这张样板压根不在表里</b> ⇒ 请求侧不折（保住 0492755 的"没口径不静默关路"），
     * 样板不在表时销账侧也不夹（{@code quotaExact} 交 {@code null} = 没有口径，不是 0）。
     *
     * <p>把新判据写成"表里有这张样板就算有口径"（丢掉族级检查）时，臂 A 红在
     * {@code hasQuotaBasis=false} 与 {@code affordable=10}；写成"assigned 非 null 就算口径"
     * （丢掉 {@code isEmpty()} 那一条）时，臂 B 红在 {@code capByRemainingShare} 交出 0。
     */
    @Test
    void familyAbsentFromPlanTableNeitherFoldsTheRequestNorClosesTheFastPath() {
        var glass = AEItemKey.of(Items.GLASS);
        var snow = AEItemKey.of(Items.SNOW_BLOCK);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 100L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 5_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        // 臂 A：样板在表里、这一族不在 ⇒ 没口径 ⇒ 不折
        assertFalse(reservation.hasQuotaBasis(pattern, snow), "表里没有这一族 ⇒ 没有配额口径");
        assertEquals(10L, ParallelCraftingCpuLogic.dispatchFiringsInsideQuota(
                        reservation, pattern, new KeyCounter[]{holds(glass, 1L)},
                        List.of(new GenericStack(snow, 10L)), 10L),
                "没口径的申报键不许把发数折成 0：那是把快路径静默永久关掉");

        // 臂 B：这张样板不在表里 ⇒ 没口径 ⇒ 销账侧也不夹（null 不是 0）
        var unplanned = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        assertFalse(reservation.hasQuotaBasis(unplanned, snow), "样板不在表里 ⇒ 没有配额口径");
        assertEquals(BigInteger.TEN, ParallelCraftingCpuLogic.capByRemainingShare(
                        BigInteger.TEN, reservation, unplanned, snow, null, new java.util.HashMap<>()),
                "没有配额口径就不夹：把 null 读成 0 就是永久关掉这一张样板");
    }

    /**
     * F2（P1）：一批发端跨<b>同族两枚不同精确键</b>（变体）——{@code #attach} 每一号都拿同一份
     * <b>批前</b> {@code quotaExact} 读数来夹（配额账要到批末 {@code #recordTakenInputs} 才前进），
     * 于是两枚变体各吃整份份额（最多 {@code 2 × share}）。这里用批内族级余量钉成"只许花一遍"。
     *
     * <p>摘掉批内余量（把那张局部表交回 {@code null}）时红在"第二枚变体键不许再吃整份"
     * 与"两笔合计 ≤ 份额"上：旧实现读出 {@code 20 + 20 = 40}。
     */
    @Test
    void twoVariantKeysOfOneFamilySpendTheFamilyShareOnlyOncePerBatch() {
        var variantA = wornKey(50);
        var variantB = wornKey(0);
        var slotA = new TestInput(new GenericStack(variantA, 20L), null);
        var slotB = new TestInput(new GenericStack(variantB, 20L), null);
        var pattern = new TestPattern(variantA, new IPatternDetails.IInput[]{slotA, slotB});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 20L;
        job.tasks.put(pattern, task);
        // 这一族总共只配给 20 枚（登记在 variantA 的精确键上，判据按族合计读）
        MaterialAllocationFixtures.assign(job, pattern, share(variantA, 20L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var poolOutflow = new WideKeyCounter();
        poolOutflow.add(variantA, BigInteger.valueOf(20L));
        poolOutflow.add(variantB, BigInteger.valueOf(20L));

        ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                new KeyCounter[]{holds(variantA, 20L), holds(variantB, 20L)}, 1L,
                List.of(new GenericStack(variantA, 20L), new GenericStack(variantB, 20L)),
                poolOutflow, reservation);

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertNotNull(eaten, "这一批确实花了族份额");
        assertEquals(20L, eaten.getLong(variantA), "第一枚变体键销满整份份额");
        assertEquals(0L, eaten.getLong(variantB),
                "同族第二枚变体键不许再吃整份：批内族级余量已扣完（旧实现这里销 20 ⇒ 合计 2×share）");
        assertEquals(40L, job.accountTaken.getLong(variantA) + job.accountTaken.getLong(variantB),
                "流水账仍收未夹原值：封顶不许把超销那一截从诊断账上一起抹掉");
    }

    /**
     * F4（P2）：{@code #capByRemainingShare} 不许恒交 {@code slot=null}——那一道槽级
     * {@code SlotAccount#selfRepaying} 豁免一丢，模具槽那一号就<b>替</b>普通消耗格把批内族余量扣了，
     * 而它自己那一号在 {@code #recordTakenInputs} 又整格豁免 ⇒ 发作方向是<b>少销</b>。
     *
     * <p>摘掉 slot 透传（交回 {@code null}）时红在"族份额留给真消耗它的那一格"：份额 10 枚
     * 被模具槽那 10 枚先扣光 ⇒ 配额账一枚不进。
     */
    @Test
    void selfRepayingMoldSlotKeepsTheFamilyShareForTheConsumingSlot() {
        var glass = AEItemKey.of(Items.GLASS);
        var mold = new TestInput(new GenericStack(glass, 10L), glass);
        var consumed = new TestInput(new GenericStack(glass, 10L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 20L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 10L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                new KeyCounter[]{holds(glass, 10L), holds(glass, 10L)}, 1L,
                List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), reservation);

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertNotNull(eaten, "普通消耗格那一截必须进配额账");
        assertEquals(10L, eaten.getLong(glass),
                "模具槽整格豁免 ⇒ 它也不许占批内族余量：份额 10 枚仍归真消耗它的那一格");
        assertEquals(20L, job.accountTaken.getLong(glass),
                "流水账照收未夹原值：两格物理都动了料");
        assertEquals(0L, reservation.remainingQuota(pattern, glass), "份额照实销尽，不是被豁免那一格吃光的");
    }

    /**
     * F3（P2）：第二处 {@code prepareBatch} 的结果同样可能是 {@code null}
     * （宿主 capacity 在两次申报之间掉到 0）。{@code #pushPreparedBatchCandidate} 自己就护 null ⇒
     * 这里复用同一个"这一批不适用"的 {@code continue} 出口，不新增日志、不新增拒绝原因。
     *
     * <p>摘掉那道护栏时这条红在"返回 0 而非崩 tick"上：旧写法直接 {@code prepared.inputTotal()} 抛 NPE。
     */
    @Test
    void secondPrepareReturningNullTakesTheSameNotApplicableExit() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 5_000L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(glass, 20_000L, Actionable.MODULATE);
        var host = new FoldThenNullHost(pattern, glass);

        long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                job, pool, new ParallelPoolLedger(), null, null,
                pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                new RecordingEnergyService(), 1L, null, null, reservation);

        assertEquals(0L, pushed, "第二次 prepare 交回 null ⇒ 走既有出口：返回 0，不崩 tick");
        assertEquals(2, host.prepares, "第一处成功、请求侧折过之后第二处才交回 null");
        assertEquals(BigInteger.valueOf(20_000L), WideCraftingInventory.amount(pool, glass),
                "没有 push 就没有物理出账：池子一枚不少");
    }

    /**
     * F6（P2）：请求侧折回是<b>可分型</b>的读数——本 tick 折了几次、折掉几发（{@code ecoFold=次数/发数}）。
     * 它<b>不参与任何判据</b>：同一跑里 {@code pushed} 仍由份额折出 1000 发，摘掉读数那条腿也只掉读数。
     *
     * <p>摘掉 {@code #noteEcoFold} 那一号调用时红在 {@code ecoFold} 仍是空串上。
     */
    @Test
    void requestSideFoldIsObservableAsEcoFoldTimesAndFirings() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 5_000L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(glass, 20_000L, Actionable.MODULATE);
        var ledger = new ParallelPoolLedger();
        // 贪心宿主第一次申报就顶到 5000 发（份额只折得出 1000 发），且继续全有全无地真扣料
        var host = new FakeNeoEcoHost(pattern, glass, 1L, 5_000L, -1L);

        assertEquals("", ledger.ecoFold(), "还没跑过就是空读数（与'折掉 0 发'分得开）");
        long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                job, pool, ledger, null, null,
                pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                new RecordingEnergyService(), 1L, null, null, reservation);

        assertEquals(1_000L, pushed, "判据不认这个读数：发数仍只由份额折出");
        assertEquals("1/4000", ledger.ecoFold(),
                "折回 1 次、折掉 4000 发：5000 → 1000 这一步在 ecoOut= 那一串旁边分得出来");
        ledger.reset();
        assertEquals("", ledger.ecoFold(), "随 tick 现算：reset 就清，不跨 tick");
    }

    /** 同一物品本体、不同耐久：AE2 按物品本体归为同一模糊族，精确键却各是一枚。 */
    private static AEItemKey wornKey(int damage) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }

    /**
     * F3 用：第一次 {@code prepare} 交回一批贪心大 batch（逼出请求侧折回），
     * 第二次起交回 {@code null}（对应宿主 capacity 掉到 0 那一档）。
     */
    private static final class FoldThenNullHost implements ICraftingProvider, PreparedBatchDispatcher {
        private final IPatternDetails pattern;
        private final AEKey take;
        int prepares;

        FoldThenNullHost(IPatternDetails pattern, AEKey take) {
            this.pattern = pattern;
            this.take = take;
        }

        @Override
        public @org.jetbrains.annotations.Nullable PreparedBatch prepareBatch(
                IPatternDetails pattern, KeyCounter[] perCraftInputs, KeyCounter perCraftOutputs,
                KeyCounter perCraftRemainders, ListCraftingInventory inventory, long requested,
                IEnergyService energyService, Level level, UUID craftingJobId
        ) {
            prepares++;
            if (prepares > 1) {
                return null;
            }
            return new PreparedBatch() {
                @Override
                public boolean push(ListCraftingInventory craftingInventory) {
                    return false;
                }

                @Override
                public long craftCount() {
                    return 5_000L;
                }

                @Override
                public List<GenericStack> inputTotal() {
                    // 顶到天花板的哨兵读数：那一维不进取请求侧，折回只由样本那一维给出
                    return List.of(new GenericStack(take, Long.MAX_VALUE));
                }

                @Override
                public List<GenericStack> outputs() {
                    return List.of();
                }

                @Override
                public List<GenericStack> remainders() {
                    return List.of();
                }

                @Override
                public double power() {
                    return 0.0D;
                }
            };
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(pattern);
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return false;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    /**
     * N3（P2，本刀真正的症状路径）：申报某键顶到 {@code Long.MAX_VALUE}、池子里该键有巨量、
     * 份额只有 N ⇒ push 之前请求量就被样本那一维折到"份额内的那一发数"，池子差值 ≤ N。
     *
     * <p>旧实现整键跳过（{@code total==Long.MAX_VALUE → continue}）且无盲发即 {@code return craftCount}
     * ⇒ 贪心宿主 {@code craftCount=5000} 大于请求的 1000 发被现成 dispatch 上限拒收 ⇒ {@code pushed=0}（红）。
     */
    @Test
    void reportedCeilingKeyIsFoldedByTheSampleDimensionNotSkipped() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 5_000L;
        job.tasks.put(pattern, task);
        // 份额只有 1000 枚，池子里躺着巨量，宿主每发真扣 1 枚（与样本同向）却顶到天花板申报。
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 1_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(glass, 20_000L, Actionable.MODULATE);
        // greedy=5000 让第一次申报就顶到 5000 发；reportedOverride=Long.MAX_VALUE 逼出哨兵读数。
        var host = new FakeNeoEcoHost(pattern, glass, 1L, 5_000L, Long.MAX_VALUE);

        long pushed = ParallelCraftingCpuLogic.pushPreparedBatch(
                job, pool, new ParallelPoolLedger(), null, null,
                pattern, job.tasks.entrySet().iterator().next(), List.of(host),
                new RecordingEnergyService(), 1L, null, null, reservation);

        assertEquals(2, host.prepares, "顶到天花板的键不再整键跳过 ⇒ 样本那一维折出 1000 发 < craftCount ⇒ 另要一批");
        assertEquals(1_000L, pushed, "请求量折到份额内那一发数：1000 枚 ÷ 每发 1 枚 = 1000 发");
        assertEquals(BigInteger.valueOf(19_000L), WideCraftingInventory.amount(pool, glass),
                "池子差值 1000 ≤ 份额：物理多拿的那一截不再无主");
        assertEquals(1_000L, MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getLong(glass),
                "销账 == 份额 == 物理出账：这一型不再靠事后 attach 兜底");
    }

    /**
     * N4（P2）：同键两格各按<b>同一份批内 remaining</b> 夹——族合计除法保证"两格合计不超吃"，
     * 且销账侧不把第二格按已花完的 remaining 再夹一次。
     */
    @Test
    void twoSlotsOfOneFamilyAreCappedByTheFamilyTotalNotEachByTheWholeShare() {
        var glass = AEItemKey.of(Items.GLASS);
        var slotA = new TestInput(new GenericStack(glass, 10L), null);
        var slotB = new TestInput(new GenericStack(glass, 10L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{slotA, slotB});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 20L;
        job.tasks.put(pattern, task);
        // 这一族总共只配给 20 枚（两格每发合计 20 枚 ⇒ 只够 1 发）。
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 20L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        // 请求侧：族合计除法 ⇒ 20 枚份额 ÷ 每发 20 枚 = 1 发；per-slot（各按整份 20/10=2）会放到 2 发超吃。
        assertEquals(1L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                reservation, pattern, new KeyCounter[]{holds(glass, 10L), holds(glass, 10L)}, 20L),
                "两格合计按族份额折 ⇒ 只够 1 发；per-slot 会各自吃整份而超吃");

        // 销账侧：两格都按同一份批内 remaining(20) 夹，第二格不因第一格先销而读到已花完的份额。
        ParallelCraftingCpuLogic.recordNeoEcoTakenInputs(job, pattern,
                new KeyCounter[]{holds(glass, 10L), holds(glass, 10L)}, 1L,
                List.of(new GenericStack(glass, 20L)), outflow(glass, 20L), reservation);
        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertNotNull(eaten, "两格合计销账必须进配额账");
        assertEquals(20L, eaten.getLong(glass), "合计销满份额 20，不被'第二格再夹一次'削成 10");
        assertTrue(eaten.getLong(glass) <= 20L, "两格合计销账不超过份额");
    }

    /**
     * N5（P2）①：{@code describeEcoOut} 渲染的 {@code 键:池子差值/reported=对方读数} 整串，
     * 含"两值不等"这一形状；{@code noteEcoOut} 收下这条读数并原样读出（空读数不覆盖旧值）。
     */
    @Test
    void ecoOutPairRendersPoolDeltaAgainstReportedReading() {
        var glass = AEItemKey.of(Items.GLASS);
        var snow = AEItemKey.of(Items.SNOW_BLOCK);
        var poolOutflow = new WideKeyCounter();
        poolOutflow.add(glass, BigInteger.valueOf(500L));
        poolOutflow.add(snow, BigInteger.valueOf(3L));
        var reported = List.of(
                new GenericStack(glass, 900L),   // 两值不等：差值 500 ≠ 对方报 900
                new GenericStack(snow, 3L));      // 两值相等
        String rendered = ParallelCraftingCpuLogic.describeEcoOut(poolOutflow, reported);
        assertTrue(rendered.contains(glass + ":500/reported=900"),
                "玻璃那一档必须把差值与对方读数并排点名：" + rendered);
        assertTrue(rendered.contains(snow + ":3/reported=3"),
                "雪那一档两值相等也照样成对：" + rendered);
        assertEquals("", ParallelCraftingCpuLogic.describeEcoOut(new WideKeyCounter(), reported),
                "没有出账就是空串");

        var ledger = new ParallelPoolLedger();
        assertEquals("", ledger.ecoOut(), "没推过 NeoEco 批次就是空读数");
        ledger.noteEcoOut(rendered);
        assertEquals(rendered, ledger.ecoOut(), "noteEcoOut 收下整串、原样读出");
        ledger.noteEcoOut("");
        assertEquals(rendered, ledger.ecoOut(), "空读数不覆盖上一条：诊断行不闪回空");
    }

    /**
     * N5（P2）②：{@code locked=[<定义>#slotN,<键>,stored=..,units=..,usable=..]} 渲染后的整串
     * （上一轮只断了字段填充，没断渲染）。
     *
     * <p>子字段边界是 {@code ,}（不带空白）：这一格进 per100r 后按空白切必须恰占一段，而
     * {@code stored=/units=/usable=} 仍寻得到址。
     */
    @Test
    void lockedReadoutRendersDefinitionSlotAndThreeNumbers() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 10L;
        job.tasks.put(pattern, task);
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 0L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(glass, 100L, Actionable.MODULATE);
        var probe = new ParallelBatchExtraction.Probe();
        probe.noteNoCandidate(3, consumed, pool, null, pattern, reservation);

        String rendered = ParallelCraftingCpuLogic.describeLockedReadout(
                probe.ncLockedOwner, probe.ncLockedSlot, probe.ncLockedKey,
                probe.ncLockedStored, probe.ncLockedUnits, probe.ncLockedUsable);
        assertTrue(rendered.contains("#slot3,"), "整串必须带上槽号：" + rendered);
        assertTrue(rendered.contains(",stored="), "整串必须带上现场量：" + rendered);
        assertTrue(rendered.contains(",units=") && rendered.contains(",usable="),
                "整串必须带上每发件数与账面可用量：" + rendered);
        assertEquals((probe.ncLockedOwner + "#slot3," + probe.ncLockedKey
                        + ",stored=" + probe.ncLockedStored + ",units=" + probe.ncLockedUnits
                        + ",usable=" + probe.ncLockedUsable).replace(' ', '_'),
                rendered, "渲染口径逐字＝owner#slotN,key,stored,units,usable（第三方串的空白只在出口折）");
        // 一格一 token：整串不许残留任何空白（键带组件时 "(with patches)" 必须已被折掉）
        assertFalse(rendered.chars().anyMatch(Character::isWhitespace),
                "locked= 那一格不许含任何空白：" + rendered);
    }

    /** N1 用：宿主 {@code prepare} 恒交回 null（对应 mixin 在 capacity≤0 时的出口）。 */
    private static final class NullPrepareHost implements ICraftingProvider, PreparedBatchDispatcher {
        private final IPatternDetails pattern;

        NullPrepareHost(IPatternDetails pattern) {
            this.pattern = pattern;
        }

        @Override
        public @org.jetbrains.annotations.Nullable PreparedBatch prepareBatch(
                IPatternDetails pattern, KeyCounter[] perCraftInputs, KeyCounter perCraftOutputs,
                KeyCounter perCraftRemainders, ListCraftingInventory inventory, long requested,
                IEnergyService energyService, Level level, UUID craftingJobId
        ) {
            return null;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(pattern);
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return false;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    /** 池子宽域现量差那本账的测试替身：一笔出账真值（键 → 枚数）。 */
    private static WideKeyCounter outflow(AEKey key, long exact) {
        var counter = new WideKeyCounter();
        counter.add(key, BigInteger.valueOf(exact));
        return counter;
    }

    /**
     * 流水账换成宽域账后，<b>销账判据的行为必须一字不变</b>：豁免仍只看这一格、配额账仍走它自己
     * 既有的饱和合并，越界量级下判据读到的仍是精确值。
     *
     * <p>量级取三批 ×（模具槽 2e18 + 普通槽 2e18）＝提走账合计 1.2e19 枚（越出 {@code long}，
     * 老 {@link KeyCounter} 在这组数上回绕成负），而配额账每键只累计 6e18 枚（仍装得进
     * {@code long}）⇒ 两本账各自独立，账变宽不会把判据拖下水。
     */
    @Test
    void slotLevelWriteoffCriteriaSurviveWideMagnitudes() {
        var glass = AEItemKey.of(Items.GLASS);
        var mold = new TestInput(new GenericStack(glass, 1L), glass);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{mold, consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);

        long perSlot = 2_000_000_000_000_000_000L;
        var legacy = new KeyCounter();
        for (int batch = 1; batch <= 3; batch++) {
            ParallelCraftingCpuLogic.recordTakenInputs(job, pattern,
                    new KeyCounter[]{holds(glass, perSlot), holds(glass, perSlot)});
            legacy.add(glass, perSlot);
            legacy.add(glass, perSlot);
        }

        var exactTaken = java.math.BigInteger.valueOf(perSlot)
                .multiply(java.math.BigInteger.valueOf(6L));
        assertEquals(exactTaken, job.accountTaken.getExact(glass),
                "提走流水账跨批复加逐位精确：两格都得记，模具槽也不漏");
        assertTrue(legacy.get(glass) < 0L, "同一组数在老 long 账上回绕成负");
        assertEquals(Long.MAX_VALUE, job.accountTaken.getLong(glass),
                "对外 long 读数（停滞日志的 taken=）按饱和收口：口径不变");

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertEquals(3L * perSlot, eaten == null ? 0L : eaten.getLong(glass),
                "配额销账仍只记普通格那三笔：模具槽整格豁免，判据与账宽无关");
        assertEquals(java.math.BigInteger.valueOf(3L * perSlot),
                exactTaken.subtract(java.math.BigInteger.valueOf(eaten.getLong(glass))),
                "流水账与配额账的差额恒等于模具槽豁免那一截：销账出口仍只有一个");
    }

    /**
     * 因果链决定性用例（第四刀）：<b>同一个键累计越出 {@code long}</b> 之后，剩余份额不能被判成 0。
     *
     * <p>写入口取真路：配给由 {@code describePlanBalance} 的逐槽复加产生（这里两笔 6e18），
     * 已吃由推送成功时的 {@code addPlanMaterialTaken} 复加产生（这里两笔 5e18）。
     * 两本账真值分别是 1.2e19 与 1e19 枚 ⇒ 还剩 2e18 枚配额。
     *
     * <p>老口径两笔合并都停在 {@link Long#MAX_VALUE}（{@code ParallelExecutingCraftingJob}
     * 的 {@code merge} 三元式与 {@code describePlanBalance} 的 {@code saturatedAdd} 各顶一次），
     * 判据拿两个天花板读数相减读出 {@code 0} ⇒ 还能推 2e18 枚的任务被 {@code quotaFor} 夹成
     * 0 发而拒推（尾段 {@code pushed=0} 的来路）。对照段用同一串输入把这条链量出来。
     */
    @Test
    void cumulativeWriteoffAboveLongKeepsTheRemainingShareReadable() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);

        long mintEach = 6_000_000_000_000_000_000L;
        long takeEach = 5_000_000_000_000_000_000L;
        var planned = new WideKeyCounter();
        planned.add(glass, mintEach);
        planned.add(glass, mintEach);
        MaterialAllocationFixtures.assign(job, pattern, planned);
        job.addPlanMaterialTaken(pattern, glass, takeEach);
        job.addPlanMaterialTaken(pattern, glass, takeEach);

        var eaten = MaterialAllocationFixtures.eatenSnapshot(job).get(pattern);
        assertEquals(new BigInteger("10000000000000000000"), eaten.getExact(glass),
                "已吃账跨批复加逐位精确，绝不停在 Long.MAX_VALUE");
        assertEquals(new BigInteger("12000000000000000000"), planned.getExact(glass),
                "配给账逐槽复加同样精确：顶到天花板就是把配到自己的那一截抹掉");

        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(2_000_000_000_000_000_000L, reservation.remainingQuota(pattern, glass),
                "还剩的 2e18 枚必须读得出来——判据要拿它除以每发用料");

        assertEquals(0L, legacySaturatedQuota(
                        new long[]{mintEach, mintEach}, new long[]{takeEach, takeEach}),
                "老口径在同一串输入下把剩余份额算成 0：这就是被判配额用尽的那一步");
    }

    /**
     * 换宽域<b>没有</b>放宽销账判据：账仍只按实提量复加，配额之外的份额一律读不出来。
     *
     * <p>已吃恰好等于配给 ⇒ 份额恒为 0；一笔都没吃 ⇒ 份额等于配给的饱和读数。
     */
    @Test
    void writeoffCriteriaAreUnchangedByTheWideLedgers() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var planned = new WideKeyCounter();
        planned.add(glass, 6_000_000_000_000_000_000L);
        planned.add(glass, 6_000_000_000_000_000_000L);
        MaterialAllocationFixtures.assign(job, pattern, planned);

        var settled = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(Long.MAX_VALUE, settled.remainingQuota(pattern, glass),
                "一笔没吃：份额即配给 1.2e19 枚，对外出口仍饱和一次 Long.MAX_VALUE");

        for (int batch = 0; batch < 3; batch++) {
            job.addPlanMaterialTaken(pattern, glass, 4_000_000_000_000_000_000L);
        }
        var fullySpent = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(0L, fullySpent.remainingQuota(pattern, glass),
                "已吃 1.2e19 枚 = 配给：销完就是 0 份额，宽域账不许它多吃一枚");
    }

    /**
     * 因果链决定性用例（第五刀）：<b>规划出口</b>那张配给表本身越出 {@code long} 时，执行侧
     * 照单采用之后剩余份额必须按真值读出来、任务才推得动。
     *
     * <p>写入口取真路：配给由 {@code MaterialClosureGate.buildMaterialPlan} 那趟账定版、随
     * {@link RecursiveCraftingPlan} 出门，再由 {@code adoptPlannedMaterialPlan} 整本搬进作业。
     * 同一键两笔各 9e18 枚 ⇒ 真值 1.8e19 枚；实吃一笔 9e18 枚 ⇒ 还剩 9e18 枚配额。
     *
     * <p>老口径这条链的上游是 {@code Map<AEKey, Long>}：规划侧那一次饱和加把配给写成
     * {@link Long#MAX_VALUE}，再与逐批精确的已吃账（第四刀换的宽域账）做差就只剩 2.2e17 枚
     * ⇒ 还能推 9e18 枚的批次被 {@code quotaFor} 夹成零头。对照段用同一串输入把这条差量出来。
     */
    @Test
    void plannedTableAboveLongSurvivesAdoptionIntoTheQuotaJudge() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        long slotUnits = 9_000_000_000_000_000_000L;
        var planned = new WideKeyCounter();
        planned.add(glass, slotUnits);
        planned.add(glass, slotUnits);
        var plan = planWithMaterialPlan(pattern, planned);
        var job = new ParallelExecutingCraftingJob(
                plan, ignored -> {
                }, (CraftingLink) null, null);

        assertFalse(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                "计划已带配给表：本趟排水不该再 mint 第二份归属");
        assertEquals(EXACT_18E18, MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(glass),
                "照单采用不许降级：计划里那 1.8e19 枚必须原样进配给账");

        job.addPlanMaterialTaken(pattern, glass, slotUnits);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(9_000_000_000_000_000_000L, reservation.remainingQuota(pattern, glass),
                "还剩的 9e18 枚必须读得出来——判据要拿它除以每发用料");
        assertEquals(223372036854775807L, legacySaturatedQuota(
                        new long[]{slotUnits, slotUnits}, new long[]{slotUnits}),
                "老口径在同一串输入下只剩 2.2e17 枚：这一截就是被上游饱和吃掉的批次");
    }

    /**
     * 换宽域<b>没有</b>放宽这条链上的任何一道出口：照单采用来的超界配额，对外读数照旧饱和
     * {@code Long.MAX_VALUE}；销账判据仍"不超不少"——已吃恰好等于配给就是 0 份额。
     *
     * <p>顺带钉住隔离性：老口径靠 {@code Map.copyOf} 让计划里那张表不可变，账本换型后由
     * {@code copy()} 顶上来——执行侧在自已那本账上复加，绝不许回写计划的配给上界。
     */
    @Test
    void adoptedWideQuotaKeepsTheSaturatedExitAndTheExactWriteoff() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        long slotUnits = 9_000_000_000_000_000_000L;
        var planned = new WideKeyCounter();
        planned.add(glass, slotUnits);
        planned.add(glass, slotUnits);
        var plan = planWithMaterialPlan(pattern, planned);
        var job = new ParallelExecutingCraftingJob(
                plan, ignored -> {
                }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);

        var untouched = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(Long.MAX_VALUE, untouched.remainingQuota(pattern, glass),
                "一笔没吃：份额即配给 1.8e19 枚，对外出口仍饱和一次 Long.MAX_VALUE");

        job.addPlanMaterialTaken(pattern, glass, slotUnits);
        job.addPlanMaterialTaken(pattern, glass, slotUnits);
        var fullySpent = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(0L, fullySpent.remainingQuota(pattern, glass),
                "已吃 1.8e19 枚 = 配给：销完就是 0 份额，宽域表不许它多吃一枚");

        job.materialAllocation.mintShare(pattern, glass, BigInteger.valueOf(5L));
        assertEquals(EXACT_18E18,
                plan.chexsonsaeutils$planMaterialPlan().get(pattern).getExact(glass),
                "执行侧在那本账上复加不得回写计划里的配给上界");
    }

    /** 1.8e19 枚：两笔 9e18 复加的精确合计（越出 {@code long}，老载体写不下）。 */
    private static final BigInteger EXACT_18E18 = new BigInteger("18000000000000000000");

    /**
     * 「在册且值为 0」与「表里没这张样板」是两回事，这条判据到这才算有用例钉住。
     *
     * <p>那本账只要非空，这张样板就<b>有配额口径</b>：份额读出 {@code 0} ⇒ 硬封顶夹死
     * （规划期定料的另一半——这一格一枚都不许从别的键付）。反过来，样板整本不在表里
     * 读出 {@code -1} ⇒ 不夹，夹了就是把没有归属的样板整批饿死。
     *
     * <p>0 值条目由 {@code mergePlannedShare} 在非正增量时按 {@code HashMap.put} 语义写下
     * （{@link WideKeyCounter#set} 保住键位），并原样活过 {@code adoptPlannedMaterialPlan}
     * 那一次 {@code copy()}。规划侧的 {@code bill} 对非正数直接 return，所以「只剩 0 标记」
     * 的形状在这条链上的唯一职责就是把 {@code isEmpty()} 顶成真：它一旦被当成空表跳过，
     * 这张样板就掉出配额口径，封顶形同虚设。
     */
    @Test
    void zeroShareInRegisterKeepsTheClampWhileAbsenceLeavesItOpen() {
        var glass = AEItemKey.of(Items.GLASS);
        var snow = AEItemKey.of(Items.SNOW_BLOCK);
        var bucket = AEItemKey.of(Items.BUCKET);
        var zeroed = new TestPattern(glass,
                new IPatternDetails.IInput[]{new TestInput(new GenericStack(glass, 1L), null)});
        var unassigned = new TestPattern(snow,
                new IPatternDetails.IInput[]{new TestInput(new GenericStack(snow, 1L), null)});

        var withZero = new WideKeyCounter();
        withZero.set(glass, 0L);      // 在册、值为 0：规划定了这一格一枚都不从这键付
        withZero.set(bucket, 5L);     // 同一样板的另一笔正常份额，用来排除"整本被跳过"
        var empty = new WideKeyCounter();
        var material = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
        material.put(zeroed, withZero);
        material.put(unassigned, empty);
        var plan = planWithMaterialPlan(material);
        var job = new ParallelExecutingCraftingJob(
                plan, ignored -> {
                }, (CraftingLink) null, null);

        assertFalse(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                "表非空：本趟排水不该再 mint 第二份归属");
        var adopted = MaterialAllocationFixtures.assignedSnapshot(job).get(zeroed);
        assertTrue(adopted.containsKey(glass),
                "0 值条目必须活过 copy()：它撑住的就是 isEmpty() 这一票");
        assertEquals(0L, adopted.getLong(glass), "在册不等于有钱");
        assertFalse(MaterialAllocationFixtures.assignedSnapshot(job).containsKey(unassigned),
                "空账整本跳过：等价于这张样板没被配给");

        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertEquals(0L, reservation.remainingQuota(zeroed, glass),
                "在册且为 0 ⇒ 夹到 0（份额 0 是规划定下的数，不是'没有口径'）");
        assertEquals(0L, reservation.remainingQuota(zeroed, snow),
                "同一张样板的键外候选照样夹：定料之后不许退到没配给的键上去");
        assertEquals(5L, reservation.remainingQuota(zeroed, bucket), "正数那一笔照常就是份额");
        assertEquals(-1L, reservation.remainingQuota(unassigned, snow),
                "不在表里＝没有配额口径 ⇒ 负数不夹");
    }

    /**
     * 规划出口那份只带配给表的递归计划：其余契约字段留空，两个<b>显示旁路</b>账交 {@code null}
     * 由 compact 构造器兜成空快照。{@code patternTimes} 逐张挂一发，只为让作业建出那些任务。
     */
    private static RecursiveCraftingPlan planWithMaterialPlan(
            java.util.IdentityHashMap<IPatternDetails, WideKeyCounter> material) {
        var times = new java.util.LinkedHashMap<IPatternDetails, Long>();
        for (IPatternDetails pattern : material.keySet()) {
            times.put(pattern, 1L);
        }
        var base = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        return new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                1L, Map.of(), material, null, null);
    }

    /**
     * 规划出口那份只带配给表的递归计划：其余契约字段留空，两个<b>显示旁路</b>账交 {@code null}
     * 由 compact 构造器兜成空快照。{@code patternTimes} 挂一发，只为让作业建出那条任务。
     */
    private static RecursiveCraftingPlan planWithMaterialPlan(
            IPatternDetails pattern, WideKeyCounter share) {
        var material = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
        material.put(pattern, share);
        return planWithMaterialPlan(material);
    }

    /** 配给/销账账本换宽域账本后的建表helper：数值口径与老 {@code Map.of(key, amount)} 一字不变。 */
    /**
     * R5：F5 那两处新读数（locked 档的样板身份与槽号）要有牙齿——不能停在默认 {@code "?"}/{@code -1}。
     *
     * <p>驱动 {@code Probe#noteNoCandidate} 走到 locked 分支：现场有料、够一发，却被份额夹到 {@code usable<units}。
     * 断 owner 不是 {@code "?"}、slot 不是 {@code -1}，且 -2（没递出候选）与 -1（无配额口径）话术可辨。
     */
    @Test
    void lockedReadoutNamesThePatternAndSlotAndSeparatesMinusOneFromMinusTwo() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 1L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 10L;
        job.tasks.put(pattern, task);
        // 份额 0 ⇒ 现场有料却付不出 ⇒ 走 locked 分支
        MaterialAllocationFixtures.assign(job, pattern, share(glass, 0L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(glass, 100L, Actionable.MODULATE);

        var probe = new ParallelBatchExtraction.Probe();
        probe.noteNoCandidate(3, consumed, pool, null, pattern, reservation);

        assertEquals("3", String.valueOf(probe.ncLockedSlot));
        assertFalse(probe.ncLockedSlot < 0, "locked 那一档必须点到具体槽号，不许留默认 -1");
        assertFalse("?".equals(probe.ncLockedOwner), "locked 那一档必须点名样板身份，不许留默认 ?");
        assertTrue(probe.ncLockedOwner.contains("glass"),
                "owner 印出样板定义身份：" + probe.ncLockedOwner);
        // -2 与 -1 话术可辨
        assertFalse(ParallelCraftingCpuLogic.describeNoCandQuota(-1L)
                .equals(ParallelCraftingCpuLogic.describeNoCandQuota(-2L)),
                "无配额口径(-1) 与没递出可计数候选(-2) 必须话术不同");
        assertEquals("无配额口径(-1)", ParallelCraftingCpuLogic.describeNoCandQuota(-1L));
        assertEquals("没递出可计数候选(-2)", ParallelCraftingCpuLogic.describeNoCandQuota(-2L));
    }

    private static WideKeyCounter share(AEKey key, long amount) {
        var counter = new WideKeyCounter();
        counter.set(key, amount);
        return counter;
    }

    /**
     * 老口径的完整判据链（两本账各自饱和合并 ⇒ 族合计 ⇒ 饱和相减），逐行照 {@code HEAD} 之前
     * 的 {@code merge} 三元式、{@code ParallelPlanReservation.shareOf} 与 {@code saturatingSubtract}
     * 重写一遍，只用于对照断言。
     */
    private static long legacySaturatedQuota(long[] mint, long[] takes) {
        long assigned = 0L;
        for (long posting : mint) {
            assigned = assigned > Long.MAX_VALUE - posting ? Long.MAX_VALUE : assigned + posting;
        }
        long eaten = 0L;
        for (long take : takes) {
            eaten = eaten > Long.MAX_VALUE - take ? Long.MAX_VALUE : eaten + take;
        }
        return eaten <= 0L ? Math.max(0L, assigned) : Math.max(0L, assigned - eaten);
    }

    private static KeyCounter holds(AEKey key, long amount) {
        var counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    /**
     * 一次交互向电网要的电封顶在 int 那一档（2026-09-22 定稿口径）：<b>份数照常聚合</b>，
     * 只是不再按"每发功耗 × k 发"整笔去要电。上一条口径被我实现反了一次（拿电去夹份数，
     * 直接把超大批次掐回 2.1e6 发），这条用例就是钉住那个反处的。
     */
    @Test
    void powerBillingIsCappedAtIntWhileTheBatchStillAggregatesNormally() {
        assertEquals((double) Integer.MAX_VALUE, ParallelCraftingCpuLogic.billedPower(1.0e18D),
                "整批功耗超过那一档：只按封顶要电");
        assertEquals(1234.0D, ParallelCraftingCpuLogic.billedPower(1234.0D), "没超过就一个字不改");
        assertEquals(0.0D, ParallelCraftingCpuLogic.billedPower(0.0D));

        var perFiringThousand = new TestPattern(OUTPUT, new IPatternDetails.IInput[]{
                new TestInput(new GenericStack(OUTPUT, 1_000L), null)});
        assertEquals(Long.MAX_VALUE, ParallelCraftingCpuLogic.powerAffordableExecutions(
                perFiringThousand, 1.0e12D),
                "电网够付这一档封顶：电这一闸不许再夹份数");
        assertEquals(5L, ParallelCraftingCpuLogic.powerAffordableExecutions(
                perFiringThousand, 5_500.0D), "电网连封顶都付不起：照旧按现量折算几发");
    }

    /**
     * 无限盘的初始缺口<b>不许</b>挂进"正在合成"：提交时只向网络抽那一次（AE2 的
     * {@code tryExtractInitialItems} 每键一次 ≤long），此后没有任何一条路会再去抽它——
     * 实机形态就是界面上 {@code 正在合成 9.2E} 永远等不到，而 {@code waitingFor} 不清空
     * 作业也永远不判完成。需要量由取料侧就地注入。
     */
    @Test
    void infiniteSupplyShortfallIsNotRegisteredAsCraftingInProgress() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                        new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of()),
                null, null, java.util.Set.of(lava));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        var missing = new KeyCounter();
        missing.add(lava, Long.MAX_VALUE);
        missing.add(OUTPUT, 500L);

        ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null, null,
                IActionSource.empty(), null);

        assertEquals(0L, job.waitingFor.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                "无限键不许挂在正在合成：那一笔料永远不会从网络来");
        assertEquals(500L, job.waitingFor.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE),
                "普通键照旧挂账等实物，一个字不许少");
        assertTrue(job.isUnboundedSupply(lava));
    }

    /**
     * 提交时的初始提料对无限源键<b>一次都不向网络伸手</b>：那一步每键只抽一次、一次最多
     * {@code long}，无限盘要供的量可以 {@code >long}，抽不到的那一截从此没人再来抽。
     * 普通键仍照 AE2 原语义走：抽不满就整单退回、原路投回网络，并报出缺的那一口。
     */
    @Test
    void initialExtractionNeverAsksTheNetworkForInfiniteSupplies() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var used = new KeyCounter();
        used.add(lava, Long.MAX_VALUE);
        used.add(OUTPUT, 10L);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                        used, new KeyCounter(), new KeyCounter(), Map.of()),
                null, null, java.util.Set.of(lava));

        var storage = new RecordingNetworkStorage();
        var cpuInventory = new ListCraftingInventory(ignored -> {
        });
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                plan, storage, cpuInventory, IActionSource.empty(), new java.util.HashSet<>()),
                "无限键不许被算进「网络欠我多少料」，普通键 10 枚网络给得起");
        assertEquals(0L, storage.extractCalls.getOrDefault(lava, 0L),
                "岩浆一次都不该被向网络要");
        assertEquals(1L, storage.extractCalls.getOrDefault(OUTPUT, 0L), "普通键照旧要一次");
        assertEquals(10L, cpuInventory.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE));

        // 普通键抽不满：整单原路投回并报出缺的那一口（AE2 原语义一字不动）
        var greedy = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                counter(OUTPUT, 500L), new KeyCounter(), new KeyCounter(), Map.of());
        var shortStorage = new RecordingNetworkStorage();
        var cpu2 = new ListCraftingInventory(ignored -> {
        });
        var missing = ParallelCraftingCpuLogic.tryExtractInitialItems(
                greedy, shortStorage, cpu2, IActionSource.empty(), new java.util.HashSet<>());
        assertNotNull(missing);
        assertEquals(OUTPUT, missing.what());
        assertEquals(490L, missing.amount());
        assertEquals(0L, cpu2.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE),
                "抽不满就不能把半份料留在 CPU 里");
        assertEquals(10L, shortStorage.inserted.getOrDefault(OUTPUT, 0L), "已抽的那 10 枚要原路投回");
    }

    /** 旧计划未携带身份，提交时仍能从明确的精确网络来源补录无限供给。 */
    @Test
    void explicitlyDeclaredNetworkSourceIsRecognizedAtSubmitAndHandedBackAsUnbounded() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                        counter(lava, Long.MAX_VALUE), new KeyCounter(), new KeyCounter(), Map.of()),
                null, null, java.util.Set.of());

        var forgiven = new java.util.HashSet<AEKey>();
        var cpu = new ListCraftingInventory(ignored -> {
        });
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                plan, new LyingInfiniteStorage(lava), cpu, IActionSource.empty(), forgiven),
                "报得出取不出不许拒掉整单提交");
        assertEquals(java.util.Set.of(lava), forgiven, "这一键要被认成无限源");
        assertTrue(cpu.list.isEmpty(), "放过不等于凭空把满 long 摆进 CPU：需要量在取料那一批才注");
    }

    /**
     * 同一判据的反面：网络两样读数都给 0 的是<b>真缺料</b>，照旧整单投回并报出缺的那一口，
     * 一个键都不许被"认成无限源"。
     */
    @Test
    void genuinelyAbsentInputStillRefusesTheSubmission() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                        counter(OUTPUT, 5L), new KeyCounter(), new KeyCounter(), Map.of()),
                null, null, java.util.Set.of());

        var forgiven = new java.util.HashSet<AEKey>();
        var cpu = new ListCraftingInventory(ignored -> {
        });
        var missing = ParallelCraftingCpuLogic.tryExtractInitialItems(
                plan, new LyingInfiniteStorage(lava), cpu, IActionSource.empty(), forgiven);

        assertNotNull(missing, "网络报 0 的键就是真缺料，不许放过");
        assertEquals(OUTPUT, missing.what());
        assertFalse(forgiven.contains(OUTPUT), "真缺料的键不能被认成无限源");
    }

    /**
     * 计划真需要某键 {@code >long} 枚时，提交腿<b>只喂一刀</b>：需求 1.4e19 枚、网络真有 1.4e19，
     * 提交这一步只搬一个 {@code long} 进池子，剩下的那一截留在网络里，由执行侧按批次常态续取
     * （{@code refillTaskInputsFromNetwork} / {@code payDeclaredBatchObligations}）。
     *
     * <p>为什么不再"分批吸到满足"：实机 1e10 的单真需 {@code 1e26} 枚 ⇒ 一万一千刀网络提取全压在
     * 点下开始键那一刻（服务端当场僵住）；而拒单那条形同虚设的代价是先把有限盘整仓吸进 CPU 池再
     * 原路投回（实机 2026-09-25 11:20 的"乱提取"本体）。抽不满的判据本身没动：见下面两条反向用例。
     */
    @Test
    void aboveLongDemandAbsorbsASingleChunkAndLeavesTheRestInTheNetwork() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var demand = new BigInteger("14000000000000000000"); // 1.4e19 > Long.MAX_VALUE
        var plan = planWithWideUsed(lava, demand);

        var storage = new WideNetworkStorage(lava, demand);
        var pool = new WideCraftingInventory(ignored -> {
        });
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                plan, storage, pool, IActionSource.empty(), new java.util.HashSet<>()),
                "网络给得出满口：一刀即止也要放行提交，不许判死这一单");
        assertEquals(1, storage.extractAsks,
                "点开始键那一刻只伸手一次：把全量吸进池子就是实机僵住的那一刀");
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), WideCraftingInventory.amount(pool, lava),
                "池子里只落一刀");
        assertEquals(demand.subtract(BigInteger.valueOf(Long.MAX_VALUE)), storage.remainingTotal(),
                "剩下的那一截留在网络里，由执行侧按批次取");
    }

    /**
     * 不许假通过的反向用例：网络只有 5e18 枚（真不够 1.4e19）⇒ 一口没喂满就是真缺料，
     * 仍如实拒提交，缺口是真值 9e18 那一截（1.4e19 − 5e18），既没被吞成"够"、也没被误认成谎报无限源。
     */
    @Test
    void aboveLongDemandStillReportsTrueShortfallWhenNetworkIsGenuinelyShort() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var demand = new BigInteger("14000000000000000000"); // 1.4e19
        var have = BigInteger.valueOf(5_000_000_000_000_000_000L); // 5e18：网络真给不出剩下的
        var plan = planWithWideUsed(lava, demand);

        var storage = new WideNetworkStorage(lava, have);
        var pool = new WideCraftingInventory(ignored -> {
        });
        var forgiven = new java.util.HashSet<AEKey>();
        var missing = ParallelCraftingCpuLogic.tryExtractInitialItems(
                plan, storage, pool, IActionSource.empty(), forgiven);

        assertNotNull(missing, "一口给不满就是真缺料，不许放过");
        assertEquals(lava, missing.what());
        assertEquals(9_000_000_000_000_000_000L, missing.amount(),
                "缺口是真值 9e18（1.4e19 − 5e18），装得进 long 就原样报、不许饱和成天花板");
        assertTrue(forgiven.isEmpty(), "确实在给料、只是不够的普通网络不能被认成无限源");
        assertEquals(0L, WideCraftingInventory.amount(pool, lava).longValue(),
                "抽不满的整单要原路投回、池子清空");
    }

    /**
     * 一张<b>存储量本来就 {@code >long} 的有限盘</b>（neoecoae／extendedae_plus 那一型）：它一次恰好
     * 交出一个 {@code long}，是真在给料的普通键，<b>绝不许</b>被认成无限源（凭空造料是用户纠过两次的
     * 那一坑）。未声明生成能力的有限盘不得被豁免，{@code forgiven} 必须恒空。
     *
     * <p>提交腿对它也只喂一刀：计划要 3e19、网络真有 2×long，旧形状会把这 2×long 整仓吸进池子再
     * 因填不满而拒单（实机 11:20 的"乱提取"），现在一刀即止、剩下的照旧留在网络里由执行侧续取。
     */
    @Test
    void hugeFiniteDiskThatReportsTheCeilingIsNeitherDrainedNorLatchedAsUnbounded() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var demand = new BigInteger("30000000000000000000"); // 3e19：两整刀仍填不满
        var have = BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1); // 1.844e19：网络真给到这里
        var plan = planWithWideUsed(lava, demand);

        var storage = new WideNetworkStorage(lava, have);
        var pool = new WideCraftingInventory(ignored -> {
        });
        var forgiven = new java.util.HashSet<AEKey>();
        assertNull(ParallelCraftingCpuLogic.tryExtractInitialItems(
                plan, storage, pool, IActionSource.empty(), forgiven),
                "满口给得出就是给得起这一刀，不许拿剩下的还差得远拒掉整单");
        assertTrue(forgiven.isEmpty(), "真交得出料的有限盘不是谎报元件：一次都不许被认成无限源");
        assertEquals(1, storage.extractAsks, "整仓吸进池子那一刀才是乱提取本体");
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), WideCraftingInventory.amount(pool, lava));
        assertEquals(have.subtract(BigInteger.valueOf(Long.MAX_VALUE)), storage.remainingTotal(),
                "网络剩下的那一截不许在提交这一步被搬走");
    }

    /**
     * 投回那一截必须按<b>宽域真值</b>分 chunk 还。池子里同一键完全可以累计到 k×{@code Long.MAX}
     * （执行侧每 tick 回补一刀，跨批累加越界），而主表只报得出一个天花板，越界那一截藏在溢出
     * 侧表里。旧写法读主表还一次再 {@code clear()}：网络只收回 {@code Long.MAX} 枚，池子却整条清空，
     * 剩下的 {@code (k−1)×Long.MAX} 枚不是没投回去，是凭空蒸发了。
     */
    @Test
    void aboveLongPoolIsReturnedToTheNetworkInChunksWithoutEvaporating() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var have = BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1); // 1.844e19
        var storage = new WideNetworkStorage(lava, BigInteger.ZERO);

        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insertExact(lava, have, Actionable.MODULATE);
        assertEquals(have, WideCraftingInventory.amount(pool, lava), "现场：池子里累计到 2×long");

        ParallelCraftingCpuLogic.returnMaterialsToNetwork(storage, pool, IActionSource.empty());

        assertEquals(have, storage.remainingTotal(),
                "分批投出去的 2×long 枚要逐 chunk 原路还回网络，一枚都不许蒸发");
        assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(pool, lava), "还完的池子清空");
    }

    /**
     * 只带一张 {@code used} 宽域溢出账的图计划：AE2 的 {@code usedItems} 把该键饱和成
     * {@link Long#MAX_VALUE}，真值挂在 {@link WideLedgerSnapshot} 的 USED 类上——正是
     * {@code tryExtractInitialItems} 读需求真值的来路。
     */
    private static git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan
            planWithWideUsed(AEKey key, BigInteger exactUsed) {
        var wideUsed = new WideKeyCounter();
        wideUsed.setExact(key, exactUsed);
        var ledger = WideLedgerSnapshot.capture(null, wideUsed, null);
        var base = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                counter(key, Long.MAX_VALUE), new KeyCounter(), new KeyCounter(), Map.of());
        return new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                base, ledger, null, java.util.Set.of());
    }

    /**
     * 大数库存：某键持有 {@code total} 枚（BigInteger），{@code extract} 每次最多给一个 {@code long}，
     * 抽干后如实给 0（SIMULATE 也诚实报剩余）。正是"网络其实有、只是单次装不下"的实机形状。
     */
    private static final class WideNetworkStorage implements appeng.api.storage.MEStorage {
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
        private final AEKey what;
        private BigInteger remaining;
        /** 被伸手的次数：一刀即止这条判据唯一的可证读数。 */
        private int extractAsks;

        private WideNetworkStorage(AEKey what, BigInteger total) {
            this.what = what;
            this.remaining = total;
        }

        private BigInteger remainingTotal() {
            return remaining;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
            if (!what.equals(key)) {
                return 0L;
            }
            extractAsks++;
            BigInteger granted = remaining.min(BigInteger.valueOf(amount)).min(LONG_MAX);
            if (mode == Actionable.MODULATE) {
                remaining = remaining.subtract(granted);
            }
            return granted.longValue();
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource src) {
            if (mode == Actionable.MODULATE && what.equals(key)) {
                remaining = remaining.add(BigInteger.valueOf(amount));
            }
            return amount;
        }

        @Override
        public Component getDescription() {
            return Component.literal("wide-network");
        }
    }

    /**
     * 部分提交那条腿（{@code seedInitialWaitingFor}）：网络仍然"报得出"而真取没给的那一口
     * 不许挂进"正在合成"——挂上就是界面那个 9.2E，而且 {@code waitingFor} 不清空作业连完成
     * 都判不了。同一次调用里网络报 0 的普通键照旧挂账等实物。
     */
    @Test
    void partialSubmitNeverAwaitsAClaimTheNetworkCannotHonour() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                        new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of()),
                null, null, java.util.Set.of());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        var missing = new KeyCounter();
        missing.add(lava, Long.MAX_VALUE);
        missing.add(OUTPUT, 7L);

        ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null,
                new LyingInfiniteStorage(lava), IActionSource.empty(), new ListCraftingInventory(ignored -> {
        }));

        assertEquals(0L, job.waitingFor.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                "报得出取不出的那一口不挂账");
        assertTrue(job.isUnboundedSupply(lava), "当场认下，后面每一批都按无限源放行");
        assertEquals(7L, job.waitingFor.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE),
                "网络报 0 的普通键照旧挂账等实物");
    }

    /**
     * 同一把尺子的另一半：网络<b>每次给不满但确实在给</b>（SIMULATE 报满、MODULATE 给 1 枚）
     * 不算谎报元件——给到的那一枚要落进池子，剩下的照旧挂账等实物。
     *
     * <p>这一条钉的是"只看 SIMULATE 就当无限"那条误判：那样一来普通存储的剩余缺口从此不再等，
     * 作业直接少一截料。
     */
    @Test
    void partialDeliveryIsStillAwaitedAndNotLatchedAsUnbounded() {
        var lava = AEItemKey.of(Items.LAVA_BUCKET);
        var plan = new git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan(
                new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                        new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of()),
                null, null, java.util.Set.of());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        var missing = new KeyCounter();
        missing.add(lava, 5L);
        var pool = new ListCraftingInventory(ignored -> {
        });

        ParallelCraftingCpuLogic.seedInitialWaitingFor(job, missing, null,
                new TrickleStorage(lava), IActionSource.empty(), pool);

        assertEquals(1L, pool.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                "真给到的那一枚不许被吞掉");
        assertEquals(4L, job.waitingFor.extract(lava, Long.MAX_VALUE, Actionable.SIMULATE),
                "剩下的 4 枚照旧挂账等实物");
        assertFalse(job.isUnboundedSupply(lava), "确实在给料的网络不算谎报元件");
    }

    /** SIMULATE 报满、MODULATE 每次只给 1 枚：普通"给不满但仍在给"的存储形状。 */
    private static final class TrickleStorage implements appeng.api.storage.MEStorage {
        private final AEKey what;

        private TrickleStorage(AEKey what) {
            this.what = what;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
            if (!what.equals(key)) {
                return 0L;
            }
            return mode == Actionable.SIMULATE ? amount : Math.min(amount, 1L);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource src) {
            return amount;
        }

        @Override
        public Component getDescription() {
            return Component.literal("trickle");
        }
    }

    /** 明确声明无限供给的网络夹具；模拟有求必应，实际不搬料，用于验证来源旁路。 */
    private static final class LyingInfiniteStorage
            implements appeng.api.storage.MEStorage, git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply {
        private final AEKey lying;

        private LyingInfiniteStorage(AEKey lying) {
            this.lying = lying;
        }

        @Override public Snapshot readExactSupply() {
            return new Snapshot(Map.of(), java.util.Set.of(lying));
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (mode == Actionable.SIMULATE && lying.equals(what)) {
                return amount;
            }
            return 0L;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            return amount;
        }

        @Override
        public Component getDescription() {
            return Component.literal("lying");
        }
    }

    /** 每次 extract 只给 10 枚，并记下每个键被要过几次、又收回了几枚。 */
    private static final class RecordingNetworkStorage implements appeng.api.storage.MEStorage {
        private final java.util.Map<AEKey, Long> extractCalls = new java.util.HashMap<>();
        private final java.util.Map<AEKey, Long> inserted = new java.util.HashMap<>();

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            extractCalls.merge(what, 1L, Long::sum);
            return Math.min(amount, 10L);
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (mode == Actionable.MODULATE) {
                inserted.merge(what, amount, Long::sum);
            }
            return amount;
        }

        @Override
        public Component getDescription() {
            return Component.literal("recording");
        }
    }

    private static KeyCounter counter(AEKey key, long amount) {
        var counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    /** 同物品的组件变体：一族三个变体各占一槽，才凑得出越出 {@code long} 的每发合计。 */
    private static AEItemKey worn(int damage) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }

    private static final AEItemKey PICK_A = worn(50);
    private static final AEItemKey PICK_B = worn(0);
    private static final AEItemKey PICK_C = worn(75);
    /** 族配额 {@code 2.4e19} 枚：真值够开两发，{@code long} 读数顶在天花板。 */
    private static final BigInteger FAST_PATH_QUOTA = new BigInteger("24000000000000000000");

    /**
     * NeoEco 快路径那道配额夹（{@code clampByRemainingQuota}）<b>两侧都不许是饱和读数</b>。
     *
     * <p>改前：分子是 {@code quotaFor} 那份"宁高估"钉到 {@link Long#MAX_VALUE} 的<b>哨兵</b>，
     * 分母是 {@code saturatedAdd} 同样钉在天花板上的每发合计——{@code Long.MAX_VALUE /
     * Long.MAX_VALUE = 1}，明明还付得起 2 发的一批被这道 long 除法夹成 1 发。
     * 现在分子走 {@code quotaExact} 的宽域真值、分母按 {@link PlanAmount} 累加，商做完才窄化一次。
     *
     * <p>对照段钉住"普通 {@code long} 域吞吐不变"：所有读数都装得进 {@code long} 时，
     * 宽域与 {@code long} 两条算法逐位同值（夹子既没放宽也没夹得更狠）。
     */
    @Test
    void fastPathQuotaClampDividesOnTheWideDomain() {
        var slots = new IPatternDetails.IInput[]{
                new TestInput(new GenericStack(PICK_A, 4_000_000_000_000_000_000L), null),
                new TestInput(new GenericStack(PICK_B, 4_000_000_000_000_000_000L), null),
                new TestInput(new GenericStack(PICK_C, 4_000_000_000_000_000_000L), null)
        };
        var pattern = new TestPattern(OUTPUT, slots);
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var planned = new WideKeyCounter();
        planned.setExact(PICK_A, FAST_PATH_QUOTA);
        MaterialAllocationFixtures.assign(job, pattern, planned);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        // 真配额口径：这道夹子必须真的生效（负数＝没有口径＝不夹，测了个空）。
        long readout = reservation.quotaFor(PICK_A, pattern, slots[0]);
        assertTrue(readout >= 0L, "本用例走真配额：quotaFor 必须报得出这一族的份额");
        assertEquals(Long.MAX_VALUE, readout, "2.4e19 枚的族配额在 long 读数上顶到天花板：它是哨兵");
        assertEquals(FAST_PATH_QUOTA.toString(),
                String.valueOf(reservation.quotaExact(PICK_A, pattern, slots[0])),
                "宽域读口交出真值——除法只准拿它当分子");

        var sample = new KeyCounter[]{holds(PICK_A, 4_000_000_000_000_000_000L),
                holds(PICK_B, 4_000_000_000_000_000_000L),
                holds(PICK_C, 4_000_000_000_000_000_000L)};
        assertEquals(2L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                reservation, pattern, sample, 2L),
                "族配额 2.4e19 枚 ÷ 每发合计 1.2e19 枚 = 2 发：两个天花板相除读出 1 发就是夹不住份数");
        // 夹子仍只往下夹：请求 9 发时按这一族的真份额折成 2 发，一枚配额都不许多花。
        assertEquals(2L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                reservation, pattern, sample, 9L), "宁少不多：份额只够两发就停在两发");

        // 对照：全部读数装得进 long 的普通形状，宽域算法与 long 算法逐位同值。
        var smallSlots = new IPatternDetails.IInput[]{
                new TestInput(new GenericStack(PICK_A, 1L), null),
                new TestInput(new GenericStack(PICK_B, 1L), null),
                new TestInput(new GenericStack(PICK_C, 1L), null)
        };
        var smallPattern = new TestPattern(OUTPUT, smallSlots);
        var smallPlanned = new WideKeyCounter();
        smallPlanned.set(PICK_A, 24L);
        var smallJob = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        MaterialAllocationFixtures.assign(smallJob, smallPattern, smallPlanned);
        var smallReservation = ParallelPlanReservation.build(smallJob.tasks.entrySet(), Map.of(),
                smallJob.materialAllocation);
        var smallSample = new KeyCounter[]{holds(PICK_A, 1L), holds(PICK_B, 1L), holds(PICK_C, 1L)};
        // 24 枚份额 ÷ 该族每发合计（1→2→3 递增）＝ 24/12/8：请求 9 发被夹到 8 发。
        assertEquals(8L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                smallReservation, smallPattern, smallSample, 9L),
                "普通 long 域的发数：换宽域后一个数都不许动");
        assertEquals(5L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                smallReservation, smallPattern, smallSample, 5L), "请求本身就比上界小时，夹子不放大");
    }

    /**
     * 一发样本吃了哪些键、几份已经定下来时，<b>没有配额口径</b>（续传作业／原样退回的模具槽）
     * 那一侧仍然一概不夹：夹了就是把没进表的样板整批饿死。判据换读口（{@code quotaFor<0} →
     * {@code quotaExact==null}）后仍与取料侧同源。
     */
    @Test
    void fastPathQuotaClampStaysOpenWithoutAQuotaChannel() {
        var slots = new IPatternDetails.IInput[]{
                new TestInput(new GenericStack(PICK_A, 1L), PICK_A),
                new TestInput(new GenericStack(PICK_B, 1L), null)
        };
        var pattern = new TestPattern(OUTPUT, slots);
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var planned = new WideKeyCounter();
        planned.set(PICK_B, 3L);
        MaterialAllocationFixtures.assign(job, pattern, planned);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        // 槽0 是原样退回的模具槽：它不在配给里 ⇒ 那一格不夹；槽1 有配额 ⇒ 按 3 枚份额夹。
        assertEquals(-1L, reservation.quotaFor(PICK_A, pattern, slots[0]), "模具槽没有配额口径");
        var sample = new KeyCounter[]{holds(PICK_A, 1L), holds(PICK_B, 1L)};
        assertEquals(3L, ParallelCraftingCpuLogic.clampByRemainingQuota(
                reservation, pattern, sample, 7L),
                "只夹有口径的那一格：模具槽那一格一枚都不该进配额账");
    }

    /**
     * 自算配给趟（生产主路：计划没带配给表时 {@code describePlanBalance} 的
     * {@code mintAllocation == true} 那一支，交白卷出口见 {@code adoptPlannedMaterialPlan}）
     * <b>不许拿 {@code TaskProgress#value} 那份饱和镜像当乘数</b>。
     *
     * <p>任务剩余发数 {@code > long} 时 {@code value} 读出 {@code Long.MAX_VALUE} 哨兵：旧实现拿它
     * 乘每发件数，mint 出的族配给顶在天花板上、与真需差一整截，下游"配额=0 / 可花却付 0"
     * 的实机症状就出自这里。修法：发数读 {@code exactRemaining()} 宽域真值，排水走
     * {@code drainSlotWide}。
     */
    @Test
    void submitMintAllocationMultipliesWithWideFiringsNotTheLongMirror() {
        var glass = AEItemKey.of(Items.GLASS);
        var consumed = new TestInput(new GenericStack(glass, 3L), null);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{consumed});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        // 3 × 发数 越出 long：发数本身 Long.MAX+1001，真需 3×(Long.MAX+1001)
        var firings = java.math.BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(1001L));
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(firings));
        job.tasks.put(pattern, progress);

        ParallelCraftingCpuLogic.mintSubmitAllocation(job, job.tasks.entrySet(),
                new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>());

        var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern);
        assertNotNull(assigned, "自算趟必须把这张样板的配给 mint 出来");
        assertEquals(firings.multiply(BigInteger.valueOf(3L)), assigned.getExact(glass),
                "mint 出的配给 = 宽域真需 3×发数：旧实现这里交回的是 Long.MAX_VALUE 那份假数");

        // 取料点/回补侧共用那把尺：拿 quotaExact 的宽域真值除每发合计，可付发数要真算得出来
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var quota = reservation.quotaExact(glass, pattern, consumed);
        assertNotNull(quota, "mint 出的那一格必须有配额口径");
        var payable = ParallelBatchExtraction.cellPayableFirings(
                null, null, quota.exactValue(), BigInteger.valueOf(3L));
        assertEquals(firings, payable,
                "按这份配给判'这一格几发'读出的是宽域真发数：拿饱和镜像当乘数时这里少算一整截");
    }

    /**
     * 对照臂（防把宽化做成第二套算法）：与主臂同一批生产成员（{@code mintSubmitAllocation} →
     * {@code SlotAccount#drainSlotWide} → {@code SlotAccount#chargedUnitsWide}），但发数装得进
     * {@code long} ⇒ 交回的配给/缺口读数与旧窄域口径<b>逐字相同</b>。
     *
     * <p>这条臂刻意带两处<b>只在这条臂上分叉</b>的结构，主臂（无自偿还槽、供给为空）看不见：
     * <ul>
     *   <li><b>部分可付</b>的 {@code glass} 池：供给 6000 枚只盖得住 2000 发，{@code drain} 的
     *       {@code fundable = onHand.divide(unit)} 除法被真正走到——漂它只红这条臂。</li>
     *   <li><b>自偿还模具槽</b>（{@code mold}，{@code getRemainingKey==候选键}）：池中无料，
     *       整段 5000 发全没盖住，缺口走 {@code chargedUnitsWide} 的"自偿还只算一发"那一支 ⇒
     *       {@code hole=7}（每发 7 枚 × 1 发）而非 {@code 7×5000}；{@code uncoveredNeed=35000} 仍是整段需求。
     *       把那一支漂成"按整批发数算"只红这条臂（主臂无自偿还槽）。</li>
     * </ul>
     *
     * <p>反向（宽域 vs 窄域那把尺）：把 {@code chargedUnitsWide} 的乘积漂回 {@code long} 饱和，
     * 这条臂的每个数都还在 {@code long} 内 ⇒ 逐字不变（仍绿），只有主臂那条 {@code 3×(Long.MAX+1001)}
     * 会被夹回 {@code Long.MAX} 而红。两向合起来才说明这条臂<em>构成对照</em>，不是空转。
     */
    @Test
    void submitMintAllocationStaysByteIdenticalWithinLongRange() {
        var glass = AEItemKey.of(Items.GLASS);
        var mold = AEItemKey.of(Items.BUCKET);
        var glassSlot = new TestInput(new GenericStack(glass, 3L), null);
        // 模具槽：候选键与回流键同一个 ⇒ SlotAccount#selfRepaying 命中
        var moldSlot = new TestInput(new GenericStack(mold, 7L), mold);
        var pattern = new TestPattern(glass, new IPatternDetails.IInput[]{glassSlot, moldSlot});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(BigInteger.valueOf(5000L)));
        job.tasks.put(pattern, progress);
        var holes = new java.util.HashMap<AEKey, BigInteger[]>();
        var consumers = new java.util.HashMap<AEKey, java.util.Map<String, BigInteger>>();
        // 部分可付：池里只有 6000 枚 glass ⇒ 只盖得住 2000 发，另 3000 发进 unfunded
        var supply = new java.util.HashMap<AEKey, BigInteger>();
        supply.put(glass, BigInteger.valueOf(6000L));

        ParallelCraftingCpuLogic.mintSubmitAllocation(job, job.tasks.entrySet(),
                supply, holes, consumers);

        // glass：配给 = 整段需求 3×5000=15000（部分可付只改 hole/funded 的切分，不改配给总量）
        assertEquals(BigInteger.valueOf(15000L), MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).getExact(glass),
                "每发 3 枚 × 5000 发：窄域口径的同一个数，宽化没有换算法");
        // 部分可付把 unfunded 从 5000 发到 3000 发：hole=3×3000=9000，need 仍是整段 3×5000=15000
        assertEquals(BigInteger.valueOf(9000L), holes.get(glass)[0],
                "fundable 除法把 6000/3=2000 发判成已付，没盖住的 3000 发×3 枚=9000");
        assertEquals(BigInteger.valueOf(15000L), holes.get(glass)[1], "未盖住槽整段需求=3×发数=15000");
        // mold：自偿还只算一发 ⇒ 缺口 hole=7×1=7；need 仍印整段 7×5000=35000
        assertEquals(BigInteger.valueOf(7L), holes.get(mold)[0],
                "自偿还模具槽整批只欠一发起步量：chargedUnitsWide 走『只算一发』那一支");
        assertFalse(MaterialAllocationFixtures.assignedSnapshot(job).get(pattern).containsKey(mold),
                "模具槽那份起步量不进配给：它每发都从池子里过一遍");
        assertEquals(BigInteger.valueOf(35000L), holes.get(mold)[1],
                "未盖槽整段需求仍是 7×5000：全额盖住的槽不计，没盖住的按整段印");
    }

    /**
     * 台账 #115 剩下的那一半：mint 那一臂的<b>端到端</b>用例（不是只问 mint 交回的那个数）。
     *
     * <p>真链一整条：{@code adoptPlannedMaterialPlan} 交 {@code true}（计划没带配给表）→
     * {@code describePlanBalance} 里那一趟 {@code mintSubmitAllocation} 落表 →
     * {@code ParallelPlanReservation#quotaExact} 的<b>两臂</b> → 取料点 {@code prepare} 的封顶 →
     * 唯一销账出口 {@code recordTakenInputs} → 回补腿 {@code refillObligationBatch}。
     *
     * <h3>独立手算表（期望值全部从这张表来，一个字都不由被测产出复算）</h3>
     * <pre>
     * 每发件数：槽0 = 4 枚/发，槽1 = 3 枚/发                      （夹具写死）
     * 任务发数：20 发                                             （夹具写死）
     * 提交供给：plan.usedItems() = 镐 80 枚、桶 60 枚              （＝整段真需，全额盖住）
     *   ⇒ mint 配给：镐 4×20 = 80，桶 3×20 = 60，holes 空
     * 池子起步：镐 100 枚、桶 100 枚
     * 取料点请求 25 发 ⇒ 配额维 min(80/4, 60/3) = 20；池维 min(100/4, 100/3) = 25
     *   ⇒ 夹到 20 发，吃掉 镐 80、桶 60 ⇒ 池 镐 20、桶 40
     * 销账 ⇒ 已吃 镐 80、桶 60 ⇒ 剩配额 0/0
     * 回补一批 20 发：镐 need = 4×20 = 80、池内 20 ⇒ 缺口 60
     *   配额臂 ⇒ 可付发数 0/4 = 0 ⇒ 这一格一枚都不补、向网络伸手 0 次
     *   （配额<b>不</b>生效那一支：room = 60 ⇒ 补 60 枚、伸手 ≥1 次 —— 这一格的牙）
     * </pre>
     *
     * <p>{@code quotaExact} 两臂各钉一个读数：在册且销光＝{@code 0}（"许过且花完"），
     * 表非空而没这一族且<b>带槽</b>＝{@code null}（"从没许过"⇒ 不夹），同一键换成
     * {@code slot == null} 的读数＝{@code 0}（那一道放开只对着带槽的伸手口径）。
     */
    @Test
    void submitMintFeedsQuotaArmsThenCapsExtractionAndRefillEndToEnd() {
        var pick = AEItemKey.of(new ItemStack(Items.IRON_PICKAXE));
        var sand = AEItemKey.of(Items.SAND);
        var pickSlot = new TestInput(new GenericStack(pick, 4L), null);
        var bucketSlot = new TestInput(new GenericStack(REMAINDER, 3L), null);
        var pattern = new TestPattern(OUTPUT, new IPatternDetails.IInput[]{pickSlot, bucketSlot});
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(BigInteger.valueOf(20L)));
        job.tasks.put(pattern, progress);
        var plan = planUsing(pick, 80L, REMAINDER, 60L);

        assertTrue(ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan),
                "计划没带配给表 ⇒ 份额只能由提交期 mint 这一臂交（false 那一臂走照单采用）");

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var balance = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertTrue(balance.contains(" src=submit"), "这一趟必须走 mint 臂：" + balance);
        assertTrue(balance.contains(" ok keys="), "供给恰好全额盖住 ⇒ 不该报出缺口：" + balance);
        var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern);
        assertNotNull(assigned, "mint 必须把这张样板的配给落进表里");
        assertEquals(BigInteger.valueOf(80L), assigned.getExact(pick), "槽0 配给＝4 枚/发 × 20 发");
        assertEquals(BigInteger.valueOf(60L), assigned.getExact(REMAINDER), "槽1 配给＝3 枚/发 × 20 发");

        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pickQuota = reservation.quotaExact(pick, pattern, pickSlot);
        assertNotNull(pickQuota, "mint 落表后这一格必须有配额口径");
        assertEquals(BigInteger.valueOf(80L), pickQuota.exactValue(), "销账前的份额＝手算的整段真需");

        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insert(pick, 100L, Actionable.MODULATE);
        pool.insert(REMAINDER, 100L, Actionable.MODULATE);

        var batch = ParallelBatchExtraction.prepare(pattern, pool, null, 25L,
                new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(batch, "两格都有料，批量必须开得工");
        assertEquals(20L, batch.executions(),
                "25 发的请求被配额夹到 20 发：池维给得出 25，配额只许 80/4 与 60/3");
        assertEquals(80L, batch.holder()[0].get(pick), "这一批真从池子里拿走 4 枚/发 × 20 发");
        assertEquals(60L, batch.holder()[1].get(REMAINDER), "槽1 按 3 枚/发 × 20 发");
        assertEquals(20L, pool.extract(pick, Long.MAX_VALUE, Actionable.SIMULATE),
                "池子按手算表掉到 20 枚");

        ParallelCraftingCpuLogic.recordTakenInputs(job, pattern, batch);
        assertEquals(BigInteger.ZERO, reservation.quotaExact(pick, pattern, pickSlot).exactValue(),
                "在册且销光＝0（『许过且花完』的形状），不是 null");
        assertNull(reservation.quotaExact(sand, pattern, pickSlot),
                "表非空、没这一族、带槽＝没有配额口径（『从没许过』），不夹");
        assertEquals(BigInteger.ZERO, reservation.quotaExact(sand, pattern, null).exactValue(),
                "同一键换成 slot==null 的那一臂：照旧交销账份额 0，不是 null");

        var pulls = new java.util.ArrayList<String>();
        ParallelBatchExtraction.ObligationRefill refill = (what, wanted) -> {
            pulls.add(what + "/" + wanted);
            return wanted;
        };
        long moved = ParallelBatchExtraction.refillObligationBatch(
                pattern, pool, null, 20L, reservation, refill);
        assertEquals(0L, moved,
                "配额已尽 ⇒ 回补一枚都不搬；配额不生效那一支会按缺口补满 60 枚");
        assertEquals(List.of(), pulls, "配额已尽 ⇒ 这一条腿压根不向网络伸手");
    }

    /**
     * 只带 {@code usedItems()} 那份供给的计划：{@code describePlanBalance} 的提交供给
     * 就是从这儿进 mint 那趟排水的（计划产出走 {@code getOutputs}，本用例那两张夹具样板都交空表）。
     */
    private static ICraftingPlan planUsing(AEKey first, long firstAmount, AEKey second, long secondAmount) {
        var used = new KeyCounter();
        used.add(first, firstAmount);
        used.add(second, secondAmount);
        var empty = new KeyCounter();
        return new ICraftingPlan() {
            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return used;
            }

            @Override
            public KeyCounter emittedItems() {
                return empty;
            }

            @Override
            public KeyCounter missingItems() {
                return empty;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return Map.of();
            }
        };
    }

    /**
     * {@code [chexsonsaeutils-batch]} 一行必须带着"这一轮被哪个闸口挡住"那两格（读数修正）。
     *
     * <p>{@code materialBackoff=} 与 {@code extractNull=} 过去<b>只在停滞行里有</b>，per100r 一个字都没
     * ⇒ 判不出"20t 的材料退避吃掉了多少轮"（实机那一跑 {@code evaluated=30} 只看得见评估了 30 条
     * 任务，看不见其中多少条是被缺料退避挡掉的，于是把退避当成"没伸手"）。口径取停滞行<b>同一格</b>
     * 的那一轮读数（{@code StallProbe#noteDetail} 已经记下的那两个数），不新记账、不跨窗累加。
     *
     * <p>{@code describeBatchGateReadouts} 是这两格进 per100r 那一行的<b>唯一</b>出口：摘掉这两格，
     * 这里两条断言逐字变红。
     */
    @Test
    void batchLineCarriesTheBackoffAndExtractNullGatesSoTwentyTickBackoffIsCountable() {
        assertEquals(" materialBackoff=0 extractNull=0",
                ParallelCraftingCpuLogic.describeBatchGateReadouts(0L, 0L),
                "两格都要出现且随场景取值：这一窗没人被退避挡住、也没人提取交回 null");
        assertEquals(" materialBackoff=12 extractNull=3",
                ParallelCraftingCpuLogic.describeBatchGateReadouts(12L, 3L),
                "退避挡掉 12 条任务、提取交回 null 3 条的场景，必须与停滞行同一把尺");
    }

    /**
     * 驱动本体的假 NeoEco 宿主：{@code prepareBatch} 按"机器容量"自己定发数，
     * {@code push} 照 {@code ECOBatchCraftingHelper#extractExact} 的<b>全有全无</b>真从池子里扣料。
     */
    private static final class FakeNeoEcoHost implements ICraftingProvider, PreparedBatchDispatcher {
        private final IPatternDetails pattern;
        private final AEKey take;
        /** 这一批<b>每发真扣</b>几枚：可以与一发样本记的不一样。 */
        private final long takePerFiring;
        /** {@code >0}：第一次申报就按机器容量报这个发数。 */
        private final long greedyFirstCount;
        /** {@code inputTotal()} 读数覆写：{@code >0} 即照它报。 */
        private final long reportedOverride;
        int prepares;

        FakeNeoEcoHost(IPatternDetails pattern, AEKey take, long takePerFiring,
                long greedyFirstCount, long reportedOverride
        ) {
            this.pattern = pattern;
            this.take = take;
            this.takePerFiring = takePerFiring;
            this.greedyFirstCount = greedyFirstCount;
            this.reportedOverride = reportedOverride;
        }

        @Override
        public @org.jetbrains.annotations.Nullable PreparedBatch prepareBatch(
                IPatternDetails pattern,
                KeyCounter[] perCraftInputs,
                KeyCounter perCraftOutputs,
                KeyCounter perCraftRemainders,
                ListCraftingInventory inventory,
                long requested,
                IEnergyService energyService,
                Level level,
                UUID craftingJobId
        ) {
            prepares++;
            long count = greedyFirstCount > 0L && prepares == 1 ? greedyFirstCount : requested;
            long taken = takePerFiring * count;
            var inputTotal = List.of(new GenericStack(take,
                    reportedOverride > 0L ? reportedOverride : taken));
            return new PreparedBatch() {
                @Override
                public boolean push(ListCraftingInventory craftingInventory) {
                    long got = craftingInventory.extract(take, taken, Actionable.MODULATE);
                    if (got != taken) {
                        if (got > 0L) {
                            craftingInventory.insert(take, got, Actionable.MODULATE);
                        }
                        return false;
                    }
                    return true;
                }

                @Override
                public long craftCount() {
                    return count;
                }

                @Override
                public List<GenericStack> inputTotal() {
                    return inputTotal;
                }

                @Override
                public List<GenericStack> outputs() {
                    return List.of();
                }

                @Override
                public List<GenericStack> remainders() {
                    return List.of();
                }

                @Override
                public double power() {
                    return 0.0D;
                }
            };
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(pattern);
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return true;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    private record TestInput(GenericStack candidate,
            @org.jetbrains.annotations.Nullable AEKey remainder) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[]{candidate};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return candidate != null && candidate.what().equals(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainder;
        }
    }

    private record TestPattern(AEItemKey definition,
            IPatternDetails.IInput[] slots) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots;
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return java.util.List.of();
        }
    }
}
