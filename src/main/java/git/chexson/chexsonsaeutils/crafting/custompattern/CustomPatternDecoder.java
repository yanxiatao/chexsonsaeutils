package git.chexson.chexsonsaeutils.crafting.custompattern;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;

/**
 * 定制样板的 IPatternDetailsDecoder 实现。
 * <p>
 * 动机：AE2 的 PatternDetailsHelper 通过已注册的 decoder 列表把物品解码为
 * IPatternDetails。本类把 CustomPatternItem 接入该机制，使定制样板能被
 * PatternDetailsHelper.decodePattern 识别（供应器、样板终端等通用路径）。
 * 注册时机：Chexsonsaeutils 主类构造器（FMLCommonSetup enqueueWork）。
 */
public class CustomPatternDecoder implements IPatternDetailsDecoder {
    public static final CustomPatternDecoder INSTANCE = new CustomPatternDecoder();

    private CustomPatternDecoder() {
    }

    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        return stack.getItem() instanceof CustomPatternItem;
    }

    @Nullable
    @Override
    public IPatternDetails decodePattern(AEItemKey what, Level level) {
        if (level == null || what == null || !(what.getItem() instanceof CustomPatternItem item)) {
            return null;
        }
        return item.decode(what, level);
    }
}
