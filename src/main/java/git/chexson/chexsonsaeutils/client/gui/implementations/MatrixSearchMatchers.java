package git.chexson.chexsonsaeutils.client.gui.implementations;

import git.chexson.chexsonsaeutils.client.gui.matrix.PatternSearchMatcher;
import git.chexson.chexsonsaeutils.client.integration.jech.JechPatternSearchMatcherImpl;
import net.neoforged.fml.ModList;

/** 在界面装配侧选择可选 JECH，未安装时不会加载 JECH 实现。 */
final class MatrixSearchMatchers {
    private MatrixSearchMatchers() {}
    static PatternSearchMatcher create() {
        return ModList.get().isLoaded("jecharacters") ? JechHolder.create() : String::contains;
    }
    private static final class JechHolder {
        static PatternSearchMatcher create() { return new JechPatternSearchMatcherImpl(); }
    }
}
