package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineDelegatingPattern;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 虚拟缩放合成模式，将多次重复的 AE2 合成执行折叠为更大的逻辑批次。
 * <p>
 * 包装一个原生 {@code AECraftingPattern} 和一个整数乘数，
 * 在 High Capacity Crafting Machine 中将模式运行 multiplier 次，
 * 大幅减少调度开销。
 */
public interface FormalMachineScaledPattern extends IMolecularAssemblerSupportedPattern, FormalMachineDelegatingPattern {

    /**
     * 返回此模式的放大倍数。
     *
     * @return 正整数，表示一次性执行的重复次数
     */
    int multiplier();

    /**
     * 返回缩放后的合成网格副本。
     * <p>
     * 将原生模式的 3×3 合成网格按 {@link #multiplier()} 倍数放大，
     * 用于客户端渲染展示。
     *
     * @return 包含放大后物品的 9 格数组
     */
    ItemStack[] getScaledCraftingGridCopies();

    /**
     * 返回此模式的主模板物品。
     * <p>
     * 如果模式没有主模板（如仅消耗不产出模板），返回 null。
     *
     * @return 主模板 GenericStack，或 null
     */
    @Nullable
    GenericStack templatePrimary();

    /**
     * 返回模板的剩余物映射。
     * <p>
     * 键为模板物品的 AEItemKey，值为执行完成后应保留的剩余数量。
     * 用于正确处理模板回填逻辑。
     *
     * @return 剩余物映射，键不可为 null
     */
    Map<AEItemKey, Long> templateRemainders();
}