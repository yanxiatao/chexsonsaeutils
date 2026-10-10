package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 服务端规划算法顺序配置；公开读写 API 说串名册，不引能力包类型。 */
public final class PlanningServerConfig {
    // 串名册＝算法全集在本配置侧唯一自持的真相，默认序与"完整排列"判据共用这一份串。
    // 算法类型仍留在能力侧 crafting.solve.PlanningAlgorithmId（本类不许引它），所以全集只能以串的形状活在这里；
    // 名册与枚举个名的漂移由 PlanningAlgorithmOrderTest#rosterMatchesEveryAlgorithmName 钉住：
    // 少写一枚、多写一枚、换了顺序都判红（测试不入主口径 census）。
    public static final List<String> DEFAULT_ORDER = List.of("GRAPH", "NATIVE");

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ORDER;
    private static final ModConfigSpec.BooleanValue LOW_MEMORY;
    private static final ModConfigSpec.BooleanValue AUTO_SPILL;
    private static final ModConfigSpec.IntValue HEAP_RESERVE_MIB;
    private static final ModConfigSpec.ConfigValue<Integer> JOURNAL_PAGE_ENTRIES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Server-side crafting planning algorithm order.")
                .translation("configuration.chexsonsaeutils.section.planningAlgorithms")
                .push("planningAlgorithms");
        ORDER = defineOrder(builder, "order", DEFAULT_ORDER);
        builder.pop();
        builder.comment("图求解内存配置：仅影响回滚日志，不改变规划结果或数量精度。")
                .push("planningMemory");
        LOW_MEMORY = builder.comment("低内存模式：将超出一页的回滚历史暂存到系统临时目录，可能降低求解速度。默认关闭。")
                .define("lowMemory", false);
        AUTO_SPILL = builder.comment("自动暂存：在堆余量不足或下次日志扩容侵入预留空间前，自动迁移现有日志到磁盘分页。默认开启。")
                .define("autoSpill", true);
        HEAP_RESERVE_MIB = builder.comment("自动暂存至少保留的堆空间，单位 MiB；实际预留取本值与最大堆 10% 的较大值。")
                .defineInRange("heapReserveMiB", 256, 16, 16384);
        JOURNAL_PAGE_ENTRIES = builder.comment("每个求解任务驻留的日志页条目数，必须为 2 的幂。默认 65536；越小越省堆内存，磁盘 I/O 越频繁。")
                .define("journalPageEntries", 65536,
                        value -> value instanceof Integer n && n >= 1024 && n <= 1048576 && Integer.bitCount(n) == 1);
        builder.pop();
        SPEC = builder.build();
    }

    private PlanningServerConfig() {
    }

    public static boolean lowMemory() {
        return SPEC.isLoaded() ? LOW_MEMORY.get() : LOW_MEMORY.getDefault();
    }

    public static boolean autoSpill() {
        return SPEC.isLoaded() ? AUTO_SPILL.get() : AUTO_SPILL.getDefault();
    }

    public static int heapReserveMiB() {
        return SPEC.isLoaded() ? HEAP_RESERVE_MIB.get() : HEAP_RESERVE_MIB.getDefault();
    }

    public static int journalPageEntries() {
        return SPEC.isLoaded() ? JOURNAL_PAGE_ENTRIES.get() : JOURNAL_PAGE_ENTRIES.getDefault();
    }

    public static List<String> order() {
        return readOrder(ORDER, DEFAULT_ORDER);
    }

    public static void setOrder(List<String> order) {
        List<String> validated = requireCompleteOrder(order);
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("Planning server config is not loaded");
        }
        List<? extends String> previous = ORDER.get();
        ORDER.set(validated);
        try {
            ORDER.save();
        } catch (RuntimeException exception) {
            ORDER.set(previous);
            throw exception;
        }
    }

    // 纯串归一，与旧的"parse 成枚举→回落默认"逐条等值：trim＋转大写根、认不出的丢掉、
    // 丢掉之后不完整就整串回落默认序；差别只在归一结果仍是串。
    static List<String> normalizeOrder(
            List<? extends String> persistedOrder,
            List<String> defaultOrder
    ) {
        if (persistedOrder == null) {
            return defaultOrder;
        }
        List<String> recognized = new ArrayList<>();
        for (String persisted : persistedOrder) {
            if (persisted == null) {
                continue;
            }
            String name = persisted.trim().toUpperCase(Locale.ROOT);
            // 认档只看串名册：名册就是算法全集，认不出＝损坏（与枚举个名一致由上面那枚对照用例钉）。
            if (DEFAULT_ORDER.contains(name)) {
                recognized.add(name);
            }
        }
        try {
            return requireCompleteOrder(recognized);
        } catch (IllegalArgumentException exception) {
            return defaultOrder;
        }
    }

    public static List<String> requireCompleteOrder(List<String> order) {
        if (order == null || order.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Planning algorithm order must not contain null");
        }
        Set<String> expected = new LinkedHashSet<>(DEFAULT_ORDER);
        Set<String> actual = new LinkedHashSet<>(order);
        if (order.size() != expected.size() || actual.size() != order.size() || !actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "Planning algorithm order must be a complete permutation of " + expected);
        }
        return List.copyOf(order);
    }

    private static ModConfigSpec.ConfigValue<List<? extends String>> defineOrder(
            ModConfigSpec.Builder builder,
            String key,
            List<String> defaultOrder
    ) {
        return builder.comment("A complete permutation of: " + String.join(", ", defaultOrder))
                .define(key, defaultOrder, PlanningServerConfig::isCompletePersistedOrder);
    }

    private static boolean isCompletePersistedOrder(Object value) {
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<String> persistedOrder = (List<String>) list;
        return normalizeOrder(persistedOrder, DEFAULT_ORDER).equals(persistedOrder);
    }

    private static List<String> readOrder(
            ModConfigSpec.ConfigValue<List<? extends String>> value,
            List<String> defaultOrder
    ) {
        List<? extends String> persisted = SPEC.isLoaded() ? value.get() : value.getDefault();
        return normalizeOrder(persisted, defaultOrder);
    }
}
