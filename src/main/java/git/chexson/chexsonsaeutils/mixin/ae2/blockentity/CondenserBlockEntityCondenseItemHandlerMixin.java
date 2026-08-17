package git.chexson.chexsonsaeutils.mixin.ae2.blockentity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.blockentity.misc.CondenserBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.integration.extendedae.ExtendedAeCompat;
import git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 物质聚合器扩容 mixin（需求 5 阶段 5b，方式 B）。
 * <p>
 * 动机：玩家在真物质聚合器中扩容框架样板供应器——存储元件槽放框架供应器物品
 * （IStorageComponent，容量 = 页数 x 1024 字节），输入槽（TRASH/void 槽）放
 * ExtendedAE 扩展样板供应器物品（extendedae:ex_pattern_provider）→ 消耗 1 个
 * → 框架供应器物品 FRAME_PATTERN_PAGES 组件 +1。
 * <p>
 * 注入点：CondenseItemHandler（CondenserBlockEntity 私有内部类，void 槽）的
 * insertItem（管道/漏斗/ME 输出路径）与 setItemDirect（玩家 GUI 拖放路径，
 * AppEngSlot.set → inventory.setItemDirect）。两者是物品进入物质聚合器的唯一入口。
 * <p>
 * 行为矩阵：
 * <ul>
 *   <li>存储槽非框架供应器 / 输入物品非扩展样板供应器 → 不拦截（原版 condense）。</li>
 *   <li>匹配且页数未达上限 → 消耗 1 个 + 页数 +1；insertItem 返回剩余物品（回管道），
 *       setItemDirect 剩余物品掉落在聚合器位置（不丢数据）。</li>
 *   <li>匹配但达上限 → 拒绝插入（insertItem 返回原栈 / setItemDirect 取消），
 *       物品不消耗（刻意取舍：达上限时扩展物品不被 condense 成能量）。</li>
 * </ul>
 * <p>
 * S1 补充：扩容路径刻意绕过 canAddOutput 检查（扩容不依赖输出槽——页数 +1 直接写
 * 物品组件，不产生物质球/奇点输出）；原版 canAddOutput 检查仍覆盖管道 simulate
 * 路径（simulate 不拦截，走原版判定）与玩家拖放 mayPlace 路径（RestrictedInputSlot
 * mayPlace → inventory.isItemValid → canAddOutput），两条路径的插入前置校验不受影响。
 */
@Mixin(targets = "appeng.blockentity.misc.CondenserBlockEntity$CondenseItemHandler", remap = false)
public abstract class CondenserBlockEntityCondenseItemHandlerMixin {

    /** 内部类持有的外部实例引用（javac 生成字段 this$0）。 */
    @Shadow
    @Final
    private CondenserBlockEntity this$0;

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$tryExpandInsert(
            int slot, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (simulate) {
            // 模拟路径不消耗：原版 simulate 返回 EMPTY（可全消耗），扩容路径语义一致
            return;
        }
        var result = tryExpand(stack);
        if (result == ExpandResult.CONSUMED) {
            ItemStack remainder = stack.copy();
            remainder.shrink(1);
            cir.setReturnValue(remainder);
        } else if (result == ExpandResult.REJECTED) {
            // 达上限：拒绝插入，物品留在管道/漏斗中
            cir.setReturnValue(stack);
        }
    }

    @Inject(method = "setItemDirect", at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$tryExpandSetDirect(int slotIndex, ItemStack stack, CallbackInfo ci) {
        var result = tryExpand(stack);
        if (result == ExpandResult.CONSUMED) {
            ItemStack remainder = stack.copy();
            remainder.shrink(1);
            if (!remainder.isEmpty()) {
                // 玩家拖放路径无返回值通道：剩余物品掉落在聚合器位置（不丢数据）
                Block.popResource(this$0.getLevel(), this$0.getBlockPos(), remainder);
            }
            ci.cancel();
        } else if (result == ExpandResult.REJECTED) {
            // 达上限：取消原版消耗，物品留在鼠标上
            ci.cancel();
        }
    }

    private enum ExpandResult {
        /** 不匹配：走原版 condense 行为。 */
        NONE,
        /** 匹配且可扩容：已消耗 1 个并 +1 页。 */
        CONSUMED,
        /** 匹配但达上限：拒绝消耗。 */
        REJECTED
    }

    /**
     * 扩容判定与执行（存储槽 = combinedInv 索引 2，见 CondenserBlockEntity.getInternalInventory）。
     *
     * @return 处理结果（NONE 表示调用方应走原版行为）
     */
    private ExpandResult tryExpand(ItemStack stack) {
        var condenser = this$0;
        var storageStack = condenser.getInternalInventory().getStackInSlot(2);
        if (!(storageStack.getItem() instanceof FramePatternProviderItem)) {
            return ExpandResult.NONE;
        }
        if (!ExtendedAeCompat.isExPatternProvider(stack)) {
            return ExpandResult.NONE;
        }
        int pages = Math.max(1, storageStack.getOrDefault(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), 1));
        if (pages >= FramePatternProviderBlockEntity.maxPages()) {
            return ExpandResult.REJECTED;
        }
        storageStack.set(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), pages + 1);
        condenser.saveChanges();
        return ExpandResult.CONSUMED;
    }
}