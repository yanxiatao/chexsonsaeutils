package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.items.misc.WrappedGenericStack;
import git.chexson.chexsonsaeutils.blockentity.crafting.DecodedPatternEntryCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 装配矩阵格子的展示栈解析：下发产出物而不是样板本体。
 *
 * <p>样板本体带着整份配方，一万六千格全量下发会直接把包撑爆；产出物只有一件物品，
 * 界面渲染用它，搜索由服务端按解码后的配方完成。解析走宿主已有的样板解码缓存，
 * 因此每格最多解一次，解不出来的样板退回显示样板物品本身。</p>
 */
public final class PatternMatrixDisplay {

    private PatternMatrixDisplay() {
    }

    public static ItemStack displayStackFor(
            @Nullable DecodedPatternEntryCache cache,
            int slot,
            ItemStack stored,
            @Nullable Level level
    ) {
        if (stored.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (cache != null && cache.matches(slot, stored)) {
            DecodedPatternEntryCache.Entry entry = cache.get(slot);
            return displayOf(entry == null ? null : entry.patternDetails(), stored);
        }
        IPatternDetails decoded = level == null ? null : PatternDetailsHelper.decodePattern(stored, level);
        if (cache != null) {
            cache.put(slot, stored, decoded);
        }
        return displayOf(decoded, stored);
    }

    private static ItemStack displayOf(@Nullable IPatternDetails details, ItemStack stored) {
        GenericStack primary = details == null || details.getOutputs().isEmpty()
                ? null
                : details.getPrimaryOutput();
        if (primary != null && primary.what() != null) {
            AEKey what = primary.what();
            ItemStack display = what instanceof AEItemKey itemKey
                    ? itemKey.toStack()
                    : WrappedGenericStack.wrap(what, 0L);
            if (!display.isEmpty()) {
                return display;
            }
        }
        return new ItemStack(stored.getItem());
    }
}
