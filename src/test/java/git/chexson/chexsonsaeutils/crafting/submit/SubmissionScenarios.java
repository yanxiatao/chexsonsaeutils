package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规划入口与提交的行为场景；由类别测试统一调用。 */
public final class SubmissionScenarios {
    private SubmissionScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.submit",
                () -> PartialSubmitAwaitScopeCases.verify(temporaryRoot),
                () -> PartialSubmitExactStorageCases.verify(temporaryRoot)
        );
    }

    /** PartialSubmitAwaitScopeCases 的独立场景夹具，按类别统一执行。 */
    public static final class PartialSubmitAwaitScopeCases {
        private static final AEKey LAVA = AEItemKey.of(Items.LAVA_BUCKET);
        private static final AEKey ORE = AEItemKey.of(Items.IRON_ORE);
        private static final AEKey INGOT = AEItemKey.of(Items.IRON_INGOT);



        /**
         * 提交这一步<b>不搬</b>的那一截不许挂进"正在合成"：需求越出 long 时这条腿也只喂一刀，
         * 剩下的那一截由执行侧按批常态续取——把它挂进 {@code waitingFor} 就是界面那个永远不降的
         * {@code 9.2E}，而 {@code waitingFor} 不清空作业连完成都判不了。
         *
         * <p>反向半边同一条用例钉住：一份<b>装得进 long</b> 的真缺口照旧要挂账等实物，
         * 不能因为"提交不吸全量"就把真缺料也免掉。
         */
        void beyondLongRemainderIsNotAwaitedButARealLongGapStillIs() {
            var demand = new BigInteger("14000000000000000000"); // 1.4e19 > long
            var network = new WideNetwork(LAVA, demand);
            var cpu = new WideCraftingInventory(ignored -> {
            });

            var missing = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    planWithWideUsed(LAVA, demand), gridOf(network), cpu, IActionSource.empty());

            assertTrue(missing.isEmpty(), ">long 那一截不是在途产出：挂上就永远等不到 " + missing);
            assertEquals(1, network.asks, "点开始键这一步只伸手一次");
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE), WideCraftingInventory.amount(cpu, LAVA),
                    "一刀照收：提交不搬全量不等于什么都不搬");

            // 反向：网络连一口都给不满（真缺料），缺口装得进 long ⇒ 照挂等实物
            var shortNetwork = new WideNetwork(ORE, BigInteger.valueOf(100L));
            var shortCpu = new WideCraftingInventory(ignored -> {
            });
            var shortMissing = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    planWithUsed(ORE, 500L), gridOf(shortNetwork), shortCpu, IActionSource.empty());
            assertEquals(400L, shortMissing.get(ORE),
                    "真缺口 400 枚照旧挂账，一个都不许多免");
        }

        /**
         * 原生树那份计划（AE2 自己的 {@code CraftingPlan}，<b>没有任何我方载体</b>）进这条腿时的数域
         * 判据（台账 #155/T12「回落后不再管 bigint」）：需求取它自己那份 {@code long} 读数，缺口按
         * 「读数 − 真到账」报，一步只伸手一次。
         *
         * <p>这条断的是<b>数域</b>而不是挂账范围，与上面那条各判各的：把真值取数那一句换成哨兵
         * {@code Long.MAX_VALUE}（= 给原生计划的账宽域化／拿哨兵当量）当场红在 {@code 400} 那一格。
         * 宽域读口同用例先断一次为空——原生形状没有账可读，这条腿也就没有东西可"宽域化"。
         */
        void nativePlanWithoutCarrierIsSubmittedByItsOwnLongReadings() {
            var network = new WideNetwork(ORE, BigInteger.valueOf(100L));
            var cpu = new WideCraftingInventory(ignored -> {
            });
            var plan = new CraftingPlan(new GenericStack(INGOT, 1L), 0L, false, false,
                    counter(ORE, 500L), new KeyCounter(), new KeyCounter(), java.util.Map.of());

            assertTrue(WideAmountPlanCarrier.resolveWideLedgers(plan).isEmpty(),
                    "原生形状的计划读不出任何宽域账 " + WideAmountPlanCarrier.resolveWideLedgers(plan));

            var missing = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(network), cpu, IActionSource.empty());

            assertEquals(400L, missing.get(ORE),
                    "原生腿的缺口＝它自己那份 long 读数减真到账量 500−100=400，不许是饱和出来的大数");
            assertEquals(1, network.asks, "原生腿这一步每键只伸手一次");
        }

        void missingOnlyPlanRegistersTheFullRefillAndCombinedDemandDoesNotOverflow() {
            var missing = counter(ORE, 9);
            var plan = new CraftingPlan(new GenericStack(INGOT, 1), 0, true, false,
                    new KeyCounter(), new KeyCounter(), missing, java.util.Map.of());
            var cpu = new WideCraftingInventory(ignored -> {});
            var gap = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                    plan, gridOf(new WideNetwork(ORE, BigInteger.ZERO)), cpu, IActionSource.empty());
            assertEquals(9L, gap.get(ORE), "原生计划 used 为空、missing 为 9 时，仍须登记全部补料");
            var combined = new CraftingPlan(new GenericStack(INGOT, 1), 0, true, false,
                    counter(ORE, Long.MAX_VALUE), new KeyCounter(), counter(ORE, 9), java.util.Map.of());
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(9)),
                    CraftingContinuationPartialSubmit.requiredInitialAmounts(combined).getExact(ORE));
        }

        /** 只带 used 宽域溢出账的计划：AE2 那份 long 读数顶在天花板上，真值挂在载体上。 */
        private static WideAmountCraftingPlan planWithWideUsed(AEKey key, BigInteger exactUsed) {
            var wideUsed = new WideKeyCounter();
            wideUsed.setExact(key, exactUsed);
            var base = new CraftingPlan(new GenericStack(key, 1L), 0L, false, false,
                    counter(key, Long.MAX_VALUE), new KeyCounter(), new KeyCounter(),
                    java.util.Map.of());
            return new WideAmountCraftingPlan(base, WideLedgerSnapshot.capture(null, wideUsed, null),
                    null, Set.of());
        }

        private static WideAmountCraftingPlan planWithUsed(AEKey key, long amount) {
            var base = new CraftingPlan(new GenericStack(key, 1L), 0L, false, false,
                    counter(key, amount), new KeyCounter(), new KeyCounter(), java.util.Map.of());
            return new WideAmountCraftingPlan(base, null, null, Set.of());
        }

        private static KeyCounter counter(AEKey key, long amount) {
            var counter = new KeyCounter();
            counter.add(key, amount);
            return counter;
        }

        /** 这条腿只问 {@code grid.getStorageService().getInventory()} 那一张嘴。 */
        private static IGrid gridOf(MEStorage storage) {
            return git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder()
                    .liveStorage(storage).build();
        }


        /** 持 {@code total} 枚（宽域）、每次最多交出一个 {@code long} 的网络桩。 */
        private static final class WideNetwork implements MEStorage {
            private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
            private final AEKey what;
            private BigInteger remaining;
            private int asks;

            private WideNetwork(AEKey what, BigInteger total) {
                this.what = what;
                this.remaining = total;
            }

            @Override
            public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
                if (!what.equals(key)) {
                    return 0L;
                }
                asks++;
                var granted = remaining.min(BigInteger.valueOf(amount)).min(LONG_MAX);
                if (mode == Actionable.MODULATE) {
                    remaining = remaining.subtract(granted);
                }
                return granted.longValue();
            }

            @Override
            public Component getDescription() {
                return Component.literal("wide-network");
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PartialSubmitAwaitScopeCases",
                () -> new PartialSubmitAwaitScopeCases().missingOnlyPlanRegistersTheFullRefillAndCombinedDemandDoesNotOverflow(),
                () -> org.junit.jupiter.api.Assertions.assertAll("PartialSubmitAwaitScopeCases.beyondLongRemainderIsNotAwaitedButARealLongGapStillIs", () -> new PartialSubmitAwaitScopeCases().beyondLongRemainderIsNotAwaitedButARealLongGapStillIs()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PartialSubmitAwaitScopeCases.nativePlanWithoutCarrierIsSubmittedByItsOwnLongReadings", () -> new PartialSubmitAwaitScopeCases().nativePlanWithoutCarrierIsSubmittedByItsOwnLongReadings())
            );
        }
    }

    /** PartialSubmitExactStorageCases 的独立场景夹具，按类别统一执行。 */
    public static final class PartialSubmitExactStorageCases {
        void ignoreMissingSubmissionRecordsActualWideReceiptInFamilyLedger() {
            var key = AEItemKey.of(Items.COPPER_INGOT);
            var demand = BigInteger.TEN.pow(100);
            var used = new WideKeyCounter(); used.setExact(key, demand);
            var projected = new KeyCounter(); projected.add(key, Long.MAX_VALUE);
            var base = new CraftingPlan(new GenericStack(key, 1), 0, false, false,
                    projected, new KeyCounter(), new KeyCounter(), Map.of());
            var plan = new WideAmountCraftingPlan(base, WideLedgerSnapshot.capture(null, used, null), null, Set.of());
            for (var have : java.util.List.of(demand, demand.divide(BigInteger.TWO))) {
                var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
                cell.insertExact(key, have, Actionable.MODULATE, IActionSource.empty());
                var pool = new WideCraftingInventory(ignored -> {});
                var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
                assertTrue(CraftingContinuationPartialSubmit.extractAvailableInitialItemsFromStorage(
                        plan, cell, pool, IActionSource.empty(), arrivals).isEmpty());
                assertEquals(have, pool.amount(key));
                assertEquals(BigInteger.ZERO, cell.exactAmount(key));
                assertEquals(1, arrivals.families().size());
                var family = arrivals.families().iterator().next();
                assertEquals(have, family.arrived().exactValue());
                assertEquals(demand.subtract(have), family.outsideArrivals().exactValue());
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PartialSubmitExactStorageCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PartialSubmitExactStorageCases.ignoreMissingSubmissionRecordsActualWideReceiptInFamilyLedger", () -> new PartialSubmitExactStorageCases().ignoreMissingSubmissionRecordsActualWideReceiptInFamilyLedger())
            );
        }
    }
}
