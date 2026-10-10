package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class ParallelFiringsPlanOwnsTotalTest {

    /** 一发吃 1000 枚 V1。 */
    private static final long UNITS_PER_FIRING = 1000L;
    private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private static final AEItemKey V1 = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.BARREL);

    /** 单槽、每发 1000 枚 V1、产 1 枚、无回流容器。 */
    private static final FakePattern ONE_A_FIRING = new FakePattern(
            AEItemKey.of(Items.CHEST),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, 1L)},
                    UNITS_PER_FIRING, null, Set.of(V1))},
            List.of(new GenericStack(PRODUCT, 1)));

    private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
    private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

    /** 没有别的任务占料、也没有提交期配给：账本存在也不改取料口径。 */
    private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), NO_PLAN, NO_TAKEN);

    private static ListCraftingInventory pool(long v1) {
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        if (v1 > 0L) {
            inventory.insert(V1, v1, Actionable.MODULATE);
        }
        return inventory;
    }

    private static ParallelExecutingCraftingJob newJob() {
        return new ParallelExecutingCraftingJob(emptyPlan(), ignored -> {
        }, (CraftingLink) null, null);
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    /**
     * 判据 1：池里只 1 发的料、规划剩 5 发 ⇒ 本批推 1 发、剩余仍是 4。
     *
     * <p>现场量（池子这一轮只凑得出 1 发）只许切"本批推几发"这一层——凑齐 1 发就推 1 发。
     * 它<b>不许</b>把这张样板的总份数夹成 0 或 1：推完这一发后任务表剩余必须还是 5-1=4。
     */
    @Test
    void singleFiringInPoolPushesOneFiringAndLeavesPlanRemaining() {
        var job = newJob();
        var task = progress(5L);
        job.tasks.put(ONE_A_FIRING, task);
        var oneFiringPool = pool(UNITS_PER_FIRING); // 恰好 1 发的料

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(new AcceptingHost(),
                ONE_A_FIRING, job, job.tasks.entrySet(), oneFiringPool, null, RESERVATION_FREE,
                new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(), Long.MAX_VALUE,
                Long.MAX_VALUE, null, null, null, null, 0L);

        assertNotNull(push, "凑齐 1 发就得推 1 发——现场量只切本批，不许让这张样板整批交白卷");
        assertEquals(1L, push.accepted(), "本批推 1 发（现场这一轮只凑得出 1 发）");
        assertEquals(4L, task.exactRemaining().exactValue().longValueExact(),
                "总份数只被实收扣掉推出去的那 1 发，5-1=4，不许夹成 0 或 1");
    }


    // ==================================================================================
    // 夹具
    // ==================================================================================


    /** 照收申报份数、回执给满实收的宿主。 */
    private static final class AcceptingHost implements DeclaredBatchHost {
        @Override
        public boolean supportsDeclaredBatch(IPatternDetails pattern) {
            return true;
        }

        @Override
        public BigInteger declaredBatchCapacity(IPatternDetails pattern) {
            return BIG_LONG;
        }

        @Override
        public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger declaredExecutions) {
            return declaredExecutions;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
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

    private record FakeInput(GenericStack[] candidates, long multiplier,
            @Nullable AEKey remainder, Set<AEKey> accepts) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return accepts.contains(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainder;
        }
    }

    private record FakePattern(AEItemKey definition, IPatternDetails.IInput[] slots,
            List<GenericStack> outputs) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots.clone();
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }
    }
}
