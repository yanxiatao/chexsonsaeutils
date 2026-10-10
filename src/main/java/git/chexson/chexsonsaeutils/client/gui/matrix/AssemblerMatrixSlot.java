package git.chexson.chexsonsaeutils.client.gui.matrix;

import appeng.crafting.pattern.EncodedPatternItem;
import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.me.common.ClientReadOnlySlot;

/**
 * 装配矩阵的虚拟格：ExtendedAE {@code AssemblerMatrixSlot} 的移植件。
 *
 * <p>1.21.1 的 {@code Slot.x/y} 只能在构造时给定，且没有 {@code getDisplayStack}，
 * 所以这里改挂 AE2 自带的只读客户端槽（它的 {@code mayPlace/set/getMaxStackSize/
 * remove/mayPickup} 五个 final 兜底与移植件逐条相同），渲染用的产出物换算挪到
 * {@link #getRenderStack()}，由界面的 {@code renderSlot} 调用。</p>
 */
public class AssemblerMatrixSlot extends ClientReadOnlySlot {

    private final AppEngInternalInventory machineInv;
    private final int machineInvSlot;
    private final int offset;

    public AssemblerMatrixSlot(
            AppEngInternalInventory machineInv,
            int machineInvSlot,
            int offset,
            int x,
            int y
    ) {
        super(x, y);
        this.machineInv = machineInv;
        this.machineInvSlot = machineInvSlot;
        this.offset = offset;
    }

    /** 行内下标换算回库存槽号，语义与移植件的 {@code getActuallySlot} 一致。 */
    public int getActuallySlot() {
        return this.machineInvSlot + this.offset;
    }

    @Override
    public ItemStack getItem() {
        return this.machineInv.getStackInSlot(this.machineInvSlot);
    }

    @Override
    public boolean hasItem() {
        return !this.getItem().isEmpty();
    }

    /** 下发的是样板本体时画它的产出物，对应移植件 {@code getDisplayStack} 的换算。 */
    public ItemStack getRenderStack() {
        final ItemStack is = this.getItem();
        if (!is.isEmpty() && is.getItem() instanceof EncodedPatternItem<?> iep) {
            final ItemStack out = iep.getOutput(is);
            if (!out.isEmpty()) {
                return out;
            }
        }
        return is;
    }
}
