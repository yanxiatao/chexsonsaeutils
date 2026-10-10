package git.chexson.chexsonsaeutils.crafting.solve;

import java.util.Locale;
import java.util.Optional;

/** 可由服务端排序的规划算法及其稳定元数据。 */
public enum PlanningAlgorithmId {
    GRAPH(false),
    NATIVE(true);

    private static final String TRANSLATION_PREFIX = "planning_algorithm.chexsonsaeutils.";

    private final boolean terminal;
    private final String translationKey;
    private final String descriptionKey;

    PlanningAlgorithmId(boolean terminal) {
        String keySuffix = name().toLowerCase(Locale.ROOT);
        this.translationKey = TRANSLATION_PREFIX + keySuffix;
        this.descriptionKey = this.translationKey + ".description";
        this.terminal = terminal;
    }

    public boolean terminal() {
        return this.terminal;
    }

    public boolean isTerminal() {
        return this.terminal;
    }

    public String translationKey() {
        return this.translationKey;
    }

    public String descriptionKey() {
        return this.descriptionKey;
    }

    public static Optional<PlanningAlgorithmId> parse(String persistedId) {
        if (persistedId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(persistedId.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
