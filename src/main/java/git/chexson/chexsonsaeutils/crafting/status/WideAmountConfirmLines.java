package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.core.localization.GuiText;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 确认表条目里"超 long 那一行"的原地改写：AE2 自己在 {@code CraftConfirmTableRenderer}
 * 里按 {@code long} 写出的行，对溢出键显示的是饱和后的 {@code Long.MAX_VALUE}，
 * 这里把它换成计划带出来的宽域真值（格子走缩写、tooltip 走全量千分位）。
 *
 * <p><b>为什么按位置改写，而不是按文案匹配</b>：AE2 那三行的建块规则是纯函数——
 * {@code getEntryDescription}（{@code CraftConfirmTableRenderer.java:42-55}）与
 * {@code getEntryTooltip}（同文件 {@code :65-80}）都按 <i>FromStorage → Missing → ToCraft</i>
 * 的顺序、只写量 {@code > 0} 的那几行。所以我们能只用条目自己的三个 {@code long}
 * 还原出块起点与块长，不需要解析 MC 的 chat contents（那套类名在小版本间改过名）。
 * 形状对不上就一行都不动，宁缺勿误伤。
 *
 * <p><b>口径边界（诚实交代）</b>：AE2 的 FromStorage 行是 {@code used + missing + emitted}
 * 之和、ToCraft 行是 {@code emitted + Σ(样板产出×发数)} 之和，而快照按类各记各的账。
 * 因此被替换的那一行显示的是<b>该类自己的宽域真值</b>；同一键若还有能塞 long 的别类量，
 * 那一行不再包含它们（原来那一行在溢出时本来就是饱和假数）。没命中的行连对象引用都不换。
 *
 * <p>选了指数计数法（{@link AmountNumberStyle#SCIENTIFIC}）时，紧凑列里<b>没溢出的行也重新装配</b>：
 * 用的仍是 AE2 自己那三个 {@code long} 读数，只换写法，同一列不出现两种计数方式。
 */
public final class WideAmountConfirmLines {

    /** AE2 的建块顺序：FromStorage（对应"已用/从存储"）→ Missing → ToCraft（对应"产出/待合成"）。 */
    private static final WideAmountKind[] KINDS = {
            WideAmountKind.USED,
            WideAmountKind.MISSING,
            WideAmountKind.EMIT,
    };

    /** 与 {@link #KINDS} 一一对应的 AE2 行文案键：沿用 AE2 自己的标签，不造第二个说法。 */
    private static final String[] KEYS = {
            GuiText.FromStorage.getTranslationKey(),
            GuiText.Missing.getTranslationKey(),
            GuiText.ToCraft.getTranslationKey(),
    };

    private WideAmountConfirmLines() {
    }

    /** AE2 会为该条目实际写出几行（只写量 {@code > 0} 的），与我们定位块长用同一判据。 */
    public static int shownLineCount(long storedAmount, long missingAmount, long craftAmount) {
        int count = 0;
        if (storedAmount > 0L) {
            count++;
        }
        if (missingAmount > 0L) {
            count++;
        }
        if (craftAmount > 0L) {
            count++;
        }
        return count;
    }

    /**
     * 就地把 {@code lines} 中从 {@code blockStart} 开始的 AE2 数量块改写成宽域真值，
     * 并按 {@code style} 重写紧凑列的计数写法。
     *
     * @param blockStart AE2 那一块在 {@code lines} 里的起始下标（格子为 0，tooltip 为块尾偏移）
     * @param storedShown / missingShown / craftShown 条目里那三个 {@code long} 读数（决定块形状）
     * @return 被重新装配的行数；返回 0 表示一行都没动
     */
    public static int applyWideAmounts(
            @Nullable List<Component> lines,
            int blockStart,
            long storedShown,
            long missingShown,
            long craftShown,
            @Nullable WideLedgerSnapshot snapshot,
            @Nullable AEKey key,
            AmountFormat format,
            AmountNumberStyle style
    ) {
        if (lines == null || key == null) {
            return 0;
        }
        // 计数方式只换紧凑列的写法；tooltip 那一列恒留全量真值，一字不动。
        boolean restyle = style == AmountNumberStyle.SCIENTIFIC && format == AmountFormat.SLOT;
        boolean hasSnapshot = snapshot != null && !snapshot.isEmpty();
        if (!restyle && !hasSnapshot) {
            return 0;
        }
        long[] shown = {storedShown, missingShown, craftShown};
        int existing = shownLineCount(storedShown, missingShown, craftShown);
        if (blockStart < 0 || blockStart + existing > lines.size()) {
            return 0;
        }
        BigInteger[] exact = new BigInteger[KINDS.length];
        int hits = 0;
        if (hasSnapshot) {
            for (int index = 0; index < KINDS.length; index++) {
                exact[index] = snapshot.get(KINDS[index], key);
                if (exact[index] != null) {
                    hits++;
                }
            }
        }
        if (hits == 0 && !restyle) {
            // 这个键没有溢出：AE2 原文本一字不变，连组件对象都不重新装配。
            return 0;
        }
        List<Component> current = new ArrayList<>(lines.subList(blockStart, blockStart + existing));
        List<Component> rebuilt = new ArrayList<>(KINDS.length);
        int cursor = 0;
        int rewritten = 0;
        for (int index = 0; index < KINDS.length; index++) {
            Component kept = shown[index] > 0L && cursor < current.size() ? current.get(cursor++) : null;
            if (exact[index] != null) {
                rebuilt.add(Component.translatable(KEYS[index],
                        EnhancedCraftingStatusFormatting.formatAmount(key, exact[index], format, style)));
            } else if (kept != null && restyle) {
                // 数字仍是 AE2 自己那三个 long 读数，只换写法（同一列不出现两种计数方式）。
                rebuilt.add(Component.translatable(KEYS[index],
                        EnhancedCraftingStatusFormatting.formatAmount(key,
                                BigInteger.valueOf(shown[index]), format, style)));
                rewritten++;
            } else if (kept != null) {
                rebuilt.add(kept);
            }
        }
        for (int index = 0; index < existing; index++) {
            lines.remove(blockStart);
        }
        lines.addAll(blockStart, rebuilt);
        return hits + rewritten;
    }
}
