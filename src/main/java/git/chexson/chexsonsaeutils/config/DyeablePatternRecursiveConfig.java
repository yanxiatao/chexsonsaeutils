package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 染色样板递归合成配置。
 *
 * 控制递归催化物在合成计划完成后需要保留的库存数量。
 */
public final class DyeablePatternRecursiveConfig {
    public static final int DEFAULT_RETAINED_CATALYST_AMOUNT = 1;
    public static final int MAX_RETAINED_CATALYST_AMOUNT = 1_000_000;
    public static final boolean DEFAULT_CROSS_COLOR_CHAIN_PLANNING_ENABLED = false;

    private DyeablePatternRecursiveConfig() {
    }

    /**
     * 返回合成结束后应保留的递归催化物数量。
     */
    public static long retainedCatalystAmount() {
        return clamp(
                intValue(ChexsonsaeutilsCompatibilityConfig.DYEABLE_RECURSIVE_RETAINED_CATALYST_AMOUNT),
                0,
                MAX_RETAINED_CATALYST_AMOUNT
        );
    }

    /**
     * 返回链式合成后续不同色样板是否继续参与递归环计算。
     */
    public static boolean crossColorChainPlanningEnabled() {
        return booleanValue(
                ChexsonsaeutilsCompatibilityConfig.DYEABLE_RECURSIVE_CROSS_COLOR_CHAIN_PLANNING_ENABLED
        );
    }

    private static int intValue(ModConfigSpec.ConfigValue<Integer> value) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return value.get();
        }
        return value.getDefault();
    }

    private static boolean booleanValue(ModConfigSpec.BooleanValue value) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return value.get();
        }
        return value.getDefault();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
