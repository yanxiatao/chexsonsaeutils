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
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务表份数的宽域账本用例：能记、能存回、能精确扣。
 *
 * <p>份数的精确真值来自规划侧 {@code WideCraftCounter} → {@link WideCraftSnapshot}
 * （随计划对象出门），AE2 的 {@code patternTimes()} 只是饱和代表值。
 * 量级用请求口径的 {@code 1e19} 发（{@code Long.MAX_VALUE} 是 9.22e18）。
 */
final class ParallelTaskProgressWideAmountTest {

    /** 1e19 发：越出 {@code long}，正是会被截成 {@code Long.MAX_VALUE} 的那一档。 */
    private static final BigInteger OVER_LONG = new BigInteger("10000000000000000000");

    /** 低位有特征的越界值：编码丢精度（例如走 double）就抓得出来。 */
    private static final BigInteger OVER_LONG_ODD = OVER_LONG.add(BigInteger.valueOf(1234567890123L));

    private static final AEItemKey PRODUCT = AEItemKey.of(new ItemStack(Items.IRON_INGOT));

    private static final FakePattern PATTERN = new FakePattern(
            AEItemKey.of(Items.CRAFTING_TABLE),
            new GenericStack(PRODUCT, 1L));

    @Test
    void overLongFiringsAreRecordedExactlyInTheTaskTable() {
        var job = new ParallelExecutingCraftingJob(
                widePlan(PATTERN, OVER_LONG), ignored -> {
                }, (CraftingLink) null, null);

        assertEquals(1, job.tasks.size());
        var recorded = job.tasks.get(PATTERN);
        assertEquals(OVER_LONG, recorded.exactRemaining().exactValue(),
                "任务表要存规划侧精确发数，不是 patternTimes() 那份饱和代表值");
        assertTrue(OVER_LONG.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        assertEquals(Long.MAX_VALUE, recorded.value, "饱和镜像照旧留着，喂只能吃 long 的旧调用点");
        assertFalse(recorded.isFinished(), "越界任务不能被读成已跑完");
    }

    /**
     * 建表的键集＝「自有宽域份数账 ∪ AE2 代表值字典」：两份各缺一截时都不许丢任务。
     *
     * <p>反向守门：{@link WideCraftSnapshot#capture} 只记发数越出 {@code long} 的样板，
     * 谁把键集来源直接换成它，塞得进 long 的窄样板就会整批从任务表消失。
     */
    @Test
    void taskTableKeysAreTheUnionOfWideAccountAndRepresentativeDict() {
        FakePattern narrowOnly = new FakePattern(
                AEItemKey.of(Items.PAPER), new GenericStack(PRODUCT, 1L));
        FakePattern wideOnly = new FakePattern(
                AEItemKey.of(Items.BOOK), new GenericStack(PRODUCT, 1L));

        var crafts = new WideCraftCounter();
        crafts.add(wideOnly, PlanAmount.of(OVER_LONG));
        // AE2 那份代表值字典只记得下窄任务：越界那一发在字典里缺位。
        var representative = new HashMap<IPatternDetails, Long>();
        representative.put(narrowOnly, 5L);
        var plan = new FakePlan(Map.copyOf(representative), WideCraftSnapshot.capture(crafts));

        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        assertEquals(2, job.tasks.size(), "两份键集取并集：窄样板与宽域样板都要在表里");
        assertTrue(job.tasks.containsKey(narrowOnly), "只塞得进 long 的样板不能被宽域快照挤掉");
        assertEquals(5L, job.tasks.get(narrowOnly).exactRemaining().fastValue());
        assertTrue(job.tasks.containsKey(wideOnly), "AE2 字典缺位的越界样板必须由宽域账补进表");
        assertEquals(OVER_LONG, job.tasks.get(wideOnly).exactRemaining().exactValue());
    }

    @Test
    void stallReadoutPrintsExactFiringsNotTheSaturatingSentinel() {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(OVER_LONG));
        assertEquals(Long.MAX_VALUE, progress.value, "先确认镜像确实处于饱和哨兵态");

        var readout = ParallelCraftingCpuLogic.describeStallTaskFirings(progress);
        assertEquals(OVER_LONG.toString(), readout, "份数读数要给精确真值");
        assertFalse(readout.contains("9223372036854775807"),
                "越界任务不许把饱和哨兵当份数摆进排查日志");

        // 域内任务观感零变；空任务沿用 -1 形态
        var narrow = new ParallelExecutingCraftingJob.TaskProgress();
        narrow.addFirings(PlanAmount.of(42L));
        assertEquals("42", ParallelCraftingCpuLogic.describeStallTaskFirings(narrow));
        assertEquals("-1", ParallelCraftingCpuLogic.describeStallTaskFirings(null));
    }

