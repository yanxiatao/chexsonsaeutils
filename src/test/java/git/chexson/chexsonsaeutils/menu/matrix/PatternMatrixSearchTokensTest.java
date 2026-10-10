package git.chexson.chexsonsaeutils.menu.matrix;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定搜索的分词口径（照搬 ExtendedAE 的 FCUtil）：查询按空格切词，
 * 候选名同样切词后要求查询词按顺序连续命中，且只做到“被包含”级别。
 */
class PatternMatrixSearchTokensTest {

    @Test
    void blankQueriesProduceNoTokens() {
        assertTrue(PatternMatrixSearch.tokenize(null).isEmpty());
        assertTrue(PatternMatrixSearch.tokenize("   ").isEmpty());
    }

    @Test
    void tokensAreLowercasedAndSplitOnSpaces() {
        assertEquals(List.of("iron", "ingot"), PatternMatrixSearch.tokenize("  Iron  INGOT "));
    }

    @Test
    void inOrderTokensMatchASubstringOfEachTargetWord() {
        List<String> query = PatternMatrixSearch.tokenize("iro ing");
        List<String> target = PatternMatrixSearch.tokenize("Iron Ingot");

        assertTrue(PatternMatrixSearch.compareTokens(query, target));
    }

    @Test
    void reversedTokenOrderDoesNotMatch() {
        List<String> query = PatternMatrixSearch.tokenize("ingot iron");
        List<String> target = PatternMatrixSearch.tokenize("Iron Ingot");

        assertFalse(PatternMatrixSearch.compareTokens(query, target));
    }

    @Test
    void skippingTargetWordsAheadIsAllowed() {
        List<String> query = PatternMatrixSearch.tokenize("ing");
        List<String> target = PatternMatrixSearch.tokenize("Raw Iron Ingot");

        assertTrue(PatternMatrixSearch.compareTokens(query, target));
    }

    @Test
    void moreQueryWordsThanTargetWordsCannotMatch() {
        List<String> query = PatternMatrixSearch.tokenize("iron ingot block");
        List<String> target = PatternMatrixSearch.tokenize("Iron Ingot");

        assertFalse(PatternMatrixSearch.compareTokens(query, target));
    }
}
