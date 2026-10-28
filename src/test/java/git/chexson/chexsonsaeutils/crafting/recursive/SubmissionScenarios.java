package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规划入口与提交的行为场景；由类别测试统一调用。 */
public final class SubmissionScenarios {
    private SubmissionScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.recursive",
                () -> RecursiveTaskOrderingCases.verify(temporaryRoot),
                () -> RingIdCases.verify(temporaryRoot),
                () -> RingSeedLedgerCases.verify(temporaryRoot)
        );
    }

    /** RecursiveTaskOrderingCases 的独立场景夹具，按类别统一执行。 */
    public static final class RecursiveTaskOrderingCases {
        private static final AEItemKey SEED = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey BRIDGE = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey OUTPUT = AEItemKey.of(Items.CLOCK);

        void consumerKeepsConfiguredInternalReserve() {
            var consumer = FakePattern.of(OUTPUT, 1L, new FakeInput(SEED, 1L));
            var internal = counter(SEED, 4L);
            var inventory = inventory(SEED, 4L);

            assertEquals(4L, RecursiveTaskOrdering.requiredPostExecutionReserve(
                    consumer, internal, Map.of(consumer, progress(1L))).get(SEED));
            assertTrue(RecursiveTaskOrdering.shouldDeferConsumer(
                    consumer, internal, inventory, Map.of(consumer, progress(1L))));
        }

        void pendingReturnPathMayUseSeedToUnlockProducer() {
            var consumer = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(SEED, 1L)},
                    new GenericStack(BRIDGE, 1L)
            );
            var producer = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(BRIDGE, 1L)},
                    new GenericStack(SEED, 2L),
                    new GenericStack(OUTPUT, 1L)
            );
            Map<appeng.api.crafting.IPatternDetails, RecursiveTaskOrdering.ParallelTaskProgressView> tasks =
                    Map.of(consumer, progress(1L), producer, progress(1L));
            var internal = counter(SEED, 1L);
            var inventory = inventory(SEED, 1L);

            assertEquals(0L, RecursiveTaskOrdering.requiredPostExecutionReserve(
                    consumer, internal, tasks).get(SEED));
            assertFalse(RecursiveTaskOrdering.shouldDeferConsumer(
                    consumer, internal, inventory, tasks));
        }

        private static RecursiveTaskOrdering.ParallelTaskProgressView progress(long value) {
            return () -> value;
        }

        private static KeyCounter counter(AEItemKey key, long amount) {
            var counter = new KeyCounter();
            counter.add(key, amount);
            return counter;
        }

        private static ListCraftingInventory inventory(AEItemKey key, long amount) {
            var inventory = new ListCraftingInventory(ignored -> {
            });
            inventory.insert(key, amount, Actionable.MODULATE);
            return inventory;
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RecursiveTaskOrderingCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RecursiveTaskOrderingCases.consumerKeepsConfiguredInternalReserve", () -> new RecursiveTaskOrderingCases().consumerKeepsConfiguredInternalReserve()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RecursiveTaskOrderingCases.pendingReturnPathMayUseSeedToUnlockProducer", () -> new RecursiveTaskOrderingCases().pendingReturnPathMayUseSeedToUnlockProducer())
            );
        }
    }

    /** RingIdCases 的独立场景夹具，按类别统一执行。 */
    public static final class RingIdCases {
        private static AEItemKey key(Item item) {
            return AEItemKey.of(new ItemStack(item));
        }

        private static FakePattern pattern(Item definitionItem, Item outputItem) {
            return FakePattern.colored(
                    -1,
                    AEItemKey.of(new ItemStack(definitionItem)),
                    List.of(new GenericStack(key(outputItem), 1L))
            );
        }

        void sameEntrySameMembersMatchRegardlessOfIterationOrder() {
            var a = pattern(Items.STONE, Items.PAPER);
            var b = pattern(Items.COBBLESTONE, Items.IRON_INGOT);
            var entryPoint = key(Items.PAPER);

            assertEquals(
                    RingId.of(entryPoint, List.of(a, b)),
                    RingId.of(entryPoint, List.of(b, a)));
        }

        void differentTopologiesProduceDifferentIds() {
            var entryPoint = key(Items.PAPER);
            var a = pattern(Items.STONE, Items.PAPER);
            var b = pattern(Items.COBBLESTONE, Items.IRON_INGOT);

            assertNotEquals(RingId.of(entryPoint, List.of(a)), RingId.of(entryPoint, List.of(b)));
            assertNotEquals(RingId.of(entryPoint, List.of(a)), RingId.of(entryPoint, List.of(a, b)));
        }

        void colorDoesNotDistinguishIds() {
            var uncolored = FakePattern.colored(
                    -1,
                    key(Items.PAPER),
                    List.of(new GenericStack(key(Items.PAPER), 1L)));
            var red = FakePattern.colored(
                    0xFFFF0000,
                    git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.dyedKey(Items.PAPER, 0xFFFF0000),
                    List.of(new GenericStack(key(Items.PAPER), 1L)));
            var blue = FakePattern.colored(
                    0xFF0000FF,
                    git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.dyedKey(Items.PAPER, 0xFF0000FF),
                    List.of(new GenericStack(key(Items.PAPER), 1L)));
            var uncoloredId = RingId.of(key(Items.PAPER), List.of(uncolored));

            assertEquals(uncoloredId, RingId.of(key(Items.PAPER), List.of(red)));
            assertEquals(uncoloredId, RingId.of(key(Items.PAPER), List.of(blue)));
            assertEquals(
                    RingId.of(key(Items.PAPER), List.of(uncolored, red)),
                    RingId.of(key(Items.PAPER), List.of(red, blue)));
        }

        void nullMembersAreStableAndDistinctFromNonEmpty() {
            var entryPoint = key(Items.PAPER);
            var empty = RingId.of(entryPoint, null);

            assertEquals(empty, RingId.of(entryPoint, null));
            assertEquals(empty, RingId.of(entryPoint, List.of()));
            assertNotEquals(empty, RingId.of(entryPoint, List.of(pattern(Items.STONE, Items.PAPER))));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RingIdCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RingIdCases.sameEntrySameMembersMatchRegardlessOfIterationOrder", () -> new RingIdCases().sameEntrySameMembersMatchRegardlessOfIterationOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingIdCases.differentTopologiesProduceDifferentIds", () -> new RingIdCases().differentTopologiesProduceDifferentIds()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingIdCases.colorDoesNotDistinguishIds", () -> new RingIdCases().colorDoesNotDistinguishIds()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingIdCases.nullMembersAreStableAndDistinctFromNonEmpty", () -> new RingIdCases().nullMembersAreStableAndDistinctFromNonEmpty())
            );
        }
    }

    /** RingSeedLedgerCases 的独立场景夹具，按类别统一执行。 */
    public static final class RingSeedLedgerCases {
        void provenAcyclicUniverseSkipsRepeatedProgressAndTopologyReadsButInvalidatesOnNewPattern() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var first = of(Items.PAPER, FakeInput.stack(ORE, 1), new GenericStack(SEED, 1));
            var second = of(Items.BOOK, FakeInput.stack(SEED, 1), new GenericStack(PRODUCT, 1));
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(first, 5L);
            tasks.put(second, 5L);
            var ledger = new RingSeedLedger<>(tasks, count -> { calls.incrementAndGet(); return PlanAmount.of(count); });
            assertTrue(ledger.seedAmounts().isEmpty());
            assertEquals(2, calls.get());
            calls.set(0);
            tasks.put(first, 0L);
            tasks.remove(second);
            assertTrue(ledger.seedDemandsByFamily().isEmpty());
            assertTrue(ledger.seedAmounts().isEmpty());
            assertEquals(0, calls.get(), "无环图的子图仍无环，不应重复读取发数重建 SCC");
            var ring = selfFeedingRing();
            tasks.put(ring, 3L);
            assertEquals(1, ledger.seedAmounts().get(SEED));
            tasks.put(ring, 0L);
            assertTrue(ledger.seedAmounts().isEmpty());
            tasks.put(ring, 4L);
            assertEquals(1, ledger.seedAmounts().get(SEED));
        }

        void inactiveCycleMemberCannotBeExcludedFromAnAcyclicCertificate() {
            var forward = of(Items.PAPER, FakeInput.stack(ORE, 1), new GenericStack(SEED, 1));
            var back = of(Items.BOOK, FakeInput.stack(SEED, 1), new GenericStack(ORE, 1));
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(forward, 2L);
            tasks.put(back, 0L);
            var ledger = new RingSeedLedger<>(tasks, PlanAmount::of);
            assertTrue(ledger.seedAmounts().isEmpty());
            tasks.put(back, 2L);
            assertTrue(!ledger.seedAmounts().isEmpty(), "恢复零发成员后必须重新识别闭环");
        }

        private static final AEKey SEED = AEItemKey.of(Items.NETHERITE_SCRAP);
        private static final AEKey ORE = AEItemKey.of(Items.IRON_ORE);
        private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEKey RIVAL_OUT = AEItemKey.of(Items.COAL_BLOCK);

        private static FakePattern of(Item definitionItem, FakeInput slot, GenericStack... outputs) {
            return FakePattern.colored(-1, AEItemKey.of(new ItemStack(definitionItem)),
                    List.of(outputs), slot);
        }

        private static FakePattern rival() {
            return of(Items.BOOK, FakeInput.stack(SEED, 1L), new GenericStack(RIVAL_OUT, 1L));
        }

        private static FakePattern selfFeedingRing() {
            return of(Items.FLOWER_POT, FakeInput.stack(SEED, 1L),
                    new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
        }

        void starterAmountIsPerFiringConsumption() {
            FakePattern ring = selfFeedingRing();
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(ring, 7L);
            tasks.put(rival(), 3L);
            var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

            assertEquals(1L, ledger.seedAmounts().get(SEED),
                    "起步份数按一发吃掉该键的量计，不是整场用量");
        }

        /** 账本交给 RecursiveTaskOrdering 之后，环生产者才排在旁人之前。 */
        void derivedSeedsPutRingProducersFirst() {
            FakePattern ring = selfFeedingRing();
            FakePattern rival = rival();
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(rival, 3L);
            tasks.put(ring, 7L);
            var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

            var ordered = RecursiveTaskOrdering.orderedEntries(tasks, ledger.seedAmounts());

            assertSame(ring, ordered.get(0).getKey(), "环生产者没有排到旁人之前");
        }

        /** 环任务转完之后账本自动失效，不再把任何键标成要留种的。 */
        void seedAccountDropsWithTheRingTask() {
            FakePattern ring = selfFeedingRing();
            FakePattern rival = rival();
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(ring, 7L);
            tasks.put(rival, 3L);
            var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));
            assertEquals(1L, ledger.seedAmounts().get(SEED));

            tasks.remove(ring);

            assertTrue(ledger.seedAmounts().isEmpty(), "环已经转完了，账本还记着种子");
        }

        /** 链式环（A 吃 SEED 出 ORE、B 吃 ORE 回 SEED）里没有任何样板把物料还给自己。 */
        void chainRingLinksBothHaveStarterAmounts() {
            FakePattern emitsLink = of(Items.BARRIER, FakeInput.stack(SEED, 1L),
                    new GenericStack(ORE, 1L), new GenericStack(PRODUCT, 1L));
            FakePattern consumesLink = of(Items.BRUSH, FakeInput.stack(ORE, 1L),
                    new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
            FakePattern rival = rival();
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(emitsLink, 4L);
            tasks.put(consumesLink, 4L);
            tasks.put(rival, 2L);
            var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

            assertEquals(1L, ledger.seedAmounts().get(SEED));
            assertEquals(1L, ledger.seedAmounts().get(ORE), "链上中转的那一枚也要有起步量");

            var ordered = RecursiveTaskOrdering.orderedEntries(tasks, ledger.seedAmounts());
            assertSame(rival, ordered.get(ordered.size() - 1).getKey(),
                    "旁人在环转起来之前就可以抢料");
        }

        /**
         * 每发吃掉得比还回来的多（本例 {@code owed=2×3=6}、{@code handedBack=1×3=3}）：这一环本来就净吃
         * 那一枚种，缺口由规划期起步提取补——按"环优先跑，跑完环再放行"的口径它<b>照样是环种、照样进账</b>。
         *
         * <p>旧口径"分量内产出不少于消耗才算有回炉能力"在这里整键撤账 ⇒ 取料点不产留底 ⇒ 池里那颗种
         * 在环转起来之前被旁人一发抽干。册子按<b>精确种键</b>各交两笔每发量（吃几枚、还几枚），
         * <b>留底取哪一截</b>由取料点按同一趟的复加现算（见 {@code ParallelPlanReservation#buildRingSeedReserve}）。
         */
        void netConsumingKeyIsStillASeedAndHandsOutBothPerFiringLegs() {
            FakePattern netConsumer = of(Items.CALIBRATED_SCULK_SENSOR, FakeInput.stack(SEED, 2L),
                    new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(netConsumer, 3L);
            var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

            assertEquals(2L, ledger.seedAmounts().get(SEED),
                    "owed>handedBack 那一档照样进账：起步量仍按一发吃掉该键的量计");
            var demand = ledger.seedDemandsByFamily().get(SEED.getPrimaryKey());
            assertNotNull(demand, "吃它的那一张同时还在还它（一发还 1 枚）⇒ 它与自己同分量 ⇒ 必须在册"
                    + "（旧口径按 owed>handedBack 在这里整键撤账）");
            assertEquals(1, demand.seedKeys().size(), "这一族下只有 SEED 那一枚精确键在册");
            var seedKey = demand.seedKeys().get(0);
            assertEquals(SEED, seedKey.seed(), "在册的是那一枚精确键本身，不是它的族");
            assertEquals(Map.of(netConsumer, 2L), seedKey.perFiringConsume(),
                    "每发吃掉几枚：留底那笔复加的负债侧");
            assertEquals(Map.of(netConsumer, 1L), seedKey.perFiringReturn(),
                    "每发还回几枚：与负债侧同一趟、同一批发数，取料点按两笔大小取留底那一截");
        }

        /** 同槽多候选是互斥选择：起步量按一份计，不能被算成两枚。 */
        void mutuallyExclusiveCandidatesCountOneSeed() {
            FakePattern ring = of(Items.CARTOGRAPHY_TABLE, FakeInput.multi(1L, SEED, SEED),
                    new GenericStack(SEED, 1L), new GenericStack(PRODUCT, 1L));
            Map<IPatternDetails, Long> tasks = new HashMap<>();
            tasks.put(ring, 5L);
            var ledger = new RingSeedLedger<>(tasks, times -> PlanAmount.of(times));

            assertEquals(1L, ledger.seedAmounts().get(SEED));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.provenAcyclicUniverseSkipsRepeatedProgressAndTopologyReadsButInvalidatesOnNewPattern", () -> new RingSeedLedgerCases().provenAcyclicUniverseSkipsRepeatedProgressAndTopologyReadsButInvalidatesOnNewPattern()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.inactiveCycleMemberCannotBeExcludedFromAnAcyclicCertificate", () -> new RingSeedLedgerCases().inactiveCycleMemberCannotBeExcludedFromAnAcyclicCertificate()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.starterAmountIsPerFiringConsumption", () -> new RingSeedLedgerCases().starterAmountIsPerFiringConsumption()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.derivedSeedsPutRingProducersFirst", () -> new RingSeedLedgerCases().derivedSeedsPutRingProducersFirst()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.seedAccountDropsWithTheRingTask", () -> new RingSeedLedgerCases().seedAccountDropsWithTheRingTask()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.chainRingLinksBothHaveStarterAmounts", () -> new RingSeedLedgerCases().chainRingLinksBothHaveStarterAmounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.netConsumingKeyIsStillASeedAndHandsOutBothPerFiringLegs", () -> new RingSeedLedgerCases().netConsumingKeyIsStillASeedAndHandsOutBothPerFiringLegs()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RingSeedLedgerCases.mutuallyExclusiveCandidatesCountOneSeed", () -> new RingSeedLedgerCases().mutuallyExclusiveCandidatesCountOneSeed())
            );
        }
    }
}
