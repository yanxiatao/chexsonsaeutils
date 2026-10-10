package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingTreeNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link CraftingTreeNode} 的包私有字段，供 limitQty 专用批量路径判断输入
 * 是循环容器还是纯消耗，并读取其键/模板数量。
 */
@Mixin(value = CraftingTreeNode.class, remap = false)
public interface CraftingTreeNodeFastAccessor {

    @Accessor(value = "what", remap = false)
    AEKey chexsonsaeutils$getWhat();

    @Accessor(value = "amount", remap = false)
    long chexsonsaeutils$getAmount();

    @Accessor(value = "parentInput", remap = false)
    IPatternDetails.IInput chexsonsaeutils$getParentInput();

    @Accessor(value = "canEmit", remap = false)
    boolean chexsonsaeutils$isCanEmit();
}
