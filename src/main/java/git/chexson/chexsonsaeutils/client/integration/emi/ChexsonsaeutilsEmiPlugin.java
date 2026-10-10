package git.chexson.chexsonsaeutils.client.integration.emi;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.EmiEntrypoint;
import appeng.integration.modules.emi.EmiUseCraftingRecipeHandler;
import git.chexson.chexsonsaeutils.menu.terminal.BigIntCraftingTerminalMenu;

/** 按新菜单类型注册 AE2 已有的合成配方转移实现。 */
@EmiEntrypoint
public final class ChexsonsaeutilsEmiPlugin implements EmiPlugin {
    @Override public void register(EmiRegistry registry) {
        registry.addRecipeHandler(BigIntCraftingTerminalMenu.TYPE,
                new EmiUseCraftingRecipeHandler<>(BigIntCraftingTerminalMenu.class));
    }
}
