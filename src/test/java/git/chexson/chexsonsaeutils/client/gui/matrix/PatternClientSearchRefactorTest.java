package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.client.integration.jech.JechPatternSearchMatcherImpl;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import net.minecraft.network.chat.Component;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证三种搜索范围、JECH 真拼音匹配和本地化摘要的字节往返。 */
class PatternClientSearchRefactorTest {
    private static final PatternMatrixSearchTerms TERMS = new PatternMatrixSearchTerms(
            List.of(Component.literal("铁矿石")), List.of(Component.literal("铁锭"), Component.literal("石头")));

    @Test
    void inputOutputModesOnlyMatchSelectedSideIncludingByproducts() {
        PatternSearchMatcher matcher = String::contains;
        assertTrue(PatternClientSearch.matches(TERMS, List.of("矿石"), PatternSearchMode.INPUT, matcher));
        assertFalse(PatternClientSearch.matches(TERMS, List.of("矿石"), PatternSearchMode.OUTPUT, matcher));
        assertTrue(PatternClientSearch.matches(TERMS, List.of("石头"), PatternSearchMode.OUTPUT, matcher));
        assertFalse(PatternClientSearch.matches(TERMS, List.of("石头"), PatternSearchMode.INPUT, matcher));
        assertTrue(PatternClientSearch.matches(TERMS, List.of("矿石"), PatternSearchMode.INPUT_OUTPUT, matcher));
        assertTrue(PatternClientSearch.matches(TERMS, List.of("石头"), PatternSearchMode.INPUT_OUTPUT, matcher));
    }

    @Test
    void jechMatchesFullPinyinInitialsAndMixedChineseWithCorrectDirection() {
        var matcher = new JechPatternSearchMatcherImpl();
        assertTrue(PatternClientSearch.matches(TERMS, List.of("tiekuangshi"), PatternSearchMode.INPUT, matcher));
        assertTrue(PatternClientSearch.matches(TERMS, List.of("tks"), PatternSearchMode.INPUT, matcher));
        assertTrue(PatternClientSearch.matches(TERMS, List.of("tie矿石"), PatternSearchMode.INPUT, matcher));
        assertFalse(PatternClientSearch.matches(TERMS, List.of("tiekuangshi"), PatternSearchMode.OUTPUT, matcher));
        assertFalse(PatternClientSearch.matches(TERMS, List.of("jin"), PatternSearchMode.INPUT_OUTPUT, matcher));
    }

    @Test
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
}
