package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.crafting.IPatternDetails;
import com.mojang.logging.LogUtils;
import com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.SmartDoublingPatterns;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * useless_mod 的份数折叠算式：把 k 发的提取量折成它认得的 {@code ScaledProcessingPattern(inner, k)}。
 *
 * <p>它家有两个网格入口（单机 {@code AdvancedAlloyFurnaceBlockEntity} 与多方块
 * {@code MePatternAssemblyBlockEntity}）都把 push 转交给同一个
 * {@code AdvancedAlloyFurnaceAeManager}，该 manager 按 {@code PendingAEBatch.operationsPerPush}
 * 记发数——所以折叠算式只有一份，两个入口各自 mixin 一层薄壳调它。
 */
public final class UselessSmartDoublingFold {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean FIRST_FOLD = new AtomicBoolean(false);

    private UselessSmartDoublingFold() {
    }

    /** 一次 push 最多能记几发：它自己对样板量的 long 上界（普通样板是 1e18 量级）。 */
    public static long maxFoldedPushExecutions(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return 0L;
        }
        try {
            return SmartDoublingPatterns.maximumSafeMultiplier(SmartDoublingPatterns.unwrap(pattern));
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @Nullable
    public static IPatternDetails foldPushPattern(
            @Nullable IPatternDetails pattern, long executions
    ) {
        if (pattern == null || executions <= 1L) {
            return null;
        }
        try {
            if (executions > maxFoldedPushExecutions(pattern)) {
                // 宁可退回单发：折出一个少于这份 holder 的壳，会把它按少几发记账，
                // 余料压在机器输入槽里。
                return null;
            }
            IPatternDetails scaled = SmartDoublingPatterns.scale(pattern, executions);
            if (FIRST_FOLD.compareAndSet(false, true)) {
                LOGGER.info("[chexsonsaeutils-foldhost] folded push executions={} shell={}",
                        executions, scaled.getClass().getName());
            }
            return scaled;
        } catch (RuntimeException e) {
            LOGGER.warn("[chexsonsaeutils-foldhost] fold failed executions={}", executions, e);
            return null;
        }
    }
}
