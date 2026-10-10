package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作业产出侧的<b>预期入网</b>账用例：宿主把产物不经 CPU 池子直接交出去时，账记得住、对得齐、存得回。
 *
 * <p><b>这本账记的是预期，不是交付</b>：落账枚数出自宿主按配方自报的计划量，一条都不是入网回执
 * （见 {@code UselessBigIntegerReceipt} 的类注释）；它只回答"这一截不会再有实物回池子"。
 *
 * <p>三条底线各有一个用例钉住：
 * <ul>
 *   <li>跨批复加逐位精确——每批 ≤ {@code long}（机器的切段粒度），合计越出 {@code long} 也不截断；</li>
 *   <li>"实物进容器"与"预期入网"是同一笔申报产出的<b>一次划分</b>：合计恒等于申报量，不重复计；</li>
 *   <li>这笔账随作业进出 NBT，编码风格与 {@code #craftingProgressWide} 同一条规则。</li>
 * </ul>
 *
 * <p>量级用 {@code 3 × 3.5e18 = 1.05e19} 枚（{@code Long.MAX_VALUE} 是 9.22e18）：正是会被
 * {@code KeyCounter} 截断掉的那一档。夹具沿用本包既有口径，不起第二套。
 */
final class ParallelExpectedOutputLedgerTest {

    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GEAR = AEItemKey.of(Items.CLOCK);
    /** 回流容器（模具/桶那一类投下去原样退回的键）：只有实物一条路。 */
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

    private static final long PER_BATCH_EXPECTED = 4_000_000_000_000_000_000L;
    private static final long PER_BATCH_PLANNED = 3_500_000_000_000_000_000L;

    /** 三批全额按自报计划入网：合计 3×Long.MAX_VALUE，逐位等于申报产出，且 {@code waitingFor} 一格都不挂。 */
    @Test
    void plannedNetworkOutputsAddUpAcrossBatchesWithoutLongTruncation() {
        var job = newJob();
        var declaredTotal = BigInteger.ZERO;

        for (int batch = 1; batch <= 3; batch++) {
            var expected = new KeyCounter();
            expected.add(PRODUCT, Long.MAX_VALUE);
            var delivered = new KeyCounter();
            delivered.add(PRODUCT, Long.MAX_VALUE);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(
                    job, expected, new KeyCounter(), delivered);
            declaredTotal = declaredTotal.add(BigInteger.valueOf(Long.MAX_VALUE));
        }

        var exactTotal = BigInteger.valueOf(3L).multiply(BigInteger.valueOf(Long.MAX_VALUE));
        assertEquals(declaredTotal, exactTotal, "申报总量本身就该是 3×Long.MAX_VALUE");
        assertEquals(exactTotal, job.expectedToNetwork(PRODUCT).exactValue(),
                "预期入网账跨批复加逐位精确，绝不被 long 截断");
        assertTrue(exactTotal.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                "本用例量的确实越出了 long：这正是老口径记不住的那一档");
        assertEquals(0L, job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "全额入网的产物不再挂 waitingFor 预期：那笔实物永远不会回 CPU");
    }

    /** 一批申报里两条去向并存：实物差额 + 预期入网合计恒等于申报量，宿主报多了也不重复计。 */
    @Test
    void physicalAndNetworkRoutesSumExactlyWithoutDoubleCounting() {
        var job = newJob();
        var declaredTotal = BigInteger.ZERO;

        for (int batch = 1; batch <= 3; batch++) {
            var expected = new KeyCounter();
            expected.add(PRODUCT, PER_BATCH_EXPECTED);
            var containers = new KeyCounter();
            containers.add(BUCKET, 1L);
            var delivered = new KeyCounter();
            delivered.add(PRODUCT, PER_BATCH_PLANNED);
            ParallelCraftingCpuLogic.reserveExpectedWaiting(job, expected, containers, delivered);
            declaredTotal = declaredTotal.add(BigInteger.valueOf(PER_BATCH_EXPECTED));
        }

        var netPart = job.expectedToNetwork(PRODUCT).exactValue();
        long containerPart = job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE);
        assertEquals(BigInteger.valueOf(PER_BATCH_PLANNED).multiply(BigInteger.valueOf(3L)),
                netPart, "预期入网那一截跨批复加精确（1.05e19 枚）");
        assertEquals(3L * (PER_BATCH_EXPECTED - PER_BATCH_PLANNED), containerPart,
                "waitingFor 只挂没入网的那一截差额");
        assertEquals(declaredTotal, netPart.add(BigInteger.valueOf(containerPart)),
                "实物 + 预期入网 = 申报总量：合计精确，不多也不少");

        assertEquals(3L, job.waitingFor.extract(BUCKET, Long.MAX_VALUE, Actionable.SIMULATE),
                "回流容器整笔挂预期：它必须实物回 CPU 才再投得出去");
        assertTrue(job.expectedToNetwork(BUCKET).signum() == 0,
                "分流只作用于产出，容器件一条都不进预期入网账");

        // 宿主报超出申报：多出的那一枚不认（与"申报 ∩ 实收"同一条口径）
        var overExpected = new KeyCounter();
        overExpected.add(PRODUCT, 10L);
        var overDelivered = new KeyCounter();
        overDelivered.add(PRODUCT, 11L);
        var netBefore = job.expectedToNetwork(PRODUCT).exactValue();
        long containerBefore = job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(
                job, overExpected, new KeyCounter(), overDelivered);
        assertEquals(netBefore.add(BigInteger.TEN), job.expectedToNetwork(PRODUCT).exactValue(),
                "超出申报的 1 枚不重复入账");
        assertEquals(containerBefore,
                job.waitingFor.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "申报全额按自报计划入网：不再挂任何实物预期");

        // 申报表之外的键：一笔账都不许添（改前那一支把它们"全额按已入网结算"并顺手复加报称账，
        // 而生产侧的申报表与宿主自报计划同出 details.getOutputs()，那一支在真宿主上永不命中）
        var outsidePlanned = new KeyCounter();
        outsidePlanned.add(GEAR, 7L);
        ParallelCraftingCpuLogic.reserveExpectedWaiting(
                job, new KeyCounter(), new KeyCounter(), outsidePlanned);
        assertEquals(0L, job.expectedToNetwork(GEAR).signum(),
                "申报表之外的键不进预期入网账");
        assertEquals(0L, job.accountAnnounced.getLong(GEAR),
                "也不进报称产出流水账：这张样板从没申报过它");
        assertEquals(0L, job.waitingFor.extract(GEAR, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    /** 预期入网账进出 NBT 逐位相等；能塞 {@code long} 的条目不多挂宽域键，缺宽域键即按老 {@code long} 读。 */
    @Test
    void plannedLedgerRoundTripsThroughJobNbtBitForBit() {
        var wideExact = new BigInteger("100000000000000000001234567890");
        var delivered = new WideKeyCounter();
        delivered.add(PRODUCT, Long.MAX_VALUE);
        delivered.add(GEAR, wideExact);

        var data = new CompoundTag();
        ParallelExecutingCraftingJob.writeExpectedNetworkOutputs(data, REGISTRIES, delivered);

        var restored = new WideKeyCounter();
        ParallelExecutingCraftingJob.readExpectedNetworkOutputs(data, REGISTRIES, restored);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), restored.getExact(PRODUCT));
        assertEquals(wideExact, restored.getExact(GEAR), "越界条目含低位特征数字，逐位还原");

        ListTag entries = data.getList(
                ParallelExecutingCraftingJob.NBT_OUTPUTS_EXPECTED_TO_NETWORK, Tag.TAG_COMPOUND);
        assertEquals(2, entries.size());
        int wideEntries = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (entries.getCompound(index).contains(
                    ParallelExecutingCraftingJob.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)) {
                wideEntries++;
            }
        }
        assertEquals(1, wideEntries, "只有真越界的条目多挂宽域键");

        // 常态零预期：一个键都不挂，不是迁移壳
        var bare = new CompoundTag();
        ParallelExecutingCraftingJob.writeExpectedNetworkOutputs(bare, REGISTRIES, new WideKeyCounter());
        assertFalse(bare.contains(ParallelExecutingCraftingJob.NBT_OUTPUTS_EXPECTED_TO_NETWORK,
                Tag.TAG_LIST));

        // 缺宽域键就是"这笔装得进 long"：按老 long 读数原样吃回来
        var legacyEntry = GEAR.toTagGeneric(REGISTRIES);
        legacyEntry.putLong("#", 42L);
        var legacyList = new ListTag();
        legacyList.add(legacyEntry);
        var legacy = new CompoundTag();
        legacy.put(ParallelExecutingCraftingJob.NBT_OUTPUTS_EXPECTED_TO_NETWORK, legacyList);
        var fromLegacy = new WideKeyCounter();
        ParallelExecutingCraftingJob.readExpectedNetworkOutputs(legacy, REGISTRIES, fromLegacy);
        assertEquals(BigInteger.valueOf(42L), fromLegacy.getExact(GEAR));
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
