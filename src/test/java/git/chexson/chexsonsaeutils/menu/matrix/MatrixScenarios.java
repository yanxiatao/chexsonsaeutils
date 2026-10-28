package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.InventoryAction;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 矩阵搜索与同步的行为场景；由类别测试统一调用。 */
public final class MatrixScenarios {
    private MatrixScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.menu.matrix",
                () -> PatternMatrixClientViewCases.verify(temporaryRoot),
                () -> PatternMatrixInteractionClickCases.verify(temporaryRoot),
                () -> PatternMatrixSearchTokensCases.verify(temporaryRoot),
                () -> PatternMatrixServerSyncCases.verify(temporaryRoot),
                () -> PatternMatrixShiftInsertCases.verify(temporaryRoot)
        );
    }

    /** PatternMatrixClientViewCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternMatrixClientViewCases {
        private static final ItemStack PAPER = new ItemStack(Items.PAPER);
        private static final ItemStack STONE = new ItemStack(Items.STONE);

        void resizeAllocatesCapacityAndCountsExistingSlots() {
            PatternMatrixClientView view = new PatternMatrixClientView();

            view.resize(100);
            assertEquals(100, view.getTotalSlots());
            assertEquals(0, view.getNonEmptySlots());

            view.apply(List.of(entry(3, PAPER, 1, 2)));
            assertEquals(1, view.getNonEmptySlots());
            assertTrue(view.hasDisplayStack(3));
            assertEquals(1, view.statusCodeAt(3));
            assertEquals(2, view.reasonCodeAt(3));
        }

        void shrinkingResizeDropsTailingSlotsAndKeepsTheRest() {
            PatternMatrixClientView view = view(10, 2, 7);
            int changesBeforeResize = view.changeCount();

            view.resize(5);

            assertEquals(5, view.getTotalSlots());
            assertTrue(view.hasDisplayStack(2));
            assertFalse(view.hasDisplayStack(7));
            assertEquals(1, view.getNonEmptySlots());
            assertTrue(view.changeCount() > changesBeforeResize);
        }

        void identicalResyncDoesNotTouchChangeCount() {
            PatternMatrixClientView view = view(10, 4);
            int changes = view.changeCount();

            view.apply(List.of(entry(4, PAPER, 0, 0)));
            view.apply(List.of(entry(9, ItemStack.EMPTY, 0, 0)));

            assertEquals(changes, view.changeCount());
            assertEquals(1, view.getNonEmptySlots());
        }

        void contentChangeInvalidatesTheOnDemandRealStack() {
            PatternMatrixClientView view = view(10, 4);
            view.applyRealStacks(List.of(new PatternMatrixRealStack(4, PAPER.copy())));
            assertTrue(view.hasRealStack(4));

            view.apply(List.of(entry(4, STONE, 0, 0)));

            assertFalse(view.hasRealStack(4));
            assertTrue(ItemStack.isSameItemSameComponents(STONE, view.displayStackAt(4)));
        }

        void realStacksAreOnlyAcceptedForSlotsTheViewAlreadyShows() {
            PatternMatrixClientView view = view(10, 4);

            view.applyRealStacks(List.of(new PatternMatrixRealStack(5, PAPER)));

            assertFalse(view.hasRealStack(5));
            assertTrue(view.realStackAt(5).isEmpty());
        }

        void clearingASlotUpdatesTheUsedCount() {
            PatternMatrixClientView view = view(10, 2, 7);

            view.apply(List.of(entry(7, ItemStack.EMPTY, 0, 0)));

            assertEquals(1, view.getNonEmptySlots());
            assertFalse(view.hasDisplayStack(7));
        }

        void matchBitmapDrivesTheFilterFlag() {
            PatternMatrixClientView view = view(100, 3);

            assertFalse(view.filterActive());

            long[] bits = new long[PatternMatrixSearch.bitsetSize(100)];
            bits[3 >>> 6] |= 1L << (3 & 63);
            int changesBefore = view.changeCount();
            view.applyMatches(bits, 1);

            assertTrue(view.filterActive());
            assertEquals(1, view.matchCount());
            assertTrue(view.isMatched(3));
            assertTrue(view.changeCount() > changesBefore);

            view.applyMatches(bits, 1);
            assertEquals(changesBefore + 1, view.changeCount());

            view.applyMatches(new long[0], 0);
            assertFalse(view.filterActive());
        }

        void outOfRangeReadsAreEmptyRatherThanFailing() {
            PatternMatrixClientView view = view(4, 1);

            assertTrue(view.displayStackAt(99).isEmpty());
            assertTrue(view.realStackAt(-1).isEmpty());
            assertEquals(0, view.statusCodeAt(99));
            assertFalse(view.isMatched(-1));
        }

        private static PatternMatrixClientView view(int totalSlots, int... filledSlots) {
            PatternMatrixClientView view = new PatternMatrixClientView();
            view.resize(totalSlots);
            List<PatternMatrixEntry> entries = new ArrayList<>();
            for (int slot : filledSlots) {
                entries.add(entry(slot, PAPER, 0, 0));
            }
            view.apply(entries);
            return view;
        }

        private static PatternMatrixEntry entry(int slot, ItemStack displayStack, int statusCode, int reasonCode) {
            return new PatternMatrixEntry(slot, displayStack, statusCode, reasonCode);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.resizeAllocatesCapacityAndCountsExistingSlots", () -> new PatternMatrixClientViewCases().resizeAllocatesCapacityAndCountsExistingSlots()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.shrinkingResizeDropsTailingSlotsAndKeepsTheRest", () -> new PatternMatrixClientViewCases().shrinkingResizeDropsTailingSlotsAndKeepsTheRest()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.identicalResyncDoesNotTouchChangeCount", () -> new PatternMatrixClientViewCases().identicalResyncDoesNotTouchChangeCount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.contentChangeInvalidatesTheOnDemandRealStack", () -> new PatternMatrixClientViewCases().contentChangeInvalidatesTheOnDemandRealStack()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.realStacksAreOnlyAcceptedForSlotsTheViewAlreadyShows", () -> new PatternMatrixClientViewCases().realStacksAreOnlyAcceptedForSlotsTheViewAlreadyShows()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.clearingASlotUpdatesTheUsedCount", () -> new PatternMatrixClientViewCases().clearingASlotUpdatesTheUsedCount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.matchBitmapDrivesTheFilterFlag", () -> new PatternMatrixClientViewCases().matchBitmapDrivesTheFilterFlag()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixClientViewCases.outOfRangeReadsAreEmptyRatherThanFailing", () -> new PatternMatrixClientViewCases().outOfRangeReadsAreEmptyRatherThanFailing())
            );
        }
    }

    /** PatternMatrixInteractionClickCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternMatrixInteractionClickCases {
        void pickupMapsLeftToPickupOrSetDownAndRightToSplit() {
            assertEquals(InventoryAction.PICKUP_OR_SET_DOWN,
                    PatternMatrixInteraction.clickAction(ClickType.PICKUP, false, false));
            assertEquals(InventoryAction.SPLIT_OR_PLACE_SINGLE,
                    PatternMatrixInteraction.clickAction(ClickType.PICKUP, true, false));
        }

        void quickMoveMapsLeftToShiftClickAndRightToPickupSingle() {
            assertEquals(InventoryAction.SHIFT_CLICK,
                    PatternMatrixInteraction.clickAction(ClickType.QUICK_MOVE, false, false));
            assertEquals(InventoryAction.PICKUP_SINGLE,
                    PatternMatrixInteraction.clickAction(ClickType.QUICK_MOVE, true, false));
        }

        void cloneOnlyDuplicatesInCreative() {
            assertEquals(InventoryAction.CREATIVE_DUPLICATE,
                    PatternMatrixInteraction.clickAction(ClickType.CLONE, false, true));
            assertNull(PatternMatrixInteraction.clickAction(ClickType.CLONE, false, false));
        }

        void unsupportedClickTypesProduceNoAction() {
            assertNull(PatternMatrixInteraction.clickAction(ClickType.THROW, false, true));
            assertNull(PatternMatrixInteraction.clickAction(ClickType.SWAP, false, true));
            assertNull(PatternMatrixInteraction.clickAction(ClickType.PICKUP_ALL, false, true));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternMatrixInteractionClickCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixInteractionClickCases.pickupMapsLeftToPickupOrSetDownAndRightToSplit", () -> new PatternMatrixInteractionClickCases().pickupMapsLeftToPickupOrSetDownAndRightToSplit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixInteractionClickCases.quickMoveMapsLeftToShiftClickAndRightToPickupSingle", () -> new PatternMatrixInteractionClickCases().quickMoveMapsLeftToShiftClickAndRightToPickupSingle()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixInteractionClickCases.cloneOnlyDuplicatesInCreative", () -> new PatternMatrixInteractionClickCases().cloneOnlyDuplicatesInCreative()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixInteractionClickCases.unsupportedClickTypesProduceNoAction", () -> new PatternMatrixInteractionClickCases().unsupportedClickTypesProduceNoAction())
            );
        }
    }

    /** PatternMatrixSearchTokensCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternMatrixSearchTokensCases {
        void blankQueriesProduceNoTokens() {
            assertTrue(PatternMatrixSearch.tokenize(null).isEmpty());
            assertTrue(PatternMatrixSearch.tokenize("   ").isEmpty());
        }

        void tokensAreLowercasedAndSplitOnSpaces() {
            assertEquals(List.of("iron", "ingot"), PatternMatrixSearch.tokenize("  Iron  INGOT "));
        }

        void inOrderTokensMatchASubstringOfEachTargetWord() {
            List<String> query = PatternMatrixSearch.tokenize("iro ing");
            List<String> target = PatternMatrixSearch.tokenize("Iron Ingot");

            assertTrue(PatternMatrixSearch.compareTokens(query, target));
        }

        void reversedTokenOrderDoesNotMatch() {
            List<String> query = PatternMatrixSearch.tokenize("ingot iron");
            List<String> target = PatternMatrixSearch.tokenize("Iron Ingot");

            assertFalse(PatternMatrixSearch.compareTokens(query, target));
        }

        void skippingTargetWordsAheadIsAllowed() {
            List<String> query = PatternMatrixSearch.tokenize("ing");
            List<String> target = PatternMatrixSearch.tokenize("Raw Iron Ingot");

            assertTrue(PatternMatrixSearch.compareTokens(query, target));
        }

        void moreQueryWordsThanTargetWordsCannotMatch() {
            List<String> query = PatternMatrixSearch.tokenize("iron ingot block");
            List<String> target = PatternMatrixSearch.tokenize("Iron Ingot");

            assertFalse(PatternMatrixSearch.compareTokens(query, target));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternMatrixSearchTokensCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixSearchTokensCases.blankQueriesProduceNoTokens", () -> new PatternMatrixSearchTokensCases().blankQueriesProduceNoTokens()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixSearchTokensCases.tokensAreLowercasedAndSplitOnSpaces", () -> new PatternMatrixSearchTokensCases().tokensAreLowercasedAndSplitOnSpaces()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixSearchTokensCases.inOrderTokensMatchASubstringOfEachTargetWord", () -> new PatternMatrixSearchTokensCases().inOrderTokensMatchASubstringOfEachTargetWord()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixSearchTokensCases.reversedTokenOrderDoesNotMatch", () -> new PatternMatrixSearchTokensCases().reversedTokenOrderDoesNotMatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixSearchTokensCases.skippingTargetWordsAheadIsAllowed", () -> new PatternMatrixSearchTokensCases().skippingTargetWordsAheadIsAllowed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixSearchTokensCases.moreQueryWordsThanTargetWordsCannotMatch", () -> new PatternMatrixSearchTokensCases().moreQueryWordsThanTargetWordsCannotMatch())
            );
        }
    }

    /** PatternMatrixServerSyncCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternMatrixServerSyncCases {
        void firstBroadcastSendsOnlyOccupiedSlotsOnce() {
            FakeSource source = new FakeSource(10);
            source.put(1, stack());
            source.put(4, stack());
            source.put(7, stack());
            PatternMatrixServerSync sync = new PatternMatrixServerSync();

            List<PatternMatrixEntry> changes = sync.collectChanges(source, 100);

            assertEquals(Set.of(1, 4, 7), slotSet(changes));
            assertEquals(3, sync.observedSlotCount());
            assertEquals(List.of(), sync.collectChanges(source, 100));
        }

        void scanStopsAtThePerCallBudgetAndResumesWhereItStopped() {
            FakeSource source = new FakeSource(5);
            for (int slot = 0; slot < 5; slot++) {
                source.put(slot, stack());
            }
            PatternMatrixServerSync sync = new PatternMatrixServerSync();

            assertEquals(2, sync.collectChanges(source, 2).size());
            assertEquals(2, sync.collectChanges(source, 2).size());
            assertEquals(1, sync.collectChanges(source, 2).size());
            assertEquals(List.of(), sync.collectChanges(source, 2));
        }

        void clearingAnObservedSlotEmitsOneEmptyEntry() {
            FakeSource source = new FakeSource(6);
            source.put(4, stack());
            PatternMatrixServerSync sync = new PatternMatrixServerSync();
            sync.collectChanges(source, 100);

            source.put(4, ItemStack.EMPTY);
            List<PatternMatrixEntry> changes = sync.collectChanges(source, 100);

            assertEquals(1, changes.size());
            assertEquals(4, changes.get(0).slot());
            assertTrue(changes.get(0).displayStack().isEmpty());
            assertEquals(0, sync.observedSlotCount());
            assertEquals(List.of(), sync.collectChanges(source, 100));
        }

        void detailVersionBumpResendsDerivedDataWithoutStackChange() {
            FakeSource source = new FakeSource(3);
            source.put(2, stack());
            PatternMatrixServerSync sync = new PatternMatrixServerSync();
            sync.collectChanges(source, 100);

            source.detailVersion++;
            List<PatternMatrixEntry> changes = sync.collectChanges(source, 100);

            assertEquals(Set.of(2), slotSet(changes));
            assertEquals(List.of(), sync.collectChanges(source, 100));
        }

        void resetDropsTheSnapshotSoTheNextPassIsFull() {
            FakeSource source = new FakeSource(4);
            source.put(0, stack());
            source.put(3, stack());
            PatternMatrixServerSync sync = new PatternMatrixServerSync();
            sync.collectChanges(source, 100);

            sync.reset();

            assertEquals(0, sync.observedSlotCount());
            assertEquals(Set.of(0, 3), slotSet(sync.collectChanges(source, 100)));
        }

        void asynchronousStatusRefreshResendsOnlyChangedSlotWithoutReopeningMenu() {
            FakeSource source = new FakeSource(3);
            source.put(0, stack());
            source.put(1, stack());
            source.statusCodes[0] = git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportStatus.PENDING_REFRESH.ordinal();
            var sync = new PatternMatrixServerSync();
            var view = new git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView();
            view.resize(3);
            view.apply(sync.collectChanges(source, 100));
            assertEquals(source.statusCodes[0], view.statusCodeAt(0));
            source.statusCodes[0] = git.chexson.chexsonsaeutils.crafting.directprocessing.MachineSupportStatus.SUPPORTED_CONFIG.ordinal();
            var refreshed = sync.collectChanges(source, 100);
            assertEquals(Set.of(0), slotSet(refreshed));
            view.apply(refreshed);
            assertEquals(source.statusCodes[0], view.statusCodeAt(0));
            assertEquals(List.of(), sync.collectChanges(source, 100));
            source.reasonCodes[1] = 9;
            var reasonChanged = sync.collectChanges(source, 100);
            assertEquals(Set.of(1), slotSet(reasonChanged));
            view.apply(reasonChanged);
            assertEquals(9, view.reasonCodeAt(1));
        }

        void broadcastSplitsChangesIntoBoundedPackets() {
            FakeSource source = new FakeSource(400);
            for (int slot = 0; slot < 300; slot++) {
                source.put(slot, stack());
            }
            PatternMatrixServerSync sync = new PatternMatrixServerSync();
            List<List<PatternMatrixEntry>> packets = new ArrayList<>();

            sync.broadcastChanges(source, packets::add);

            int total = 0;
            for (List<PatternMatrixEntry> packet : packets) {
                assertTrue(packet.size() <= PatternMatrixServerSync.ENTRIES_PER_PACKET,
                        "单包条目数必须受限，避免拼出巨型包");
                total += packet.size();
            }
            assertEquals(300, total);
            assertEquals(3, packets.size());
        }

        void emptyStoreProducesNoTraffic() {
            FakeSource source = new FakeSource(0);
            PatternMatrixServerSync sync = new PatternMatrixServerSync();

            assertEquals(List.of(), sync.collectChanges(source, 100));
        }

        private static Set<Integer> slotSet(List<PatternMatrixEntry> entries) {
            Set<Integer> slots = new HashSet<>();
            for (PatternMatrixEntry entry : entries) {
                slots.add(entry.slot());
            }
            return slots;
        }

        private static ItemStack stack() {
            return new ItemStack(Items.PAPER);
        }

        private static final class FakeSource implements PatternMatrixSource {

            private final AppEngInternalInventory inventory;
            private final int[] statusCodes;
            private final int[] reasonCodes;
            private long detailVersion;

            private FakeSource(int totalSlots) {
                this.inventory = new AppEngInternalInventory(new FakeHost(), Math.max(1, totalSlots), 1);
                this.statusCodes = new int[inventory.size()];
                this.reasonCodes = new int[inventory.size()];
            }

            private void put(int slot, ItemStack stack) {
                inventory.setItemDirect(slot, stack);
            }

            @Override
            public InternalInventory patternMatrixInventory() {
                return inventory;
            }

            @Override
            public int patternMatrixTotalSlots() {
                return inventory.size();
            }

            @Override
            public ItemStack patternMatrixDisplayStack(int slot) {
                return inventory.getStackInSlot(slot).copy();
            }

            @Override
            public int patternMatrixStatusCode(int slot) {
                return statusCodes[slot];
            }

            @Override
            public int patternMatrixReasonCode(int slot) {
                return reasonCodes[slot];
            }

            @Override
            public long patternMatrixDetailVersion() {
                return detailVersion;
            }

            @Override
            public boolean patternMatrixMayPlace(ItemStack stack) {
                return !stack.isEmpty();
            }

            @Override
            public int patternMatrixSearchMatches(String query, long[] matchBits) {
                return 0;
            }
        }

        private static final class FakeHost implements InternalInventoryHost {

            @Override
            public void saveChangedInventory(AppEngInternalInventory inv) {
            }

            @Override
            public boolean isClientSide() {
                return false;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.firstBroadcastSendsOnlyOccupiedSlotsOnce", () -> new PatternMatrixServerSyncCases().firstBroadcastSendsOnlyOccupiedSlotsOnce()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.scanStopsAtThePerCallBudgetAndResumesWhereItStopped", () -> new PatternMatrixServerSyncCases().scanStopsAtThePerCallBudgetAndResumesWhereItStopped()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.clearingAnObservedSlotEmitsOneEmptyEntry", () -> new PatternMatrixServerSyncCases().clearingAnObservedSlotEmitsOneEmptyEntry()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.detailVersionBumpResendsDerivedDataWithoutStackChange", () -> new PatternMatrixServerSyncCases().detailVersionBumpResendsDerivedDataWithoutStackChange()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.resetDropsTheSnapshotSoTheNextPassIsFull", () -> new PatternMatrixServerSyncCases().resetDropsTheSnapshotSoTheNextPassIsFull()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.asynchronousStatusRefreshResendsOnlyChangedSlotWithoutReopeningMenu", () -> new PatternMatrixServerSyncCases().asynchronousStatusRefreshResendsOnlyChangedSlotWithoutReopeningMenu()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.broadcastSplitsChangesIntoBoundedPackets", () -> new PatternMatrixServerSyncCases().broadcastSplitsChangesIntoBoundedPackets()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixServerSyncCases.emptyStoreProducesNoTraffic", () -> new PatternMatrixServerSyncCases().emptyStoreProducesNoTraffic())
            );
        }
    }

    /** PatternMatrixShiftInsertCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternMatrixShiftInsertCases {
        void wholeStackSpreadsOnePatternPerSlotFromTheFirstFreeSlot() {
            FakeSource source = new FakeSource(10);

            int inserted = PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 3));

            assertEquals(3, inserted);
            assertEquals(3, countOccupied(source));
            assertEquals(Items.PAPER, itemAt(source, 0));
            assertEquals(Items.PAPER, itemAt(source, 2));
            assertEquals(1, source.inventory.getStackInSlot(0).getCount());
        }

        void occupiedSlotsAreSkipped() {
            FakeSource source = new FakeSource(4);
            source.put(0, stack(Items.STONE, 1));

            int inserted = PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 2));

            assertEquals(2, inserted);
            assertEquals(Items.STONE, itemAt(source, 0));
            assertEquals(Items.PAPER, itemAt(source, 1));
            assertEquals(Items.PAPER, itemAt(source, 2));
            assertTrue(source.inventory.getStackInSlot(3).isEmpty());
        }

        void rejectedStackInsertsNothing() {
            FakeSource source = new FakeSource(4);
            source.accepts = false;

            assertEquals(0, PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 2)));
            assertEquals(0, countOccupied(source));
        }

        void fullMatrixTakesNothing() {
            FakeSource source = new FakeSource(2);
            source.put(0, stack(Items.STONE, 1));
            source.put(1, stack(Items.STONE, 1));

            assertEquals(0, PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 2)));
            assertEquals(2, countOccupied(source));
        }

        private static Item itemAt(FakeSource source, int slot) {
            return source.inventory.getStackInSlot(slot).getItem();
        }

        private static int countOccupied(FakeSource source) {
            int used = 0;
            for (int slot = 0; slot < source.inventory.size(); slot++) {
                if (!source.inventory.getStackInSlot(slot).isEmpty()) {
                    used++;
                }
            }
            return used;
        }

        private static ItemStack stack(Item item, int count) {
            return new ItemStack(item, count);
        }

        private static final class FakeSource implements PatternMatrixSource {

            private final AppEngInternalInventory inventory;
            private boolean accepts = true;

            private FakeSource(int totalSlots) {
                this.inventory = new AppEngInternalInventory(new FakeHost(), totalSlots, 1);
            }

            private void put(int slot, ItemStack stack) {
                inventory.setItemDirect(slot, stack);
            }

            @Override
            public InternalInventory patternMatrixInventory() {
                return inventory;
            }

            @Override
            public int patternMatrixTotalSlots() {
                return inventory.size();
            }

            @Override
            public ItemStack patternMatrixDisplayStack(int slot) {
                return inventory.getStackInSlot(slot).copy();
            }

            @Override
            public int patternMatrixStatusCode(int slot) {
                return 0;
            }

            @Override
            public int patternMatrixReasonCode(int slot) {
                return 0;
            }

            @Override
            public long patternMatrixDetailVersion() {
                return 0L;
            }

            @Override
            public boolean patternMatrixMayPlace(ItemStack stack) {
                return accepts && !stack.isEmpty();
            }

            @Override
            public int patternMatrixSearchMatches(String query, long[] matchBits) {
                return 0;
            }
        }

        private static final class FakeHost implements InternalInventoryHost {

            @Override
            public void saveChangedInventory(AppEngInternalInventory inv) {
            }

            @Override
            public boolean isClientSide() {
                return false;
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternMatrixShiftInsertCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixShiftInsertCases.wholeStackSpreadsOnePatternPerSlotFromTheFirstFreeSlot", () -> new PatternMatrixShiftInsertCases().wholeStackSpreadsOnePatternPerSlotFromTheFirstFreeSlot()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixShiftInsertCases.occupiedSlotsAreSkipped", () -> new PatternMatrixShiftInsertCases().occupiedSlotsAreSkipped()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixShiftInsertCases.rejectedStackInsertsNothing", () -> new PatternMatrixShiftInsertCases().rejectedStackInsertsNothing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixShiftInsertCases.fullMatrixTakesNothing", () -> new PatternMatrixShiftInsertCases().fullMatrixTakesNothing())
            );
        }
    }
}
