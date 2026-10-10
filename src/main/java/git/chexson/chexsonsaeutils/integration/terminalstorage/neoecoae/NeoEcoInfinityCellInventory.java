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
public final class NeoEcoInfinityCellInventory extends InfinityCellInventory implements ExactAmountSource, cn.dancingsnow.neoecoae.api.storage.ECOBigIntegerStorage {
    public NeoEcoInfinityCellInventory(ItemStack stack, @Nullable ISaveProvider host) {
        super(stack, host);
    }

    /** NeoEco 网络超过 long 的回网直接进入同一份盘账，不复制库存。 */
    @Override public java.math.BigInteger insertBigInteger(AEKey what, java.math.BigInteger amount,
            appeng.api.config.Actionable mode, appeng.api.networking.security.IActionSource source) {
        return insertExact(what, amount, mode, source);
    }

    /** 只读访问任意 AEKey 的有限实际库存；无限容量不代表无限供给。 */
    @Override public void neoecoae$visitExactAmounts(BiConsumer<AEKey, ExactAmount> visitor) {
        readExactSnapshot().amounts().forEach((key, amount) -> visitor.accept(key, ExactAmount.finite(amount)));
    }
}