    @Test
    void wideFiringsRoundTripThroughTaskEntryNbtBitForBit() {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(OVER_LONG_ODD));

        var item = new CompoundTag();
        ParallelExecutingCraftingJob.writeTaskProgress(item, progress);
        var restored = ParallelExecutingCraftingJob.readTaskProgress(item);

        assertEquals(OVER_LONG_ODD, restored.exactRemaining().exactValue(),
                "存→读逐位相等（含低位特征数字）");
        assertEquals(progress.value, restored.value, "饱和镜像同样往返一致");

        // 常态（装得进 long）不该多挂宽域键：老键位就是全部
        var narrow = new ParallelExecutingCraftingJob.TaskProgress();
        narrow.addFirings(PlanAmount.of(42L));
        var narrowItem = new CompoundTag();
        ParallelExecutingCraftingJob.writeTaskProgress(narrowItem, narrow);
        assertFalse(narrowItem.contains(
                ParallelExecutingCraftingJob.NBT_CRAFTING_PROGRESS_WIDE, Tag.TAG_BYTE_ARRAY),
                "能塞 long 的份数只写老 long 键");
        assertEquals(42L, ParallelExecutingCraftingJob.readTaskProgress(narrowItem)
                .exactRemaining().fastValue());
    }

    @Test
    void batchedDecrementStaysExactAndNeverLooksFinished() {
        var job = new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.addFirings(PlanAmount.of(OVER_LONG));

        long batch = 1_000_000_000L;
        for (int round = 1; round <= 4; round++) {
            ParallelCraftingCpuLogic.applyPreparedBatchSuccess(
                    job, task, preparedBatch(batch), new NoopEnergyService());
            assertEquals(OVER_LONG.subtract(BigInteger.valueOf(batch * round)),
                    task.exactRemaining().exactValue(), "第 " + round + " 批后剩余必须逐位精确");
            assertEquals(Long.MAX_VALUE, task.value, "越界期间镜像仍是饱和代表值");
            assertFalse(task.isFinished(), "还剩约 1e19 发：绝不允许被判成跑完");
        }

        // 扣回 long 域：撤掉侧值，镜像立刻恢复成精确值，任务仍未跑完
        task.consumeFirings(Long.MAX_VALUE - 1_000L);
        var demoted = OVER_LONG
                .subtract(BigInteger.valueOf(batch).multiply(BigInteger.valueOf(4L)))
                .subtract(BigInteger.valueOf(Long.MAX_VALUE - 1_000L));
        assertEquals(demoted, task.exactRemaining().exactValue());
        assertEquals(demoted.longValueExact(), task.value, "回到 long 域后镜像即精确值");
        assertFalse(task.isFinished());

        // 跑完最后一段：只有精确归零才算完成
        task.consumeFirings(task.exactRemaining().fastValue());
        assertTrue(task.isFinished());
        assertEquals(0L, task.value);
    }

    /** 规划侧宽账 + AE2 那份饱和字典同源：一份 {@link WideCraftCounter} 同时交出两样。 */
    private static ICraftingPlan widePlan(IPatternDetails pattern, BigInteger firings) {
        var crafts = new WideCraftCounter();
        crafts.add(pattern, PlanAmount.of(firings));
        return new FakePlan(crafts.longView(), WideCraftSnapshot.capture(crafts));
    }

    private static ICraftingPlan emptyPlan() {
        return new FakePlan(Map.of(), WideCraftSnapshot.empty());
    }

    private static PreparedBatch preparedBatch(long count) {
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

    private static final class NoopEnergyService implements IEnergyService {
        @Override
        public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
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

    private record FakePlan(Map<IPatternDetails, Long> patternTimes,
            WideCraftSnapshot wideCrafts) implements ICraftingPlan, WideAmountPlanCarrier {

        private static final KeyCounter EMPTY = new KeyCounter();

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
            return EMPTY;
        }

        @Override
        public KeyCounter emittedItems() {
            return EMPTY;
        }

        @Override
        public KeyCounter missingItems() {
            return EMPTY;
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return patternTimes;
        }

        @Override
        public WideLedgerSnapshot chexsonsaeutils$wideLedgers() {
            return WideLedgerSnapshot.empty();
        }

        @Override
        public WideCraftSnapshot chexsonsaeutils$wideCrafts() {
            return wideCrafts;
        }
    }

    private record FakePattern(AEItemKey definition,
            GenericStack output) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(output);
        }
    }
}
