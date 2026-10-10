package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.stacks.AEItemKey;
import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.function.Consumer;

/** 每个标记槽对应一个真实缓存槽；保留工具组件变化，配置换物时立即退回旧缓存。 */
public final class SimulationBuffer {
    public static final int SIZE = 63;
    private final AppEngInternalInventory inventory = new AppEngInternalInventory(SIZE);
    private final AEItemKey[] origins = new AEItemKey[SIZE];
    /** 获取缓存，数量和耐久均来自实际物品。 */
    public ItemStack get(int slot) { return inventory.getStackInSlot(slot); }
    /** 根据原始标记判断缓存归属；工具磨损不会改变归属。 */
    public boolean belongs(int slot, AEItemKey key) { return key != null && key.equals(origins[slot]); }
    /** 转移缓存所有权到模拟玩家，避免交互期间存在两份真实物品。 */
    public ItemStack take(int slot) {
        var stack = get(slot);
        inventory.setItemDirect(slot, ItemStack.EMPTY);
        return stack;
    }
    /** 设置缓存并记录其标记来源。 */
    public void set(int slot, AEItemKey origin, ItemStack stack) {
        origins[slot] = stack.isEmpty() ? null : origin;
        inventory.setItemDirect(slot, stack);
    }
    /** 将合成产物直接补入对应缓存，模拟调用不修改库存。 */
    public long accept(int slot, AEItemKey key, int desired, long amount, appeng.api.config.Actionable mode) {
        return accept(slot,key,key,desired,amount,mode);
    }
    /** 模糊合成交付分别保留原标记与真实变体，不能合并不同组件的堆叠。 */
    public long accept(int slot, AEItemKey origin, AEItemKey key, int desired, long amount, appeng.api.config.Actionable mode) {
        var stack = get(slot);
        if (!stack.isEmpty() && (!belongs(slot,origin) || !key.matches(stack))) return 0;
        int space = Math.max(0,Math.min(desired,key.toStack().getMaxStackSize()) - stack.getCount());
        int accepted = (int) Math.min(amount,space);
        if (accepted > 0 && mode == appeng.api.config.Actionable.MODULATE) {
            if (stack.isEmpty()) set(slot,origin,key.toStack(accepted));
            else stack.grow(accepted);
        }
        return accepted;
    }
    /** 变更配置或移除容量卡时，退回不再需要的物品和超出目标数量的部分。 */
    public void reconcile(int slot, AEItemKey key, int desired, Consumer<ItemStack> returnItem) {
        var stack = get(slot);
        if (stack.isEmpty()) return;
        if (!belongs(slot, key) || desired == 0) {
            returnItem.accept(take(slot));
            origins[slot] = null;
        } else if (stack.getCount() > desired) {
            returnItem.accept(stack.split(stack.getCount() - desired));
        }
    }
    /** 归还全部缓存，不遗留隐形物品。 */
    public void drain(Consumer<ItemStack> returnItem) {
        for (int i = 0; i < SIZE; i++) {
            if (!get(i).isEmpty()) returnItem.accept(take(i));
            origins[i] = null;
        }
    }
    /** 拆卸时只复制掉落清单，随后由 AE2 clearContent 清空。 */
    public void addDrops(List<ItemStack> drops) {
        for (var stack : inventory) if (!stack.isEmpty()) drops.add(stack.copy());
    }
    /** AE2 已生成掉落后清除内容，防止二次掉落。 */
    public void clear() {
        inventory.clear();
        java.util.Arrays.fill(origins, null);
    }
    /** 保存真实缓存和原始标记，重启后仍能继续使用受损工具。 */
    public void write(CompoundTag tag, HolderLookup.Provider registries) {
        inventory.writeToNBT(tag, "buffer", registries);
        var originTag = new CompoundTag();
        for (int i = 0; i < SIZE; i++) if (origins[i] != null) originTag.put(Integer.toString(i), origins[i].toTag(registries));
        tag.put("origins", originTag);
    }
    /** 恢复缓存。 */
    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        clear();
        inventory.readFromNBT(tag, "buffer", registries);
        var originTag = tag.getCompound("origins");
        for (int i = 0; i < SIZE; i++) origins[i] = originTag.contains(Integer.toString(i))
                ? AEItemKey.fromTag(registries, originTag.getCompound(Integer.toString(i))) : null;
    }
}
