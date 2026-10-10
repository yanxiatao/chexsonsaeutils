package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.client.integration.jech.JechPatternSearchMatcherImpl;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedMatrixSearchTest {
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

    @Test void switchingEqualVersionViewsDoesNotReusePreviousPageMatches() {
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

    @Test void sharedSearchReactsToModeContentAndClearingWithoutRepeatedUpdates() {
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

    @Test void markerViewPreservesRealSlotsAndFiltersRowsWithSamePinyinSearch() {
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

    @Test void renamedMarkerUpdatesSearchAndDoesNotRetainCallerStack() {
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

    @Test void modeOrderMatchesExtendedTerminal() {
        assertEquals(PatternSearchMode.INPUT, PatternSearchMode.INPUT_OUTPUT.next());
        assertEquals(PatternSearchMode.OUTPUT, PatternSearchMode.INPUT.next());
        assertEquals(PatternSearchMode.INPUT_OUTPUT, PatternSearchMode.OUTPUT.next());
    }
}
