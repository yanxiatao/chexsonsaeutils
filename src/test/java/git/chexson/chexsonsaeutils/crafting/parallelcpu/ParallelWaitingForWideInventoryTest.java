package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行 CPU 产出缓冲（{@code waitingFor}）换装溢出侧表后的作业级用例。
 *
 * <p>钉三件事：
 * <ul>
 *   <li>等待产出的容器使用宽域实现；</li>
 *   <li>同一产物跨批挂账累计越出 {@code long} 时，作业记得住精确值，而对 AE2 的那条读口
 *       （{@code getWaitingFor} 用的就是 {@code extract(key, Long.MAX_VALUE, SIMULATE)}）
 *       仍报饱和 {@code Long.MAX_VALUE}；</li>
 *   <li>常态（不越界）挂账写出的 NBT 形状与换实现前一字不差，不多挂覆盖键。</li>
 * </ul>
 *
 * <p>夹具沿用本包既有口径（空计划 + 空 link），不起第二套。
 */
final class ParallelWaitingForWideInventoryTest {

    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** 作业使用宽域容器记录等待产出的精确数量。 */
    @Test
    void waitingForUsesTheWideImplementation() {
        assertInstanceOf(WideCraftingInventory.class, newJob().waitingFor);
    }

    /** 同一产物跨三批各挂 {@code Long.MAX_VALUE} 枚：精确账是 3×，对 AE2 的读数钉在天花板。 */
    @Test
    void waitingForRemembersTheExactAmountAcrossBatchesBeyondLong() {
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, Long.MAX_VALUE);

        for (int batch = 1; batch <= 3; batch++) {
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        }

        assertEquals(LONG_MAX.multiply(BigInteger.valueOf(3L)), job.waitingForExact(PRODUCT).exactValue(),
                "跨批挂账逐位精确：这才是「库存本身能记 >long」的意思");
        assertEquals(Long.MAX_VALUE, getWaitingForReadout(job, PRODUCT),
                "getWaitingFor 那条基类身份读口：溢出键报饱和 long，与无限盘对网络报数同一口径");
        assertEquals(Long.MAX_VALUE, job.waitingFor.list.get(PRODUCT));
        assertTrue(job.waitingFor.list.get(PRODUCT) > 0L,
                "读数不绕回成负数，回池夹取不会被判成「没料」");
        assertEquals(0L, job.expectedToNetwork(PRODUCT).signum(), "本用例一笔都没入网，两本账不串");
    }

    /** 越界那截被物理回池逐步抽走：AE2 读数按批下降，精确账同步下降，绝不双计也绝不少扣。 */
    @Test
    void returningPhysicalOutputShavesTheWideLedgerBatchByBatch() {
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, Long.MAX_VALUE);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        assertEquals(LONG_MAX.multiply(BigInteger.TWO), job.waitingForExact(PRODUCT).exactValue());

        // 一批回池：insertInternal 先 SIMULATE 夹取、再 MODULATE 抽走
        long accepted = job.waitingFor.extract(PRODUCT, getWaitingForReadout(job, PRODUCT),
                Actionable.MODULATE);
        assertEquals(Long.MAX_VALUE, accepted);
        assertEquals(LONG_MAX, job.waitingForExact(PRODUCT).exactValue());
        assertEquals(Long.MAX_VALUE, getWaitingForReadout(job, PRODUCT));

        assertEquals(1L, job.waitingFor.extract(PRODUCT, 1L, Actionable.MODULATE));
        assertEquals(LONG_MAX.subtract(BigInteger.ONE), job.waitingForExact(PRODUCT).exactValue());
    }

    /** 常态挂账（不越界）：写出的 NBT 与老实现逐字相同，一个覆盖键都不多挂。 */
    @Test
    void inRangeReservationsWriteTheSameNbtShapeAsBefore() {
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, 1_000_000_000_000L);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);

        var plain = new ListCraftingInventory(ignored -> {
        });
        plain.insert(PRODUCT, 1_000_000_000_000L, Actionable.MODULATE);

        ListTag written = job.waitingFor.writeToNBT(REGISTRIES);
        assertEquals(plain.writeToNBT(REGISTRIES), written,
                "作业落盘的 waitingFor 列表形状不变：老存档读得动，别的 mod 也读得动");
        assertEquals(0, countWideEntries(written));
    }

    /** 越界挂账进出 NBT 逐位相等，且读回后对 AE2 仍报饱和读数（作业级：走的就是那两条读写口）。 */
    @Test
    void wideReservationRoundTripsThroughTheJobReadAndWritePorts() {
        var job = newJob();
        var expected = new KeyCounter();
        expected.add(PRODUCT, Long.MAX_VALUE);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, new KeyCounter(), null);
        var exact = LONG_MAX.multiply(BigInteger.TWO);

        ListTag written = job.waitingFor.writeToNBT(REGISTRIES);
        assertEquals(1, countWideEntries(written), "只有真越界那条挂覆盖键");
        assertFalse(job.waitingFor.list.isEmpty());

        var restored = new WideCraftingInventory(ignored -> {
        });
        restored.readFromNBT(written, REGISTRIES);
        assertEquals(exact, WideCraftingInventory.amount(restored, PRODUCT));
        assertEquals(Long.MAX_VALUE, restored.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    // ---- 夹具 ----

    /** 与 {@code ParallelCraftingCpuLogic.getWaitingFor} 逐字同一条调用：以基类身份问还剩多少。 */
    private static long getWaitingForReadout(ParallelExecutingCraftingJob job, AEItemKey what) {
        return job.waitingFor.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    private static int countWideEntries(ListTag tag) {
        int count = 0;
        for (int index = 0; index < tag.size(); index++) {
            if (tag.getCompound(index).contains(
                    WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)) {
                count++;
            }
        }
        return count;
    }

    private static ParallelExecutingCraftingJob newJob() {
        return new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
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
}
