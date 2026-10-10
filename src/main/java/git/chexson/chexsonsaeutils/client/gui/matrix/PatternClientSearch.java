package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSearch;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 搜索当前客户端语言的摘要，按输入或输出范围匹配全部查询词。 */
public final class PatternClientSearch {
    private PatternClientSearch() {}

    public static boolean matches(PatternMatrixSearchTerms terms, List<String> query,
                                  PatternSearchMode mode, PatternSearchMatcher matcher) {
        return (mode.includesInputs() && matchesNames(terms.inputs(), query, matcher))
                || (mode.includesOutputs() && matchesNames(terms.outputs(), query, matcher));
    }

    private static boolean matchesNames(List<Component> candidates, List<String> query, PatternSearchMatcher matcher) {
        for (Component candidate : candidates) {
            List<String> words = PatternMatrixSearch.tokenize(candidate.getString());
            int index = 0;
            for (String word : words) {
                if (index < query.size() && matcher.contains(word, query.get(index))) index++;
            }
            if (index == query.size()) return true;
        }
        return false;
    }
}
