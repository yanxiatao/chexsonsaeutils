package git.chexson.chexsonsaeutils.client.integration.jech;

import git.chexson.chexsonsaeutils.client.gui.matrix.PatternSearchMatcher;
import me.towdium.jecharacters.utils.Match;

/** 直接复用 JECH 公开匹配入口，遵循其全拼、首字母及拼写配置。 */
public final class JechPatternSearchMatcherImpl implements PatternSearchMatcher {
    @Override
    public boolean contains(String candidate, String query) {
        return Match.contains(candidate, query);
    }
}
