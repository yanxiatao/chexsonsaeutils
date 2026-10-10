package git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae;

import appeng.api.stacks.AEKey;
import appeng.api.storage.cells.ISaveProvider;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountSource;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import java.util.function.BiConsumer;

/** 可选装配的无限盘，向 NeoEco 原生终端采集链提供同一份原子精确快照。 */
public final class NeoEcoInfinityCellInventory extends InfinityCellInventory implements ExactAmountSource {
    public NeoEcoInfinityCellInventory(ItemStack stack, @Nullable ISaveProvider host) {
        super(stack, host);
    }

    /** 只读访问任意 AEKey 的有限实际库存；无限容量不代表无限供给。 */
    @Override public void neoecoae$visitExactAmounts(BiConsumer<AEKey, ExactAmount> visitor) {
        readExactSnapshot().amounts().forEach((key, amount) -> visitor.accept(key, ExactAmount.finite(amount)));
    }
}
