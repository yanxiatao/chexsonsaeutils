package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 装配矩阵的服务端搜索：界面侧是滚动行列表，过滤整行所需的命中集合只能由持有
 * 配方解码能力的一端给出，因此查询在这里展开为位图，再由界面按行筛选。
 *
 * <p>匹配口径覆盖样板自身、全部产出与全部可选输入（含输入替代项），按 ExtendedAE
 * 装配矩阵的分词写法比对：查询与候选名都按空格切词，再要求查询词依序被包含。</p>
 */
public final class PatternMatrixSearch {

    private PatternMatrixSearch() {
    }

    /** 位图长度：每 64 个槽位一个 long。 */
    public static int bitsetSize(int totalSlots) {
        return (Math.max(1, totalSlots) + 63) >>> 6;
    }

    /**
     * 写入命中位图并返回命中数。位图会先被清零，调用方可直接复用同一数组。
     *
     * @param matchBits 至少 {@link #bitsetSize(int)} 长度
     */
    public static int findMatches(
            PatternSlotStore store,
            DecodedPatternEntryCache decodedPatternEntryCache,
            @Nullable Level level,
            String rawQuery,
            long[] matchBits
    ) {
        Arrays.fill(matchBits, 0L);
        List<String> tokens = tokenize(rawQuery);
        if (tokens.isEmpty() || level == null) {
            return 0;
        }
        int matches = 0;
        for (int slot = 0; slot < store.getTotalSlots(); slot++) {
            ItemStack stack = store.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (matchesStack(stack, tokens) || matchesPattern(slot, stack, tokens, level, decodedPatternEntryCache)) {
                matchBits[slot >>> 6] |= 1L << (slot & 63);
                matches++;
            }
        }
        return matches;
    }

    private static boolean matchesPattern(
            int slot,
            ItemStack encodedPattern,
            List<String> tokens,
            Level level,
            DecodedPatternEntryCache cache
    ) {
        IPatternDetails patternDetails = cachedPattern(cache, slot, encodedPattern);
        if (patternDetails == null && !cache.matches(slot, encodedPattern)) {
            patternDetails = PatternDetailsHelper.decodePattern(encodedPattern, level);
            cache.put(slot, encodedPattern, patternDetails);
        }
        if (patternDetails == null) {
            return false;
        }
        for (GenericStack output : patternDetails.getOutputs()) {
            if (matchesGenericStack(output, tokens)) {
                return true;
            }
        }
        for (IPatternDetails.IInput input : patternDetails.getInputs()) {
            for (GenericStack possibleInput : input.getPossibleInputs()) {
                if (matchesGenericStack(possibleInput, tokens)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static IPatternDetails cachedPattern(DecodedPatternEntryCache cache, int slot, ItemStack encodedPattern) {
        DecodedPatternEntryCache.Entry entry = cache.get(slot);
        if (entry == null || !cache.matches(slot, encodedPattern)) {
            return null;
        }
        return entry.patternDetails();
    }

    private static boolean matchesGenericStack(@Nullable GenericStack stack, List<String> tokens) {
        return stack != null && stack.what() instanceof AEItemKey itemKey && matchesStack(itemKey.toStack(), tokens);
    }

    private static boolean matchesStack(ItemStack stack, List<String> tokens) {
        if (stack.isEmpty()) {
            return false;
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(Component.translatable(stack.getDescriptionId()).getString());
        candidates.add(stack.getHoverName().getString());
        stack.getItemHolder().unwrapKey().ifPresent(key -> candidates.add(key.location().toString()));
        for (String candidate : candidates) {
            if (compareTokens(tokens, tokenize(candidate))) {
                return true;
            }
        }
        return false;
    }

    /** 按空格切词并统一小写，逐行照搬 ExtendedAE 的 FCUtil.tokenize。 */
    public static List<String> tokenize(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lowered = text.trim().toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (var token : lowered.split(" ")) {
            if (!token.isBlank()) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    /** 查询词需在目标词序中连续可依序包含，逐行照搬 ExtendedAE 的 FCUtil.compareTokens。 */
    public static boolean compareTokens(List<String> filter, List<String> target) {
        int p = 0;
        while (p <= target.size() - filter.size()) {
            int q = p, f = 0;
            while (q < target.size() && f < filter.size()) {
                var tt = target.get(q);
                var ft = filter.get(f);
                if (tt.contains(ft)) {
                    q++;
                    f++;
                } else {
                    q++;
                }
            }
            if (f >= filter.size()) {
                return true;
            } else {
                p++;
            }
        }
        return false;
    }
}
