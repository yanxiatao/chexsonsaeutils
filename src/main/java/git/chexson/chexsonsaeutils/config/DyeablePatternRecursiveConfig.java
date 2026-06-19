package git.chexson.chexsonsaeutils.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;

/**
 * 染色样板递归合成配置。
 *
 * 控制递归催化物在合成计划完成后需要保留的库存数量。
 */
public final class DyeablePatternRecursiveConfig {
    public static final int DEFAULT_RETAINED_CATALYST_AMOUNT = 1;
    public static final int MAX_RETAINED_CATALYST_AMOUNT = 1_000_000;
    private static final String CONFIG_KEY = "dyeableRecursiveRetainedCatalystAmount";

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

    static long retainedCatalystAmount(Path configFile) {
        return clamp(
                StartupConfigIntReader.read(
                        configFile,
                        CONFIG_KEY,
                        ChexsonsaeutilsCompatibilityConfig.DYEABLE_RECURSIVE_RETAINED_CATALYST_AMOUNT.getDefault()
                ),
                0,
                MAX_RETAINED_CATALYST_AMOUNT
        );
    }

    private static int intValue(ModConfigSpec.ConfigValue<Integer> value) {
        if (ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded()) {
            return value.get();
        }
        return StartupConfigIntReader.read(CONFIG_KEY, value.getDefault());
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
