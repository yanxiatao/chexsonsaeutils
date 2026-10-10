package git.chexson.chexsonsaeutils.config;

import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumSet;
import java.util.List;

/** 服务端规划算法顺序配置；持久层字符串不会泄漏到公开读写 API。 */
public final class PlanningServerConfig {
    public static final List<PlanningAlgorithmId> DEFAULT_ORDER = List.of(
            PlanningAlgorithmId.FROZEN_ROUTE,
            PlanningAlgorithmId.GRAPH,
            PlanningAlgorithmId.FAST,
            PlanningAlgorithmId.NATIVE);

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ORDER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Server-side crafting planning algorithm order.")
                .translation("configuration.chexsonsaeutils.section.planningAlgorithms")
                .push("planningAlgorithms");
        ORDER = defineOrder(builder, "order", DEFAULT_ORDER);
        builder.pop();
        SPEC = builder.build();
    }

    private PlanningServerConfig() {
    }

    public static List<PlanningAlgorithmId> order() {
        return readOrder(ORDER, DEFAULT_ORDER);
    }

    public static void setOrder(List<PlanningAlgorithmId> order) {
        List<PlanningAlgorithmId> validated = requireCompleteOrder(order);
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("Planning server config is not loaded");
        }
        List<? extends String> previous = ORDER.get();
        ORDER.set(encode(validated));
        try {
            ORDER.save();
        } catch (RuntimeException exception) {
            ORDER.set(previous);
            throw exception;
        }
    }

    static List<PlanningAlgorithmId> normalizeOrder(
            List<? extends String> persistedOrder,
            List<PlanningAlgorithmId> defaultOrder
    ) {
        if (persistedOrder == null) {
            return defaultOrder;
        }
        List<PlanningAlgorithmId> parsed = persistedOrder.stream()
                .map(PlanningAlgorithmId::parse)
                .flatMap(java.util.Optional::stream)
                .toList();
        try {
            return requireCompleteOrder(parsed);
        } catch (IllegalArgumentException exception) {
            return defaultOrder;
        }
    }

    public static List<PlanningAlgorithmId> requireCompleteOrder(List<PlanningAlgorithmId> order) {
        if (order == null || order.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Planning algorithm order must not contain null");
        }
        EnumSet<PlanningAlgorithmId> expected = EnumSet.allOf(PlanningAlgorithmId.class);
        EnumSet<PlanningAlgorithmId> actual = order.isEmpty()
                ? EnumSet.noneOf(PlanningAlgorithmId.class)
                : EnumSet.copyOf(order);
        if (order.size() != expected.size() || actual.size() != order.size() || !actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "Planning algorithm order must be a complete permutation of " + expected);
        }
        return List.copyOf(order);
    }

    private static ModConfigSpec.ConfigValue<List<? extends String>> defineOrder(
            ModConfigSpec.Builder builder,
            String key,
            List<PlanningAlgorithmId> defaultOrder
    ) {
        List<String> defaults = encode(defaultOrder);
        return builder.comment("A complete permutation of: " + String.join(", ", defaults))
                .define(key, defaults, PlanningServerConfig::isCompletePersistedOrder);
    }

    private static boolean isCompletePersistedOrder(Object value) {
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<String> persistedOrder = (List<String>) list;
        List<PlanningAlgorithmId> normalized = normalizeOrder(persistedOrder, DEFAULT_ORDER);
        return normalized.stream().map(Enum::name).toList().equals(persistedOrder);
    }

    private static List<PlanningAlgorithmId> readOrder(
            ModConfigSpec.ConfigValue<List<? extends String>> value,
            List<PlanningAlgorithmId> defaultOrder
    ) {
        List<? extends String> persisted = SPEC.isLoaded() ? value.get() : value.getDefault();
        return normalizeOrder(persisted, defaultOrder);
    }

    private static List<String> encode(List<PlanningAlgorithmId> order) {
        return order.stream().map(Enum::name).toList();
    }
}
