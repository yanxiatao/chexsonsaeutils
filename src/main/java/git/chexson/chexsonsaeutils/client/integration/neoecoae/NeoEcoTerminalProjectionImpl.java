package git.chexson.chexsonsaeutils.client.integration.neoecoae;

import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache;
import cn.dancingsnow.neoecoae.network.MapDelta;
import git.chexson.chexsonsaeutils.terminal.sync.TerminalInventoryView;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 渲染前提交完整投影；缓存修订变化也会触发，防止原生 long 更新覆盖真值。 */
public final class NeoEcoTerminalProjectionImpl implements NeoEcoTerminalProjection {
    private long frameRevision, cacheRevision = -1;
    @Override public void refresh(AbstractContainerMenu menu, TerminalInventoryView view) {
        if (view.revision() == 0 || (view.revision() == frameRevision
                && ExactAmountClientCache.revision() == cacheRevision)) return;
        var amounts = new HashMap<AEKey, ExactAmount>();
        if (view.active()) for (var entry : view.entries()) {
            if (entry.unverified() != null) {
                var existing = ExactAmountClientCache.get(menu.containerId, entry.key());
                if (existing != null) amounts.put(entry.key(), existing);
            } else amounts.put(entry.key(), entry.amount().unbounded()
                    ? ExactAmount.unbounded() : ExactAmount.finite(entry.amount().value()));
        }
        ExactAmountClientCache.apply(menu, true, new MapDelta<>(Map.copyOf(amounts), Set.of()));
        frameRevision = view.revision(); cacheRevision = ExactAmountClientCache.revision();
    }
}
