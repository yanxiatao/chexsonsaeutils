package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.client.integration.jech.JechPatternSearchMatcherImpl;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 矩阵搜索与同步的行为场景；由类别测试统一调用。 */
public final class MatrixScenarios {
    private MatrixScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.client.gui.matrix",
                () -> PatternClientSearchRefactorCases.verify(temporaryRoot),
                () -> PatternInfoCases.verify(temporaryRoot),
                () -> PatternRowCases.verify(temporaryRoot),
                () -> SharedMatrixSearchCases.verify(temporaryRoot)
        );
    }

    /** PatternClientSearchRefactorCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternClientSearchRefactorCases {
        private static final PatternMatrixSearchTerms TERMS = new PatternMatrixSearchTerms(
                List.of(Component.literal("铁矿石")), List.of(Component.literal("铁锭"), Component.literal("石头")));

        void inputOutputModesOnlyMatchSelectedSideIncludingByproducts() {
            PatternSearchMatcher matcher = String::contains;
            assertTrue(PatternClientSearch.matches(TERMS, List.of("矿石"), PatternSearchMode.INPUT, matcher));
            assertFalse(PatternClientSearch.matches(TERMS, List.of("矿石"), PatternSearchMode.OUTPUT, matcher));
            assertTrue(PatternClientSearch.matches(TERMS, List.of("石头"), PatternSearchMode.OUTPUT, matcher));
            assertFalse(PatternClientSearch.matches(TERMS, List.of("石头"), PatternSearchMode.INPUT, matcher));
            assertTrue(PatternClientSearch.matches(TERMS, List.of("矿石"), PatternSearchMode.INPUT_OUTPUT, matcher));
            assertTrue(PatternClientSearch.matches(TERMS, List.of("石头"), PatternSearchMode.INPUT_OUTPUT, matcher));
        }

        void jechMatchesFullPinyinInitialsAndMixedChineseWithCorrectDirection() {
            var matcher = new JechPatternSearchMatcherImpl();
            assertTrue(PatternClientSearch.matches(TERMS, List.of("tiekuangshi"), PatternSearchMode.INPUT, matcher));
            assertTrue(PatternClientSearch.matches(TERMS, List.of("tks"), PatternSearchMode.INPUT, matcher));
            assertTrue(PatternClientSearch.matches(TERMS, List.of("tie矿石"), PatternSearchMode.INPUT, matcher));
            assertFalse(PatternClientSearch.matches(TERMS, List.of("tiekuangshi"), PatternSearchMode.OUTPUT, matcher));
            assertFalse(PatternClientSearch.matches(TERMS, List.of("jin"), PatternSearchMode.INPUT_OUTPUT, matcher));
        }

        void localizedSearchTermsRoundTripAndChangingOnlyTermsRefreshesView() {
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var terms = new PatternMatrixSearchTerms(List.of(Component.translatable("item.minecraft.iron_ingot")), TERMS.outputs());
            var entry = new PatternMatrixEntry(1, new ItemStack(Items.IRON_INGOT), 1, 2, terms);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
            try {
                PatternMatrixEntry.STREAM_CODEC.encode(buffer, entry);
                var restored = PatternMatrixEntry.STREAM_CODEC.decode(buffer);
                assertEquals(terms, restored.searchTerms());
            } finally { buffer.release(); }
            var view = new PatternMatrixClientView();
            view.resize(3);
            view.apply(List.of(entry));
            int version = view.contentChangeCount();
            view.applyMatches(new long[]{2}, 1);
            assertEquals(version, view.contentChangeCount());
            view.apply(List.of(new PatternMatrixEntry(1, entry.displayStack(), 1, 2, TERMS)));
            assertEquals(TERMS, view.searchTermsAt(1));
            assertTrue(view.contentChangeCount() > version);
            view.apply(List.of(new PatternMatrixEntry(1, ItemStack.EMPTY, 0, 0)));
            assertEquals(PatternMatrixSearchTerms.EMPTY, view.searchTermsAt(1));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternClientSearchRefactorCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternClientSearchRefactorCases.inputOutputModesOnlyMatchSelectedSideIncludingByproducts", () -> new PatternClientSearchRefactorCases().inputOutputModesOnlyMatchSelectedSideIncludingByproducts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternClientSearchRefactorCases.jechMatchesFullPinyinInitialsAndMixedChineseWithCorrectDirection", () -> new PatternClientSearchRefactorCases().jechMatchesFullPinyinInitialsAndMixedChineseWithCorrectDirection()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternClientSearchRefactorCases.localizedSearchTermsRoundTripAndChangingOnlyTermsRefreshesView", () -> new PatternClientSearchRefactorCases().localizedSearchTermsRoundTripAndChangingOnlyTermsRefreshesView())
            );
        }
    }

    /** PatternInfoCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternInfoCases {
        private static final int COLUMNS = 9;
        private static final ItemStack PAPER = new ItemStack(Items.PAPER);

        void slicesWholeInventoryIntoFixedRows() {
            PatternInfo info = new PatternInfo(30, COLUMNS);

            assertEquals(4, info.rowCount());
            assertEquals(0, info.internalRows().get(0).offset());
            assertEquals(9, info.internalRows().get(1).offset());
            assertEquals(3, info.internalRows().get(3).slots());
        }

        void listingStopsAtTheLastContentRowPlusOneFreeRow() {
            PatternInfo info = new PatternInfo(45, COLUMNS);

            List<PatternRow> listed = info.listRows(view(45, List.of(20), null));

            assertEquals(List.of(0, 9, 18, 27), listed.stream().map(PatternRow::offset).toList());
        }

        void emptyMachineStillListsOneFreeRowForPlacing() {
            PatternInfo info = new PatternInfo(45, COLUMNS);

            assertEquals(1, info.listRows(view(45, List.of(), null)).size());
        }

        void fullMachineAppendsNoFreeRow() {
            PatternInfo info = new PatternInfo(45, COLUMNS);

            assertEquals(5, info.listRows(view(45, List.of(44), null)).size());
        }

        void filteringListsOnlyRowsHoldingAMatch() {
            PatternInfo info = new PatternInfo(45, COLUMNS);

            List<PatternRow> listed = info.listRows(view(45, List.of(20), List.of(30)));

            assertEquals(1, listed.size());
            assertSame(info.internalRows().get(3), listed.get(0));
        }

        void emptyOrDegenerateCapacityProducesNoRows() {
            assertTrue(new PatternInfo(0, COLUMNS).listRows(view(0, List.of(), null)).isEmpty());
            assertTrue(new PatternInfo(-5, COLUMNS).internalRows().isEmpty());
            assertTrue(new PatternInfo(30, 0).internalRows().isEmpty());
        }

        /** 命中位图要生效必须先有过滤标记，因此有命中时把命中槽同时填上内容。 */
        private static PatternMatrixClientView view(int totalSlots, List<Integer> filledSlots,
                                                    List<Integer> matchedSlots) {
            PatternMatrixClientView view = new PatternMatrixClientView();
            view.resize(totalSlots);
            List<PatternMatrixEntry> entries = filledSlots.stream()
                    .map(slot -> new PatternMatrixEntry(slot, PAPER, 0, 0))
                    .toList();
            if (!entries.isEmpty()) {
                view.apply(entries);
            }
            if (matchedSlots == null) {
                return view;
            }
            long[] bits = new long[(totalSlots + 63) >>> 6];
            for (int slot : matchedSlots) {
                bits[slot >>> 6] |= 1L << (slot & 63);
            }
            view.applyMatches(bits, matchedSlots.size());
            return view;
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternInfoCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternInfoCases.slicesWholeInventoryIntoFixedRows", () -> new PatternInfoCases().slicesWholeInventoryIntoFixedRows()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternInfoCases.listingStopsAtTheLastContentRowPlusOneFreeRow", () -> new PatternInfoCases().listingStopsAtTheLastContentRowPlusOneFreeRow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternInfoCases.emptyMachineStillListsOneFreeRowForPlacing", () -> new PatternInfoCases().emptyMachineStillListsOneFreeRowForPlacing()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternInfoCases.fullMachineAppendsNoFreeRow", () -> new PatternInfoCases().fullMachineAppendsNoFreeRow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternInfoCases.filteringListsOnlyRowsHoldingAMatch", () -> new PatternInfoCases().filteringListsOnlyRowsHoldingAMatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternInfoCases.emptyOrDegenerateCapacityProducesNoRows", () -> new PatternInfoCases().emptyOrDegenerateCapacityProducesNoRows())
            );
        }
    }

    /** PatternRowCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternRowCases {
        private static final int COLUMNS = 9;
        private static final ItemStack PAPER = new ItemStack(Items.PAPER);
        private static final ItemStack STONE = new ItemStack(Items.STONE);

        void syncPrefersTheRealStackAndFallsBackToTheDisplaySummary() {
            PatternMatrixClientView view = new PatternMatrixClientView();
            view.resize(30);
            view.apply(List.of(new PatternMatrixEntry(18, PAPER, 0, 0), new PatternMatrixEntry(19, STONE, 0, 0)));
            view.applyRealStacks(List.of(new PatternMatrixRealStack(18, STONE)));
            PatternRow row = new PatternRow(18, COLUMNS);

            row.syncFrom(view);

            assertTrue(ItemStack.isSameItemSameComponents(STONE, row.inventory().getStackInSlot(0)));
            assertTrue(ItemStack.isSameItemSameComponents(STONE, row.inventory().getStackInSlot(1)));
            assertTrue(row.inventory().getStackInSlot(8).isEmpty());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternRowCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternRowCases.syncPrefersTheRealStackAndFallsBackToTheDisplaySummary", () -> new PatternRowCases().syncPrefersTheRealStackAndFallsBackToTheDisplaySummary())
            );
        }
    }

    /** SharedMatrixSearchCases 的独立场景夹具，按类别统一执行。 */
    public static final class SharedMatrixSearchCases {
        private static ItemStack namedMarker(String name) {
            var stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            return stack;
        }
        private static PatternMatrixClientView patternView(String input, String output) {
            var view = new PatternMatrixClientView();
            view.resize(36);
            view.apply(List.of(new PatternMatrixEntry(0, new ItemStack(Items.IRON_INGOT), 0, 0,
                    new PatternMatrixSearchTerms(List.of(Component.literal(input)), List.of(Component.literal(output))))));
            return view;
        }

        void switchingEqualVersionViewsDoesNotReusePreviousPageMatches() {
            var search = new PatternMatrixClientSearch(String::contains);
            var patterns = patternView("铁矿石", "铁锭");
            var markers = patternView("熔炉", "熔炉");
            assertEquals(patterns.contentChangeCount(), markers.contentChangeCount());
            search.update(patterns, "铁", PatternSearchMode.INPUT_OUTPUT, "zh_cn");
            assertEquals(1, patterns.matchCount());
            search.update(markers, "铁", PatternSearchMode.INPUT_OUTPUT, "zh_cn");
            assertEquals(0, markers.matchCount());
            search.update(patterns, "铁", PatternSearchMode.INPUT_OUTPUT, "zh_cn");
            assertEquals(1, patterns.matchCount());
        }

        void sharedSearchReactsToModeContentAndClearingWithoutRepeatedUpdates() {
            var search = new PatternMatrixClientSearch(new JechPatternSearchMatcherImpl());
            var view = patternView("铁矿石", "铁锭");
            search.update(view, "tks", PatternSearchMode.INPUT, "zh_cn");
            assertEquals(1, view.matchCount());
            int version = view.changeCount();
            search.update(view, "tks", PatternSearchMode.INPUT, "zh_cn");
            assertEquals(version, view.changeCount());
            search.update(view, "tks", PatternSearchMode.OUTPUT, "zh_cn");
            assertEquals(0, view.matchCount());
            view.apply(List.of(new PatternMatrixEntry(0, new ItemStack(Items.IRON_INGOT), 0, 0,
                    new PatternMatrixSearchTerms(List.of(Component.literal("金矿石")), List.of(Component.literal("铁矿石"))))));
            search.update(view, "tks", PatternSearchMode.OUTPUT, "zh_cn");
            assertEquals(1, view.matchCount());
            search.update(view, "", PatternSearchMode.OUTPUT, "zh_cn");
            assertFalse(view.filterActive());
        }

        void markerViewPreservesRealSlotsAndFiltersRowsWithSamePinyinSearch() {
            var stacks = new ArrayList<>(Collections.nCopies(36, ItemStack.EMPTY));
            var marker = namedMarker("粉碎机");
            stacks.set(27, marker);
            var source = new MachineMarkerClientView();
            var view = source.update(stacks);
            assertEquals(36, view.getTotalSlots());
            assertEquals(1, view.getNonEmptySlots());
            assertTrue(ItemStack.matches(marker, view.realStackAt(27)));
            int version = view.contentChangeCount();
            assertSame(view, source.update(stacks));
            assertEquals(version, view.contentChangeCount());
            var search = new PatternMatrixClientSearch(new JechPatternSearchMatcherImpl());
            for (var mode : PatternSearchMode.values()) {
                search.update(view, "fsj", mode, "zh_cn");
                assertTrue(view.isMatched(27));
                assertEquals(1, new PatternInfo(36, 9).listRows(view).size());
            }
            search.update(view, "minecraft:paper", PatternSearchMode.INPUT_OUTPUT, "en_us");
            assertEquals(1, view.matchCount());
            stacks.set(27, ItemStack.EMPTY);
            source.update(stacks);
            search.update(view, "fsj", PatternSearchMode.INPUT_OUTPUT, "zh_cn");
            assertEquals(0, view.getNonEmptySlots());
            assertEquals(0, view.matchCount());
            assertTrue(view.realStackAt(27).isEmpty());
            assertEquals(PatternMatrixSearchTerms.EMPTY, view.searchTermsAt(27));
        }

        void renamedMarkerUpdatesSearchAndDoesNotRetainCallerStack() {
            var source = new MachineMarkerClientView();
            var marker = namedMarker("旧机器");
            var view = source.update(List.of(marker));
            marker.set(DataComponents.CUSTOM_NAME, Component.literal("新机器"));
            assertEquals("旧机器", view.realStackAt(0).getHoverName().getString());
            source.update(List.of(marker));
            var search = new PatternMatrixClientSearch(String::contains);
            search.update(view, "旧", PatternSearchMode.INPUT_OUTPUT, "zh_cn");
            assertEquals(0, view.matchCount());
            search.update(view, "新", PatternSearchMode.INPUT_OUTPUT, "zh_cn");
            assertEquals(1, view.matchCount());
        }

        void modeOrderMatchesExtendedTerminal() {
            assertEquals(PatternSearchMode.INPUT, PatternSearchMode.INPUT_OUTPUT.next());
            assertEquals(PatternSearchMode.OUTPUT, PatternSearchMode.INPUT.next());
            assertEquals(PatternSearchMode.INPUT_OUTPUT, PatternSearchMode.OUTPUT.next());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SharedMatrixSearchCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SharedMatrixSearchCases.switchingEqualVersionViewsDoesNotReusePreviousPageMatches", () -> new SharedMatrixSearchCases().switchingEqualVersionViewsDoesNotReusePreviousPageMatches()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SharedMatrixSearchCases.sharedSearchReactsToModeContentAndClearingWithoutRepeatedUpdates", () -> new SharedMatrixSearchCases().sharedSearchReactsToModeContentAndClearingWithoutRepeatedUpdates()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SharedMatrixSearchCases.markerViewPreservesRealSlotsAndFiltersRowsWithSamePinyinSearch", () -> new SharedMatrixSearchCases().markerViewPreservesRealSlotsAndFiltersRowsWithSamePinyinSearch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SharedMatrixSearchCases.renamedMarkerUpdatesSearchAndDoesNotRetainCallerStack", () -> new SharedMatrixSearchCases().renamedMarkerUpdatesSearchAndDoesNotRetainCallerStack()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SharedMatrixSearchCases.modeOrderMatchesExtendedTerminal", () -> new SharedMatrixSearchCases().modeOrderMatchesExtendedTerminal())
            );
        }
    }
}
