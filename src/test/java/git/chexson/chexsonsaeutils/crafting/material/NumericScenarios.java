package git.chexson.chexsonsaeutils.crafting.material;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import git.chexson.chexsonsaeutils.cell.InfinityCellItem;
import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.material.PlanGrossAudit;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 精确数量与材料账本的行为场景；由类别测试统一调用。 */
public final class NumericScenarios {
    private NumericScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.material",
                () -> PlanningSupplyTruthCases.verify(temporaryRoot),
                () -> SlotAccountCases.verify(temporaryRoot)
        );
    }

    /** PlanningSupplyTruthCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanningSupplyTruthCases {
        private static final AEKey COPPER = AEItemKey.of(Items.COPPER_INGOT);
        private static final AEKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
        private static final BigInteger OVERFLOWED = LONG_MAX.add(BigInteger.valueOf(5));

        void extendedAeFluidSupplyIsDetectedInNativeFluidUnits() {
            var lava = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.LAVA);
            var source = new FakeCreativeCell(lava, (long) Integer.MAX_VALUE * lava.getAmountPerUnit());
            var grid = FakePlanGrid.FakeGrid.builder().build();
            grid.node.owner = new FakeDrive(List.of(source), true);
            var truth = PlanningSupplyTruth.capture(grid, source.getAvailableStacks());
            assertTrue(truth.unboundedSupplies().contains(lava));
            assertEquals(Set.of(lava), PlanningSupplyTruth.currentUnboundedSupplies(grid));
            assertEquals(PlanningSupplyTruth.UNBOUNDED, truth.supplyOf(lava, 1));
        }

        void undeclaredFiniteStorageIsNotInfiniteEvenWhenExtractionExceedsItsProjection() {
            var storage = new MEStorage() {
                @Override public void getAvailableStacks(KeyCounter out) { out.add(IRON, Integer.MAX_VALUE); }
                @Override public long extract(AEKey key, long amount, Actionable mode,
                        appeng.api.networking.security.IActionSource source) { return amount; }
                @Override public Component getDescription() { return Component.literal("有限盘的截断读数"); }
            };
            assertTrue(PlanningSupplyTruth.unboundedKeysOf(storage).isEmpty());
        }

        /** 无限盘溢出键：网络报 Long.MAX_VALUE，规划侧要读到真值。 */
        void overflowKeyReadsBackAsBigIntegerTruthOnPlanningSide() {
            var cell = newCell(COPPER, 5L);
            var networkStock = stockOf(COPPER, Long.MAX_VALUE);

            var truth = PlanningSupplyTruth.capture(gridWithCells(cell), networkStock);

            assertFalse(truth.isEmpty());
            assertEquals(1, truth.wideKeyCount());
            assertEquals(1, truth.wideSourceCount());
            assertEquals(PlanAmount.of(OVERFLOWED), truth.supplyOf(COPPER, networkStock.get(COPPER)));
            assertEquals(OVERFLOWED, truth.wideSupply(COPPER).exactValue());
        }

        /** 同一张盘上的未溢出键、以及别的 mod 报满 long 的键：一律逐字退回网络读数。 */
        void nonOverflowReadingsStayByteForByte() {
            var cell = newCell(COPPER, 5L);
            // IRON：同盘的普通存储（未溢出）；GOLD：别的 mod 的 cell 报满 long，我们一分没有
            cell.data().insert(IRON, 500L, Actionable.MODULATE);
            var networkStock = new KeyCounter();
            networkStock.add(COPPER, Long.MAX_VALUE);
            networkStock.add(IRON, 500L);
            networkStock.add(GOLD, Long.MAX_VALUE);

            var truth = PlanningSupplyTruth.capture(gridWithCells(cell), networkStock);

            assertEquals(PlanAmount.of(OVERFLOWED), truth.supplyOf(COPPER, networkStock.get(COPPER)));
            assertEquals(PlanAmount.of(500L), truth.supplyOf(IRON, networkStock.get(IRON)));
            assertEquals(PlanAmount.of(Long.MAX_VALUE), truth.supplyOf(GOLD, networkStock.get(GOLD)));
            assertNull(truth.wideSupply(IRON));
            assertNull(truth.wideSupply(GOLD));
            assertEquals(1, truth.wideKeyCount());
        }

        /** 盘没溢出（读数恰等于真值）时不产宽域键：常态路径与今日口径一致。 */
        void saturatedButNotOverflowingCellYieldsNoWideKeys() {
            var cell = newCell(COPPER, 0L);
            var networkStock = stockOf(COPPER, Long.MAX_VALUE);

            var truth = PlanningSupplyTruth.capture(gridWithCells(cell), networkStock);

            assertTrue(truth.isEmpty());
            assertEquals(1, truth.wideSourceCount());
            assertEquals(PlanAmount.of(Long.MAX_VALUE), truth.supplyOf(COPPER, networkStock.get(COPPER)));
        }

        /** 两张溢出盘同键：真值按 BigInteger 相加；网络那份回绕的读数只当下界，不多报。 */
        void twoOverflowingCellsSumExactly() {
            var networkStock = stockOf(COPPER, Long.MAX_VALUE);

            var truth = PlanningSupplyTruth.capture(
                    gridWithCells(newCell(COPPER, 5L), newCell(COPPER, 7L)), networkStock);

            // 两盘各报 Long.MAX_VALUE，网络聚合早已回绕；真值 = 2×Long.MAX + 12
            assertEquals(PlanAmount.of(LONG_MAX.shiftLeft(1).add(BigInteger.valueOf(12))),
                    truth.supplyOf(COPPER, networkStock.get(COPPER)));
        }

        /** 离线宿主的库存本来就不在网络里：计入真值就是虚增供给，必须跳过。 */
        void offlineHostContributesNothing() {
            var grid = FakePlanGrid.FakeGrid.builder().build();
            grid.node.owner = new FakeDrive(List.of(newCell(COPPER, 5L).inventory()), false);

            var truth = PlanningSupplyTruth.capture(grid, stockOf(COPPER, Long.MAX_VALUE));

            assertTrue(truth.isEmpty());
            assertEquals(PlanAmount.of(Long.MAX_VALUE), truth.supplyOf(COPPER, Long.MAX_VALUE));
        }

        /** 没有我们盘的网格（以及无网格）：读数逐字不变，等价于今日口径。 */
        void gridWithoutOurCellsKeepsLongReadings() {
            var networkStock = stockOf(COPPER, Long.MAX_VALUE);

            assertSame(PlanningSupplyTruth.EMPTY, PlanningSupplyTruth.capture(
                    FakePlanGrid.FakeGrid.builder().build(), networkStock));
            assertSame(PlanningSupplyTruth.EMPTY, PlanningSupplyTruth.capture(null, networkStock));
            assertEquals(PlanAmount.of(Long.MAX_VALUE),
                    PlanningSupplyTruth.EMPTY.supplyOf(COPPER, networkStock.get(COPPER)));
        }

        /**
         * 网络里没有可疑读数（无哨兵、无回绕迹象）：犯不着做归属，直接空快照。
         *
         * <p>夹具口径（台账 {@code 197} 随准入放宽而改，其余断言逐字不动）：旧形是「无限盘装着
         * {@code Long.MAX_VALUE+5}、聚合读数写 10」——宿主真读数比聚合大是一枚物理上不成立的快照，
         * 在新准入（逐存储和 &gt; 聚合＝回绕证据）下它恰恰是<b>该</b>换尺的形状。换成一枚老实报 10
         * 的普通单元：聚合与逐存储一致，读数可信，空快照的短路判据照旧。
         */
        void noSaturatedKeySkipsGridWalk() {
            var networkStock = stockOf(COPPER, 10L);
            var grid = FakePlanGrid.FakeGrid.builder().build();
            grid.node.owner = new FakeDrive(List.<StorageCell>of(new FakeForeignCell(COPPER, 10L, 1)), true);

            assertSame(PlanningSupplyTruth.EMPTY,
                    PlanningSupplyTruth.capture(grid, networkStock));
        }

        /**
         * 别人的存储凑出了 &gt; long：三张普通单元各存 3.1e18，AE2 的逐键 {@code long} 读数饱和成
         * {@code Long.MAX_VALUE}。那不是我们的盘，规划侧无从知道真值，也绝不许替它编一个 ——
         * 读数原样退回 {@code Long.MAX_VALUE}。
         */
        void foreignSaturatedStorageKeepsItsOwnLongReading() {
            var ours = newCell(COPPER, 5L);
            var foreign = new FakeForeignCell(GOLD, 3_100_000_000_000_000_000L, 3);
            var grid = FakePlanGrid.FakeGrid.builder().build();
            grid.node.owner = new FakeDrive(List.<StorageCell>of(ours.inventory(), foreign), true);
            var networkStock = new KeyCounter();
            networkStock.add(COPPER, Long.MAX_VALUE);
            foreign.getAvailableStacks(networkStock);

            var truth = PlanningSupplyTruth.capture(grid, networkStock);

            assertEquals(Long.MAX_VALUE, networkStock.get(GOLD));
            assertEquals(PlanAmount.of(OVERFLOWED), truth.supplyOf(COPPER, networkStock.get(COPPER)));
            assertEquals(PlanAmount.of(Long.MAX_VALUE), truth.supplyOf(GOLD, networkStock.get(GOLD)));
            assertNull(truth.wideSupply(GOLD));
            assertEquals(1, truth.wideKeyCount());
            // 别人的单元不是宽域供给源：扫描到的供给源只有我们自己那一张
            assertEquals(1, truth.wideSourceCount());
        }

        /** 真值随编译图出门：判据读图即可，库存新鲜化（编译缓存命中）也不把它冲掉。 */
        void truthTravelsWithCompiledGraphAcrossStockRefresh() {
            var truth = PlanningSupplyTruth.capture(gridWithCells(newCell(COPPER, 5L)),
                    stockOf(COPPER, Long.MAX_VALUE));
            var graph = graphWithStock(Map.of(COPPER, Long.MAX_VALUE));

            graph.attachSupplyTruth(truth);
            var refreshed = graph.withStock(Map.of(COPPER, 9L, IRON, 1L));

            assertSame(truth, refreshed.supplyTruth());
            assertEquals(PlanAmount.of(OVERFLOWED), refreshed.supplyTruth()
                    .supplyOf(COPPER, refreshed.stock().get(COPPER)));
            // 未收录的键逐字退回调用方给的 long 读数
            assertEquals(PlanAmount.of(1L), refreshed.supplyTruth().supplyOf(IRON, 1L));
            assertEquals(PlanningSupplyTruth.EMPTY, graphWithStock(Map.of()).supplyTruth());
        }

        /**
         * 覆盖面自报要有牙（台账 {@code 200}）：这一趟扫描真伸到几个宿主、几张单元、逐存储那本账里
         * 登记了几枚键，读数就必须是几个——数错一层（宿主那一层加两回、单元那一层按宿主加）当场红。
         *
         * <p>{@code of(...)} 那条路没有网格可扫：三个读数恒交 0 并被钉住，钉的是「非网格来源不许
         * 假装覆盖过」。这三个读数只报覆盖面，不参与任何判据：同一份快照里换尺结果逐字不变
         * （看不见的那一枚仍按网络读数走，不替它编真值）。
         */
        void coverageReadoutsReportWhatTheScanActuallyReached() {
            var cell = newCell(COPPER, 5L);
            cell.data().insert(IRON, 500L, Actionable.MODULATE);
            var networkStock = new KeyCounter();
            networkStock.add(COPPER, Long.MAX_VALUE);
            networkStock.add(IRON, 500L);
            // GOLD：网络报满哨兵、逐存储那本账里一个字都没有——这一枚是「扫描看不见」
            networkStock.add(GOLD, Long.MAX_VALUE);

            var truth = PlanningSupplyTruth.capture(
                    gridWithCells(cell, newCell(COPPER, 7L)), networkStock);

            assertEquals(1, truth.scannedHostCount(), "宿主形态数按宿主那一层加一次，不是按槽位翻倍");
            assertEquals(2, truth.scannedCellCount(), "单元格数＝真伸到的那两张单元");
            assertEquals(2, truth.tallyKeyCount(), "逐存储那本账登记过的键数＝COPPER+IRON，GOLD 不在里头");
            assertEquals(PlanAmount.of(Long.MAX_VALUE), truth.supplyOf(GOLD, Long.MAX_VALUE),
                    "覆盖面读数不许动判据：看不见的那一枚照旧退回网络读数");

            var offline = PlanningSupplyTruth.of(Map.of(COPPER, PlanAmount.of(OVERFLOWED)));

            assertEquals(0, offline.scannedHostCount(), "非网格来源没有宿主可扫：不许假装覆盖过");
            assertEquals(0, offline.scannedCellCount(), "非网格来源没有单元格可扫：不许假装覆盖过");
            assertEquals(0, offline.tallyKeyCount(), "非网格来源没翻过逐存储那本账：不许假装覆盖过");
            assertEquals(PlanAmount.of(OVERFLOWED), offline.supplyOf(COPPER, Long.MAX_VALUE),
                    "恒 0 只关覆盖面，表里交的真值一个字不少");
        }

        // ---- fixtures ----

        private static CraftGraph graphWithStock(Map<AEKey, Long> stock) {
            return new CraftGraph(COPPER, Map.of(), Map.of(), List.of(), Map.of(),
                    Map.of(), Map.of(), stock, Set.of(), Map.of(), false, null, 0, 0);
        }

        /** 造一张真值 = Long.MAX_VALUE + extra 的无限盘（直接写单元账，绕开单元过滤与存档）。 */
        private static FakeCell newCell(AEKey key, long extra) {
            var cellId = UUID.randomUUID();
            var data = InfinityCellStore.global().cell(cellId);
            data.insert(key, Long.MAX_VALUE, Actionable.MODULATE);
            if (extra > 0L) {
                data.insert(key, extra, Actionable.MODULATE);
            }
            var stack = new ItemStack(Items.STICK);
            var tag = new CompoundTag();
            tag.putUUID(InfinityCellItem.CELL_UUID, cellId);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return new FakeCell(new InfinityCellInventory(stack, null), data);
        }

        /** 造一枚只装 {@code amount}（装得进 {@code long}）的无限盘：报量＝真值，未溢出键的夹具形态。 */
        private static FakeCell newPlainCell(AEKey key, long amount) {
            var cellId = UUID.randomUUID();
            var data = InfinityCellStore.global().cell(cellId);
            data.insert(key, amount, Actionable.MODULATE);
            var stack = new ItemStack(Items.STICK);
            var tag = new CompoundTag();
            tag.putUUID(InfinityCellItem.CELL_UUID, cellId);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return new FakeCell(new InfinityCellInventory(stack, null), data);
        }

        private static KeyCounter stockOf(AEKey key, long amount) {
            var stock = new KeyCounter();
            stock.add(key, amount);
            return stock;
        }

        private static FakePlanGrid.FakeGrid gridWithCells(FakeCell... cells) {
            var grid = FakePlanGrid.FakeGrid.builder().build();
            var inventories = new java.util.ArrayList<StorageCell>(cells.length);
            for (var cell : cells) {
                inventories.add(cell.inventory());
            }
            grid.node.owner = new FakeDrive(inventories, true);
            return grid;
        }

        private record FakeCell(InfinityCellInventory inventory, InfinityCellStore.CellData data) {
        }

        /** 驱动器/存储舱宿主：只交代「槽里挂的是哪份单元」，其余按接口签名占位。 */
        private static final class FakeDrive implements IChestOrDrive {
            private final List<StorageCell> cells;
            private final boolean powered;

            private FakeDrive(List<StorageCell> cells, boolean powered) {
                this.cells = cells;
                this.powered = powered;
            }

            @Override
            public int getCellCount() {
                return this.cells.size();
            }

            @Override
            public CellState getCellStatus(int slot) {
                return CellState.NOT_EMPTY;
            }

            @Override
            public boolean isPowered() {
                return this.powered;
            }

            @Override
            public boolean isCellBlinking(int slot) {
                return false;
            }

            @Override
            public @Nullable Item getCellItem(int slot) {
                return Items.AIR;
            }

            @Override
            public @Nullable MEStorage getCellInventory(int slot) {
                return this.cells.get(slot);
            }

            @Override
            public @Nullable StorageCell getOriginalCellInventory(int slot) {
                return this.cells.get(slot);
            }

            @Override
            public @Nullable IGridNode getActionableNode() {
                return null;
            }
        }

        /**
         * AE2 自己的创造 ME 磁盘：{@code getAvailableStacks} 只报 {@code Integer.MAX_VALUE} 枚，
         * {@code extract} 却对配置过的键"要多少给多少"（见
         * {@code appeng/me/cells/CreativeCellInventory.java}）。它报的量小得正常，
         * 只有行为能认它——而这条链不通时，需求 &gt; long 的单子永远被判"材料不足"不许提交：
         * 一次 ≤long 的 SIMULATE 探询最多拿回 {@code Long.MAX_VALUE}，永远抵不过 4.5e20 的需求。
         *
         * <p>夹具口径（台账 {@code 197}，随准入放宽而改，三条断言逐字不动）：普通邻键旧形是
         * 「盘里装着 {@code Long.MAX_VALUE+1000}、聚合读数写 1000」——宿主真读数比聚合大是一枚
         * 物理上不成立的快照，在新准入（逐存储和 &gt; 聚合＝回绕证据）下它恰恰是<b>该</b>换尺的
         * 形状，会把本用例顶成读数之争。换成一枚老实装着 1000 枚的盘：聚合与逐存储一致，
         * 本用例量的还是行为判别本身。
         */
        void creativeCellIsDetectedByGivingMoreThanItReports() {
            var creative = new FakeCreativeCell(IRON);
            var ordinary = newPlainCell(COPPER, 1_000L);
            var grid = FakePlanGrid.FakeGrid.builder().build();
            grid.node.owner = new FakeDrive(List.<StorageCell>of(creative, ordinary.inventory()), true);
            var networkStock = new KeyCounter();
            networkStock.add(IRON, Integer.MAX_VALUE);
            networkStock.add(COPPER, 1_000L);

            var truth = PlanningSupplyTruth.capture(grid, networkStock);

            assertEquals(1, truth.unboundedKeyCount(), "只有「取出＞报出」的那张单元算无限源");
            assertEquals(PlanningSupplyTruth.UNBOUNDED, truth.supplyOf(IRON, networkStock.get(IRON)),
                    "无限源必须给到能盖住 >long 的需求；给 Long.MAX 就等于还是判缺料");
            assertEquals(PlanAmount.of(1_000L), truth.supplyOf(COPPER, networkStock.get(COPPER)),
                    "普通单元一次都不该被探询成无限");
        }

        /** 创造形单元：报 Integer.MAX_VALUE，取多少给多少（AE2 CreativeCellInventory 的形状）。 */
        private static final class FakeCreativeCell implements StorageCell,
                git.chexson.chexsonsaeutils.api.storage.UnboundedStorageSupply {
            private final AEKey key;
            private final long reported;

            private FakeCreativeCell(AEKey key) {
                this(key, Integer.MAX_VALUE);
            }

            private FakeCreativeCell(AEKey key, long reported) {
                this.key = key;
                this.reported = reported;
            }

            @Override public Set<AEKey> unboundedSupplyKeys() { return Set.of(key); }

            @Override
            public void getAvailableStacks(KeyCounter out) {
                out.add(this.key, reported);
            }

            @Override
            public long extract(AEKey what, long requested, Actionable mode,
                    appeng.api.networking.security.IActionSource source) {
                return this.key.equals(what) ? requested : 0L;
            }

            @Override
            public Component getDescription() {
                return Component.literal("creative");
            }

            @Override
            public CellState getStatus() {
                return CellState.TYPES_FULL;
            }

            @Override
            public double getIdleDrain() {
                return 0;
            }

            @Override
            public void persist() {
            }
        }

        /**
         * 别的 mod 的单元：内部靠反复累加存下 &gt; long 的量，但对 AE2 只能自饱和报一个
         * {@code long}。它不是 {@link git.chexson.chexsonsaeutils.cell.WideSupplySource}，
         * 规划侧无从知道它的真值，也不许替它编。
         */
        private static final class FakeForeignCell implements StorageCell {
            private final AEKey key;
            private final long reported;

            private FakeForeignCell(AEKey key, long perMount, int mounts) {
                this.key = key;
                var total = BigInteger.valueOf(perMount).multiply(BigInteger.valueOf(mounts));
                this.reported = total.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : total.longValue();
            }

            @Override
            public void getAvailableStacks(KeyCounter out) {
                out.add(this.key, this.reported);
            }

            @Override
            public long extract(AEKey what, long requested, Actionable mode,
                    appeng.api.networking.security.IActionSource source) {
                return this.key.equals(what) ? Math.min(requested, this.reported) : 0L;
            }

            @Override
            public Component getDescription() {
                return Component.literal("foreign");
            }

            @Override
            public CellState getStatus() {
                return CellState.NOT_EMPTY;
            }

            @Override
            public double getIdleDrain() {
                return 0;
            }

            @Override
            public void persist() {
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.extendedAeFluidSupplyIsDetectedInNativeFluidUnits", () -> new PlanningSupplyTruthCases().extendedAeFluidSupplyIsDetectedInNativeFluidUnits()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.undeclaredFiniteStorageIsNotInfiniteEvenWhenExtractionExceedsItsProjection", () -> new PlanningSupplyTruthCases().undeclaredFiniteStorageIsNotInfiniteEvenWhenExtractionExceedsItsProjection()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.overflowKeyReadsBackAsBigIntegerTruthOnPlanningSide", () -> new PlanningSupplyTruthCases().overflowKeyReadsBackAsBigIntegerTruthOnPlanningSide()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.nonOverflowReadingsStayByteForByte", () -> new PlanningSupplyTruthCases().nonOverflowReadingsStayByteForByte()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.saturatedButNotOverflowingCellYieldsNoWideKeys", () -> new PlanningSupplyTruthCases().saturatedButNotOverflowingCellYieldsNoWideKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.twoOverflowingCellsSumExactly", () -> new PlanningSupplyTruthCases().twoOverflowingCellsSumExactly()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.offlineHostContributesNothing", () -> new PlanningSupplyTruthCases().offlineHostContributesNothing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.gridWithoutOurCellsKeepsLongReadings", () -> new PlanningSupplyTruthCases().gridWithoutOurCellsKeepsLongReadings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.noSaturatedKeySkipsGridWalk", () -> new PlanningSupplyTruthCases().noSaturatedKeySkipsGridWalk()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.foreignSaturatedStorageKeepsItsOwnLongReading", () -> new PlanningSupplyTruthCases().foreignSaturatedStorageKeepsItsOwnLongReading()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.truthTravelsWithCompiledGraphAcrossStockRefresh", () -> new PlanningSupplyTruthCases().truthTravelsWithCompiledGraphAcrossStockRefresh()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.coverageReadoutsReportWhatTheScanActuallyReached", () -> new PlanningSupplyTruthCases().coverageReadoutsReportWhatTheScanActuallyReached()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningSupplyTruthCases.creativeCellIsDetectedByGivingMoreThanItReports", () -> new PlanningSupplyTruthCases().creativeCellIsDetectedByGivingMoreThanItReports())
            );
        }
    }

    /** SlotAccountCases 的独立场景夹具，按类别统一执行。 */
    public static final class SlotAccountCases {
        private static final AEKey GLASS = AEItemKey.of(Items.GLASS);
        private static final AEKey SAND = AEItemKey.of(Items.SAND);
        private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);

        /** 毛账探针：原样退回的模具槽按一发收费，不按发数放大。 */
        void grossAuditChargesOneFiringForSelfRepayingSlot() {
            assertEquals(3L, demandOf(FakeInput.multiWithRemainders(
                    3L, Map.of(GLASS, GLASS), GLASS, SAND)));
        }

        /** 普通消耗槽照旧按 倍率 × 发数 收费。 */
        void grossAuditChargesEveryFiringForConsumedSlot() {
            assertEquals(300L, demandOf(FakeInput.multi(3L, GLASS, SAND)));
        }

        /** 有退路＝除被排除的那个键外还有任一候选真有量；只有它自己有料时不算退路。 */
        void alternativeLooksOnlyAtTheOtherCandidates() {
            var slot = FakeInput.multi(1L, GLASS, SAND);
            assertTrue(SlotAccount.hasAlternative(slot, GLASS,
                    key -> key.equals(SAND) ? 7L : 0L));
            assertFalse(SlotAccount.hasAlternative(slot, GLASS,
                    key -> key.equals(GLASS) ? 7L : 0L));
        }

        /** 顺位排水：先榨干首位，剩下的才退下一个；缺口记在首位键上。 */
        void drainSpillsInCandidateOrderAndBlamesTheFirstCandidate() {
            var pool = new java.util.HashMap<AEKey, Long>();
            pool.put(GLASS, 6L);
            pool.put(SAND, 1L);
            var drained = SlotAccount.drainSlot(
                    pool, FakeInput.multi(1L, GLASS, SAND), 10L);
            assertEquals(3L, drained.unfundedFirings());
            assertEquals(GLASS, drained.reported().what());
            assertEquals(0L, pool.get(GLASS).longValue());
            assertEquals(0L, pool.get(SAND).longValue());
        }

        /** 两槽共用一份池子：前一槽许诺过的量不能再给后一槽。 */
        void drainSharesOnePoolInsteadOfPromisingItTwice() {
            var pool = new java.util.HashMap<AEKey, Long>();
            pool.put(GLASS, 6L);
            var slot = FakeInput.multi(1L, GLASS);
            assertEquals(0L, SlotAccount.drainSlot(pool, slot, 5L).unfundedFirings());
            assertEquals(4L, SlotAccount.drainSlot(pool, slot, 5L).unfundedFirings());
        }

        /** 模具槽整批只从池子里取那份起步量，不按发数放大。 */
        void drainChargesSelfRepayingSlotOneFiring() {
            var pool = new java.util.HashMap<AEKey, Long>();
            pool.put(GLASS, 2L);
            var slot = FakeInput.multiWithRemainders(
                    2L, Map.of(GLASS, GLASS), GLASS, SAND);
            assertEquals(0L, SlotAccount.drainSlot(pool, slot, 100L).unfundedFirings());
            assertEquals(0L, pool.get(GLASS).longValue());
        }

        /** 退料吃到的键不能从账上隐身：首位候选被抽干后，剩下的需求要记到第二候选头上。 */
        void grossAuditBillsTheCandidateActuallySpilledTo() {
            var glassMaker = FakePattern.of(GLASS, 5L);
            var sandMaker = FakePattern.of(SAND, 1L);
            var consumer = FakePattern.of(PRODUCT, 1L, FakeInput.multi(1L, GLASS, SAND));
            var balances = PlanGrossAudit.measure(Map.<IPatternDetails, Long>of(
                    glassMaker, 100L, sandMaker, 100L, consumer, 600L), null);
            // 旧口径把 600 全记在首位候选上，退料吃到的键在账上是零需求、零缺口。
            assertEquals(500L, balances.byKey().get(GLASS)[0].longValue());
            assertEquals(100L, balances.byKey().get(SAND)[0].longValue());
        }

        /** 出口报缺料吃的是带量的缺口表；整族排空时 spare 全零，才是真缺而不是顺序吃空。 */
        void grossAuditHolesCarryAmountsAndSayTheFamilyIsDrained() {
            var glassMaker = FakePattern.of(GLASS, 5L);
            var sandMaker = FakePattern.of(SAND, 1L);
            var consumer = FakePattern.of(PRODUCT, 1L, FakeInput.multi(1L, GLASS, SAND));
            // 供给 100 玻璃 + 20 沙子，需求 130 发：首位排干再退第二候选，仍差 10。
            var balances = PlanGrossAudit.measure(Map.<IPatternDetails, Long>of(
                    glassMaker, 20L, sandMaker, 20L, consumer, 130L), null);
            assertEquals(java.util.List.of(GLASS), PlanGrossAudit.holeKeys(balances));
            assertEquals(10L, PlanGrossAudit.holes(balances).get(0).getValue().longValue());
            assertTrue(PlanGrossAudit.describe(balances, null, null, null).contains("spare=[all-empty]"),
                    PlanGrossAudit.describe(balances, null, null, null));
        }

        private static long demandOf(FakeInput input) {
            var pattern = FakePattern.of(PRODUCT, 1, input);
            var balances = PlanGrossAudit.measure(
                    Map.<IPatternDetails, Long>of(pattern, 100L), null);
            return balances.byKey().get(GLASS)[0].longValue();
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.grossAuditChargesOneFiringForSelfRepayingSlot", () -> new SlotAccountCases().grossAuditChargesOneFiringForSelfRepayingSlot()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.grossAuditChargesEveryFiringForConsumedSlot", () -> new SlotAccountCases().grossAuditChargesEveryFiringForConsumedSlot()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.alternativeLooksOnlyAtTheOtherCandidates", () -> new SlotAccountCases().alternativeLooksOnlyAtTheOtherCandidates()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.drainSpillsInCandidateOrderAndBlamesTheFirstCandidate", () -> new SlotAccountCases().drainSpillsInCandidateOrderAndBlamesTheFirstCandidate()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.drainSharesOnePoolInsteadOfPromisingItTwice", () -> new SlotAccountCases().drainSharesOnePoolInsteadOfPromisingItTwice()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.drainChargesSelfRepayingSlotOneFiring", () -> new SlotAccountCases().drainChargesSelfRepayingSlotOneFiring()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.grossAuditBillsTheCandidateActuallySpilledTo", () -> new SlotAccountCases().grossAuditBillsTheCandidateActuallySpilledTo()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SlotAccountCases.grossAuditHolesCarryAmountsAndSayTheFamilyIsDrained", () -> new SlotAccountCases().grossAuditHolesCarryAmountsAndSayTheFamilyIsDrained())
            );
        }
    }
}
