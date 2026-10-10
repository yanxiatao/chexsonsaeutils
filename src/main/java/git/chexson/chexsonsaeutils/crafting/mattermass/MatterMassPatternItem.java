package git.chexson.chexsonsaeutils.crafting.mattermass;

import java.util.List;
import java.util.UUID;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.EncodedPatternDecoder;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.EncodedPatternItem;

/**
 * 物质团样板物品（EncodedPatternItem 子类）。
 * <p>
 * 由物质团供应器在放入处理样板时自动生成（见
 * {@link git.chexson.chexsonsaeutils.helpers.mattermassprovider.MatterMassPatternProviderLogic}
 * 的自动转换）。可被 AE2 样板编码终端解析显示原始配方（经
 * PatternEncodingLogicMatterMassMixin），重新编码产物为标准处理样板。
 */
public class MatterMassPatternItem extends EncodedPatternItem<MatterMassPatternDetails> {

    public MatterMassPatternItem(Properties properties) {
        super(properties, new EncodedPatternDecoder<MatterMassPatternDetails>() {
            @Override
            public MatterMassPatternDetails decode(AEItemKey what, Level level) {
                return new MatterMassPatternDetails(what);
            }
        }, MatterMassPatternDetails::getInvalidPatternTooltip);
    }

    /** 物品注册工厂（样板不可堆叠，与 AE2 样板一致）。 */
    public static MatterMassPatternItem createItem() {
        return new MatterMassPatternItem(new Item.Properties().stacksTo(1));
    }

    /**
     * 转换工厂：由原始稀疏输入/输出构造物质团样板栈。
     *
     * @param sparseInputs  原始稀疏输入快照
     * @param sparseOutputs 原始稀疏输出快照（第一个输出决定物质团显示名）
     * @param massUuid      预分配的物质团 UUID
     */
    public static ItemStack createPatternStack(List<GenericStack> sparseInputs, List<GenericStack> sparseOutputs,
            UUID massUuid) {
        var stack = new ItemStack(
                git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent.MATTER_MASS_PATTERN_ITEM.get());
        MatterMassPatternDetails.encode(stack, sparseInputs, sparseOutputs, massUuid);
        return stack;
    }
}
