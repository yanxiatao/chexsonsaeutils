package git.chexson.chexsonsaeutils.menu.implementations;

import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.me.crafting.CraftingCPUMenu;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import java.util.Objects;

public class ParallelCraftingCPUMenu extends CraftingCPUMenu {

    public static final MenuType<ParallelCraftingCPUMenu> TYPE = MenuTypeBuilder
            .create(ParallelCraftingCPUMenu::new, AE2ParallelCpuToolBlockEntity.class)
            .withMenuTitle(ignored -> Component.translatable("block.chexsonsaeutils.ae2_parallel_cpu_tool"))
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":ae2_parallel_cpu_tool_cpu")
            ));

    private final AE2ParallelCpuToolBlockEntity host;

    public ParallelCraftingCPUMenu(
            int id,
            Inventory playerInventory,
            AE2ParallelCpuToolBlockEntity host
    ) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        setCPU(host.getParallelCpuCluster().menuCpu());
    }

    public AE2ParallelCpuToolBlockEntity getParallelCpuHost() {
        return host;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            setCPU(host.getParallelCpuCluster().menuCpu());
        }
        super.broadcastChanges();
    }

    @Override
    public boolean allowConfiguration() {
        return true;
    }
}
