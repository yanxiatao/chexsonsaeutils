package git.chexson.chexsonsaeutils.crafting.mattermass;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;

/**
 * 物质团样板的 IPatternDetailsDecoder 实现。
 * <p>
 * 使物质团样板被 PatternDetailsHelper.decodePattern 全局识别：编码终端回填
 * （经 mixin）、样板访问终端显示、普通供应器识别均走此路径。
 * 注册受特性门控（Chexsonsaeutils common setup）。
 */
public class MatterMassPatternDecoder implements IPatternDetailsDecoder {
    public static final MatterMassPatternDecoder INSTANCE = new MatterMassPatternDecoder();

    private MatterMassPatternDecoder() {
    }

    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        return stack.getItem() instanceof MatterMassPatternItem;
    }

    @Nullable
    @Override
    public IPatternDetails decodePattern(AEItemKey what, Level level) {
        if (level == null || what == null || !(what.getItem() instanceof MatterMassPatternItem item)) {
            return null;
        }
        return item.decode(what, level);
    }
}
