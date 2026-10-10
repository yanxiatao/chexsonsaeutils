package git.chexson.chexsonsaeutils.client.gui.patterntransmitter;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** 所有原生确认屏及其子类使用相同左侧预览入口，无需新增客户端 mixin。 */
@EventBusSubscriber(modid = "chexsonsaeutils", value = Dist.CLIENT)
public final class RemotePreviewClient {
    private RemotePreviewClient() {}
    public static void bind() {
        RemotePreviewPayload.bindClient(value -> {
            if (Minecraft.getInstance().screen instanceof RemotePreviewScreen screen) screen.accept(value);
        });
    }
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CraftConfirmScreen screen)) return;
        int width = Math.max(20, Math.min(70, screen.getGuiLeft() - 4));
        event.addListener(Button.builder(Component.literal(width >= 60 ? "远程预览" : "远"), button -> Minecraft.getInstance().setScreen(new RemotePreviewScreen(screen, screen.getMenu().containerId)))
                .bounds(Math.max(0, screen.getGuiLeft() - width - 4), screen.getGuiTop() + 120, width, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("查看本次远程需求、来源材料与 CPU"))).build());
    }
}
