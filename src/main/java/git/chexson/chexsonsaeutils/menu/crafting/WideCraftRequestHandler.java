package git.chexson.chexsonsaeutils.menu.crafting;

import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.MenuOpener;
import git.chexson.chexsonsaeutils.crafting.request.CraftingRequestCompatibilityAccess;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningLevelRouter;
import git.chexson.chexsonsaeutils.network.crafting.WideCraftRequestPayload;
import git.chexson.chexsonsaeutils.terminal.storage.TerminalInventoryAccess;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import java.math.BigInteger;
import com.mojang.logging.LogUtils;

/** 服务端下单入口：验证当前菜单、真实目标和数量模式，再选择公开能力范围内的执行路径。 */
public final class WideCraftRequestHandler {
    private WideCraftRequestHandler() {}
    public static void handle(WideCraftRequestPayload request, Player player) {
        if (!(player instanceof ServerPlayer server) || !(player.containerMenu instanceof CraftAmountMenu menu)
                || menu.containerId != request.containerId() || !menu.stillValid(player)) return;
        var target = menu.getWhatToCraft();
        var locator = menu.getLocator();
        var host = menu.getTarget() instanceof appeng.api.networking.security.IActionHost actionHost ? actionHost : null;
        if (target == null || locator == null || host == null || host.getActionableNode() == null) return;
        var grid = host.getActionableNode().getGrid();
        var compatibility = CraftingRequestCompatibilityAccess.get();
        try {
            compatibility.finalTotal(menu, request.finalTotal());
            var amount = request.amount();
            if (request.missing() && !request.finalTotal()) {
                amount = missing(menu, server, amount);
                if (amount.signum() == 0) { menu.getHost().returnToMainMenu(player, menu); return; }
            }
            if (compatibility.submit(menu, amount, false, request.autoStart())) return;
            if (request.finalTotal()) {
                amount = missing(menu, server, amount);
                if (amount.signum() == 0) { menu.getHost().returnToMainMenu(player, menu); return; }
            }
            if (amount.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
                menu.confirm(amount.intValueExact(), false, request.autoStart());
                return;
            }
            if (amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 && !PlanningLevelRouter.shouldUseGraphPlanning(grid)) {
                server.sendSystemMessage(Component.translatable("gui.chexsonsaeutils.wide_request.requires_cpu"));
                return;
            }
            if (MenuOpener.open(WideCraftConfirmMenu.TYPE, server, locator)
                    && server.containerMenu instanceof WideCraftConfirmMenu confirm) {
                confirm.configure(target.what(), request.amount(), amount, request.finalTotal(), request.missing(), request.autoStart());
            }
        } catch (RuntimeException failure) {
            LogUtils.getLogger().error("精确下单失败：player={} target={} amount={}", player.getUUID(), target.what(), request.amount(), failure);
            server.sendSystemMessage(Component.translatable("gui.chexsonsaeutils.wide_request.failed", failure.getMessage()));
        }
    }
    private static BigInteger missing(CraftAmountMenu menu, ServerPlayer player, BigInteger amount) {
        var grid = ((appeng.api.networking.security.IActionHost) menu.getTarget()).getActionableNode().getGrid();
        var key = menu.getWhatToCraft().what();
        var snapshot = TerminalInventoryAccess.reader().read(grid, player.server);
        if (snapshot.unverified().containsKey(key)) throw new IllegalStateException("目标库存读数未确认，不能计算最终总数");
        var stock = snapshot.amounts().getOrDefault(key, StorageAmount.ZERO);
        return stock.unbounded() ? BigInteger.ZERO : amount.subtract(stock.value()).max(BigInteger.ZERO);
    }
}
