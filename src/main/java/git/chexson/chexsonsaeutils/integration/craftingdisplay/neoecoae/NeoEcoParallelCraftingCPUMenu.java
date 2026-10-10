package git.chexson.chexsonsaeutils.integration.craftingdisplay.neoecoae;

import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.api.me.menu.ECOBigOrderStatusHost;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOBigOrderProgress;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.network.MapDelta;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import net.minecraft.world.entity.player.Inventory;
import java.math.BigInteger;
import java.util.Map;

/** 自家监控菜单使用自身的 CPU 序号，避免 NeoEco 的原生菜单类型检查忽略读数。 */
public final class NeoEcoParallelCraftingCPUMenu extends ParallelCraftingCPUMenu implements ECOBigOrderStatusHost {
    private int exactSerial = -1, progressSerial = -1;
    private Map<AEKey, ExactAmount> stored = Map.of(), active = Map.of(), pending = Map.of();
    private ECOBigOrderProgress progress;
    public NeoEcoParallelCraftingCPUMenu(int id, Inventory inventory, AE2ParallelCpuToolBlockEntity host) {
        super(id, inventory, host);
    }
    @Override public void neoecoae$applyExactAmounts(int serial, boolean full, MapDelta<AEKey, ExactAmount> storage,
            MapDelta<AEKey, ExactAmount> crafting, MapDelta<AEKey, ExactAmount> scheduled) {
        if (serial != getSelectedCpuSerial() || (!full && serial != exactSerial)) return;
        stored = storage.apply(full ? Map.of() : stored);
        active = crafting.apply(full ? Map.of() : active);
        pending = scheduled.apply(full ? Map.of() : pending);
        exactSerial = serial;
    }
    private BigInteger value(Map<AEKey, ExactAmount> amounts, AEKey key) {
        var amount = exactSerial == getSelectedCpuSerial() ? amounts.get(key) : null;
        return amount == null ? null : amount.value();
    }
    @Override public BigInteger neoecoae$getExactStored(AEKey key) { return value(stored, key); }
    @Override public BigInteger neoecoae$getExactActive(AEKey key) { return value(active, key); }
    @Override public BigInteger neoecoae$getExactPending(AEKey key) { return value(pending, key); }
    @Override public void neoecoae$setBigOrderProgress(int serial, ECOBigOrderProgress value) {
        progressSerial = serial; progress = value;
    }
    @Override public ECOBigOrderProgress neoecoae$getBigOrderProgress() {
        return progressSerial == getSelectedCpuSerial() ? progress : null;
    }
    @Override public void neoecoae$clearBigOrderProgress() { progressSerial = -1; progress = null; }
}
