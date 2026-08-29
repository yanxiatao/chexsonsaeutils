package git.chexson.chexsonsaeutils.mixin.ae2.blockentity;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.blockentity.storage.IOPortBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import git.chexson.chexsonsaeutils.cell.MatterMassStore;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassItem;

/**
 * IO 端口空物质团销毁。
 * <p>
 * 动机：物质团注册为只读存储单元后，IO 端口（EMPTY 模式）会正常把内容物
 * 抽入网络，但抽空后 AE2 原生行为是把空单元移到输出槽——物质团是一次性
 * 物品，内容物转移完毕应直接消失。本 mixin 在 moveSlot 头部拦截：输入槽
 * 该格是内容物已空的物质团时原地销毁并视为移动成功。
 * <p>
 * 受物质团供应器特性门控（ChexsonsaeutilsMixinPlugin.MATTER_MASS_ONLY_MIXINS）。
 */
@Mixin(value = IOPortBlockEntity.class, remap = false)
public abstract class IOPortBlockEntityMatterMassMixin {

    @Final
    @Shadow
    private AppEngInternalInventory inputCells;

    @Inject(method = "moveSlot", at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$destroyEmptyMatterMass(int x, CallbackInfoReturnable<Boolean> cir) {
        var stack = this.inputCells.getStackInSlot(x);
        if (!(stack.getItem() instanceof MatterMassItem)) {
            return;
        }
        var uuid = MatterMassItem.getUuid(stack);
        if (uuid != null && !MatterMassStore.global().isEmpty(uuid)) {
            return;
        }
        this.inputCells.setItemDirect(x, ItemStack.EMPTY);
        cir.setReturnValue(true);
    }
}
