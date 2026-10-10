package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoPreparedBatch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ParallelCraftingCpuLogicTest {

    private static final AEItemKey OUTPUT = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
    private static final AEItemKey REMAINDER = AEItemKey.of(new ItemStack(Items.BUCKET));

    @Test
    void operationCountersAreUnlimitedButStillObservable() {
        var ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(
                1L, 1L, 1L, 1L, 20_000_000L));
        ledger.resetForTick(1L, System.nanoTime());

        assertEquals(1_000_000L, ledger.claimPatternPushes(1_000_000L));
        assertEquals(1_000_000L, ledger.claimProviderChecks(1_000_000L));
        assertEquals(1_000_000L, ledger.claimExtractPatternInputs(1_000_000L));
        assertEquals(1_000_000L, ledger.claimReinjectPatternInputs(1_000_000L));
        assertFalse(ledger.isExhausted());
        assertEquals(1_000_000L, ledger.snapshot().patternPushesUsed());
    }

    @Test
    void wallClockBudgetRemainsTheOnlyExhaustionGuard() {
        var ledger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(
                1L, 1L, 1L, 1L, 1L));
        ledger.resetForTick(1L, 1L);

        assertFalse(ledger.hasTimeBudget(2L));
        assertTrue(ledger.isExhausted());
        assertTrue(ledger.exhaustedTypes().contains(ParallelCpuGridBudgetLedger.BudgetType.TIME));
    }

    @Test
    void unavailableBatchFallsBackWithoutBeingClassifiedAsPushRejection() {
        assertEquals(
                ParallelCraftingCpuLogic.PreparedBatchPushResult.NOT_BATCHED,
                ParallelCraftingCpuLogic.pushPreparedNeoEcoBatch(
                        null, new ListCraftingInventory(ignored -> {
                        })));
        assertEquals(
                ParallelCraftingCpuLogic.PreparedBatchPushResult.NOT_BATCHED,
                ParallelCraftingCpuLogic.pushPreparedNeoEcoBatch(
                        preparedBatch(1L, List.of(), List.of(), 0.0D),
                        new ListCraftingInventory(ignored -> {
                        })));
    }

    @Test
    void rejectedPreparedBatchIsClassifiedAsPushRejection() {
        NeoEcoPreparedBatch rejected = new NeoEcoPreparedBatch() {
            @Override
            public boolean push(ListCraftingInventory inventory) {
                return false;
            }

            @Override
            public long craftCount() {
                return 2L;
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
                ParallelCraftingCpuLogic.pushPreparedNeoEcoBatch(
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
        NeoEcoPreparedBatch prepared = preparedBatch(
                12L,
                List.of(new GenericStack(OUTPUT, 36L)),
                List.of(new GenericStack(REMAINDER, 12L)),
                48.0D);

        ParallelCraftingCpuLogic.applyNeoEcoBatchSuccess(job, task, prepared, energy);

        assertEquals(5L, task.value);
        assertEquals(36L, job.waitingFor.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(12L, job.waitingFor.extract(REMAINDER, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(12.0D, job.timeTracker.getStartedUnits());
        assertEquals(48.0D, energy.modulatedPower);
        assertEquals(1, energy.modulatedCalls);
        assertNull(job.exactInputWitness);
    }

    @Test
    void batchSuccessPreservesOtherPatternWitnessState() {
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        job.exactInputWitness = new ParallelInputWitnessState(
                new PlanningInputWitness(
                        true,
                        Map.of(AEItemKey.of(Items.PAPER), 1L),
                        List.of(new PlanningInputWitness.PatternInputRuns(
                                AEItemKey.of(Items.PAPER),
                                1,
                                List.of(new PlanningInputWitness.InputRun(
                                        0, AEItemKey.of(Items.IRON_INGOT), 1L, 1L)))),
                        List.of()));
        var witness = job.exactInputWitness;
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = 2L;

        ParallelCraftingCpuLogic.applyNeoEcoBatchSuccess(
                job, task, preparedBatch(2L, List.of(), List.of(), 0.0D),
                new RecordingEnergyService());

        assertEquals(0L, task.value);
        assertEquals(witness, job.exactInputWitness);
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

    private static NeoEcoPreparedBatch preparedBatch(
            long count,
            List<GenericStack> outputs,
            List<GenericStack> remainders,
            double power
    ) {
        return new NeoEcoPreparedBatch() {
            @Override
            public boolean push(ListCraftingInventory inventory) {
                return true;
            }

            @Override
            public long craftCount() {
                return count;
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
}
