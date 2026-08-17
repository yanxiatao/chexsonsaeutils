package git.chexson.chexsonsaeutils.integration.extendedae;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * ExtendedAE 集成（需求 5 阶段 5b）。
 * <p>
 * 动机：扩容「框架样板供应器」的消耗物是 ExtendedAE 的「扩展样板供应器」方块物品
 * （注册名 ex_pattern_provider，物品 ID extendedae:ex_pattern_provider，见
 * reference-sources/extendedae EAESingletons.java:301）。本类提供运行时物品匹配，
 * 不编译期依赖 ExtendedAE（未安装时返回 AIR，匹配恒 false）。
 * <p>
 * 使用方：扩容 GUI 输入槽过滤器（FramePatternUpgradeMenu）与物质聚合器扩容 mixin
 * （CondenserBlockEntityCondenseItemHandlerMixin）。
 */
public final class ExtendedAeCompat {

    /** ExtendedAE 扩展样板供应器方块物品的注册 ID。 */
    private static final ResourceLocation EX_PATTERN_PROVIDER_ID =
            ResourceLocation.parse("extendedae:ex_pattern_provider");

    /** 惰性缓存：null = 尚未查询（首次调用时查注册表；未装 ExtendedAE 时为 AIR）。 */
    private static Item exPatternProviderItem;

    private ExtendedAeCompat() {
    }

    /**
     * @return ExtendedAE 扩展样板供应器物品（未安装时返回 AIR）
     */
    public static Item getExPatternProviderItem() {
        if (exPatternProviderItem == null) {
            exPatternProviderItem = BuiltInRegistries.ITEM.get(EX_PATTERN_PROVIDER_ID);
        }
        return exPatternProviderItem;
    }

    /**
     * @param stack 待匹配物品
     * @return true 当物品是 ExtendedAE 扩展样板供应器（空栈/未安装恒 false）
     */
    public static boolean isExPatternProvider(ItemStack stack) {
        return !stack.isEmpty() && stack.is(getExPatternProviderItem());
    }
}