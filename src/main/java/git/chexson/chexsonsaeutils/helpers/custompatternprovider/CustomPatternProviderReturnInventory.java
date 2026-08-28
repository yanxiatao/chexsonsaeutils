package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeySlotFilter;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;

/**
 * 定制样板供应器的产物返回库存。
 * <p>
 * 与 AE2 {@link PatternProviderReturnInventory} 的差异是放开单格物品上限：AE2 基类
 * {@code GenericStackInv.getMaxAmount} 把物品钳成
 * {@code min(itemKey.getMaxStackSize(), capacity)}（普通物品恒 64、9 格恒 576），机器一次
 * 产出上百件时 {@code pullFromMachine} 只能分多轮搬，搬不下的余量回滚进机器槽把机器堵住。
 * 本类在总闸开启时把物品单格上限放到 {@code Integer.MAX_VALUE}——锁 int 而非 long，是为了让
 * {@code AEItemKey.toStack(int)}、{@code ConfigMenuInventory} 的 {@code Ints.saturatedCast}
 * 等下游 int 截断点全部无损。
 * <p>
 * 不覆写 {@code insert}/{@code setStack}：基类已按 {@code getMaxAmount} 钳制且允许同 key
 * 合并（{@code GenericStackInv.insert} 逐槽铺开），放开上限即单格吸收整批。
 * <p>
 * 已知代价：超过物品堆叠上限的槽位经 {@code ConfigMenuInventory} 变成 AE2
 * {@code WrappedGenericStack}，渲染正常（真实物品 + 数量标签），但 {@code AppEngSlot} 对
 * wrapper 禁拾取——由 {@link #withdrawForPlayer(int, int)} 配合菜单动作提供点击取回。
 */
public class CustomPatternProviderReturnInventory extends PatternProviderReturnInventory {

    /** 总闸开启时的单格物品上限。 */
    private static final long ITEM_OVERSTACK_MAX_AMOUNT = Integer.MAX_VALUE;

    private final BooleanSupplier overstackEnabled;

    public CustomPatternProviderReturnInventory(Runnable listener, @Nullable AEKeySlotFilter filter,
            BooleanSupplier overstackEnabled) {
        super(listener);
        this.setFilter(filter);
        this.overstackEnabled = overstackEnabled;
    }

    @Override
    public long getMaxAmount(AEKey key) {
        if (key instanceof AEItemKey && this.overstackEnabled.getAsBoolean()) {
            return ITEM_OVERSTACK_MAX_AMOUNT;
        }
        return super.getMaxAmount(key);
    }

    /**
     * 物品通道容量随总闸同步放开。
     * <p>
     * 动机：{@code ConfigMenuInventory.getSlotLimit} 与 AE2 对外暴露的通用库存适配器读的是
     * 容量而非 {@code getMaxAmount}，只放开后者会让 GUI 与外部管道仍按
     * {@code Item.ABSOLUTE_MAX_STACK_SIZE}（99）判容量。
     */
    @Override
    public long getCapacity(AEKeyType type) {
        if (type == AEKeyType.items() && this.overstackEnabled.getAsBoolean()) {
            return ITEM_OVERSTACK_MAX_AMOUNT;
        }
        return super.getCapacity(type);
    }

    /**
     * 从指定槽取回至多 {@code max} 个物品给玩家，一次不超过物品堆叠上限。
     * <p>
     * 不能走 {@code extract}：父类 {@code canExtract()} 恒 false（防管道从返回栏抽走），
     * 基类 {@code GenericStackInv.extract} 会直接返回 0。这里走 {@code getStack}/{@code setStack}
     * 公开入口，写回值恒不大于原值，因此总闸关闭后 {@code setStack} 的向上钳制不会吞掉任何存量。
     *
     * @param slot 返回栏槽位序号
     * @param max  本次最多取回的数量
     * @return 取回的真实物品栈；槽位为空或非法槽位时返回空栈
     */
    public ItemStack withdrawForPlayer(int slot, int max) {
        if (slot < 0 || slot >= this.size() || max <= 0) {
            return ItemStack.EMPTY;
        }
        var stack = this.getStack(slot);
        if (stack == null || !(stack.what() instanceof AEItemKey itemKey)) {
            return ItemStack.EMPTY;
        }
        int taken = (int) Math.min(max, Math.min(stack.amount(), itemKey.getMaxStackSize()));
        if (taken <= 0) {
            return ItemStack.EMPTY;
        }
        long remaining = stack.amount() - taken;
        this.setStack(slot, remaining > 0 ? new GenericStack(itemKey, remaining) : null);
        return itemKey.toStack(taken);
    }
}
