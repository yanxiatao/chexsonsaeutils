package git.chexson.chexsonsaeutils.client.integration.neoecoae;

import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingStatusMenu;
import cn.dancingsnow.neoecoae.api.me.menu.ECOBigOrderStatusHost;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.network.MapDelta;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/** 精确帧按菜单实例和 CPU 序号投影，避免 NeoEco 的空采样包覆盖自家 CPU 真值。 */
public final class NeoEcoCraftingDisplayClient {
    private static AbstractContainerMenu owner;
    private static Projection projection;
    private NeoEcoCraftingDisplayClient() {}
    public static void register() {
        ExactCraftingCpuPayload.bind((menu, payload) -> {
            if (!(menu instanceof ECOBigOrderStatusHost) || selected(menu) != payload.cpuSerial()) return;
            owner = menu;
            projection = new Projection(payload.cpuSerial(), delta(payload.amounts().stored()),
                    delta(payload.amounts().active()), delta(payload.amounts().pending()));
            apply();
        });
        NeoForge.EVENT_BUS.addListener(NeoEcoCraftingDisplayClient::render);
    }
    private static void render(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof appeng.client.gui.AEBaseScreen<?> screen) || screen.getMenu() != owner) {
            owner = null; projection = null; return;
        }
        apply();
    }
    private static void apply() {
        if (projection != null && owner instanceof ECOBigOrderStatusHost host && selected(owner) == projection.serial)
            host.neoecoae$applyExactAmounts(projection.serial, true, projection.stored, projection.active, projection.pending);
    }
    private static int selected(AbstractContainerMenu menu) {
        return menu instanceof CraftingStatusMenu nativeMenu ? nativeMenu.getSelectedCpuSerial()
                : menu instanceof ParallelCraftingCPUMenu ownMenu ? ownMenu.getSelectedCpuSerial() : -1;
    }
    private static MapDelta<AEKey, ExactAmount> delta(Map<AEKey, BigInteger> values) {
        var map = new java.util.HashMap<AEKey, ExactAmount>();
        values.forEach((key, value) -> map.put(key, ExactAmount.finite(value)));
        return new MapDelta<>(Map.copyOf(map), Set.of());
    }
    private record Projection(int serial, MapDelta<AEKey, ExactAmount> stored,
            MapDelta<AEKey, ExactAmount> active, MapDelta<AEKey, ExactAmount> pending) {}
}
