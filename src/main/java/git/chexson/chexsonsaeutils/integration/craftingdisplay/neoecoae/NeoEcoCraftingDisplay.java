package git.chexson.chexsonsaeutils.integration.craftingdisplay.neoecoae;

import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatusMenu;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.WeakHashMap;

/** 复用 NeoEco 监控表公开显示宿主，以独立精确帧补充自家 CPU。 */
public final class NeoEcoCraftingDisplay {
    private static final WeakHashMap<CraftingCPUMenu, Sent> SENT = new WeakHashMap<>();
    private NeoEcoCraftingDisplay() {}
    /** 仅安装 NeoEco 时由装配层调用，不改变原模组 CPU 的显示来源。 */
    public static void register() {
        ParallelCraftingCPUMenu.bindFactory(NeoEcoParallelCraftingCPUMenu::new);
    }
    public static void registerClient() {
        git.chexson.chexsonsaeutils.client.integration.neoecoae.NeoEcoCraftingDisplayClient.register();
    }
    /** 在现有菜单同步点采样；即使 long 投影没变化，精确值也会独立同步。 */
    public static void send(CraftingCPUMenu menu, ParallelCraftingCPU cpu, boolean force) {
        if (!(menu.getPlayer() instanceof ServerPlayer player)) return;
        int serial = menu instanceof CraftingStatusMenu nativeMenu ? nativeMenu.getSelectedCpuSerial()
                : menu instanceof ParallelCraftingCPUMenu ownMenu ? ownMenu.getSelectedCpuSerial() : -1;
        if (serial < 0) return;
        var prior = SENT.get(menu);
        if (!force && prior != null && prior.serial == serial && player.tickCount - prior.tick < 10) return;
        var amounts = cpu.exactMenuAmounts();
        SENT.put(menu, new Sent(serial, player.tickCount, amounts));
        if (force || prior == null || prior.serial != serial || !prior.amounts.equals(amounts))
            PacketDistributor.sendToPlayer(player, new ExactCraftingCpuPayload(menu.containerId, serial, amounts));
    }
    private record Sent(int serial, int tick, git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts amounts) {}
}
