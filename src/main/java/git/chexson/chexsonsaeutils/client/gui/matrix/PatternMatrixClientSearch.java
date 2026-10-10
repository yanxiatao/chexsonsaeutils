package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSearch;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;

/** 按视图、内容、语言、查询及模式缓存搜索，两页切换不会复用其他库存的命中结果。 */
public final class PatternMatrixClientSearch {
    private final PatternSearchMatcher matcher;
    private PatternMatrixClientView lastView;
    private int lastVersion = -1;
    private String lastQuery, lastLanguage;
    private PatternSearchMode lastMode;
    public PatternMatrixClientSearch(PatternSearchMatcher matcher) { this.matcher = matcher; }
    public void update(PatternMatrixClientView view, String query, PatternSearchMode mode, String language) {
        if (view == lastView && view.contentChangeCount() == lastVersion && query.equals(lastQuery)
                && mode == lastMode && language.equals(lastLanguage)) return;
        lastView = view; lastVersion = view.contentChangeCount();
        lastQuery = query; lastLanguage = language; lastMode = mode;
        if (query.isBlank()) { view.applyMatches(new long[0], 0); return; }
        var tokens = PatternMatrixSearch.tokenize(query);
        long[] bits = new long[PatternMatrixSearch.bitsetSize(view.getTotalSlots())];
        int count = 0;
        for (int slot = 0; slot < view.getTotalSlots(); slot++) {
            if (view.hasDisplayStack(slot) && PatternClientSearch.matches(view.searchTermsAt(slot), tokens, mode, matcher)) {
                bits[slot >>> 6] |= 1L << (slot & 63); count++;
            }
        }
        view.applyMatches(bits, count);
    }
}
