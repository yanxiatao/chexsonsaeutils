package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

/**
 * 染色样板递归 KeyCounter NBT 编解码。
 *
 * 用 AE2 自身的 generic key tag 格式保存内部催化物状态。
 */
public final class DyeablePatternRecursiveCounterNbt {
    private DyeablePatternRecursiveCounterNbt() {
    }

    /**
     * 把 counter 写成 AEKey 通用 tag 列表。
     */
    public static ListTag write(@Nullable KeyCounter counter) {
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        if (counter != null) {
            for (var entry : counter) {
                if (entry.getKey() != null && entry.getLongValue() > 0L) {
                    inventory.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                }
            }
        }
        return inventory.writeToNBT();
    }

    /**
     * 从 AEKey 通用 tag 列表读取 counter。
     */
    public static KeyCounter read(@Nullable ListTag data) {
        KeyCounter counter = new KeyCounter();
        if (data == null) {
            return counter;
        }
        for (int index = 0; index < data.size(); index++) {
            var compound = data.getCompound(index);
            AEKey key = AEKey.fromTagGeneric(compound);
            long amount = compound.getLong("#");
            if (key != null && amount > 0L) {
                counter.add(key, amount);
            }
        }
        return counter;
    }
}
