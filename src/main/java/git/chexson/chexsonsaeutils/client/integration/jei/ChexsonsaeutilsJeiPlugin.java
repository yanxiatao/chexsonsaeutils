package git.chexson.chexsonsaeutils.client.integration.jei;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

@JeiPlugin
public final class ChexsonsaeutilsJeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_UID =
            Objects.requireNonNull(ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":direct_processing_machine"));

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeHolder.setRuntime(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiRuntimeHolder.clearRuntime();
    }
}
