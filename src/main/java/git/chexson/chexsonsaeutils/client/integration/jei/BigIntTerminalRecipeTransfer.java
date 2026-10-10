package git.chexson.chexsonsaeutils.client.integration.jei;

import appeng.integration.modules.itemlists.CraftingHelper;
import git.chexson.chexsonsaeutils.menu.terminal.BigIntCraftingTerminalMenu;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.util.Optional;

/** 复用 AE2 配方填充包，由服务端执行权限检查和真实提取。 */
public final class BigIntTerminalRecipeTransfer implements
        IRecipeTransferHandler<BigIntCraftingTerminalMenu, RecipeHolder<CraftingRecipe>> {
    private final IRecipeTransferHandlerHelper helper;
    public BigIntTerminalRecipeTransfer(IRecipeTransferHandlerHelper helper) { this.helper = helper; }
    @Override public Class<BigIntCraftingTerminalMenu> getContainerClass() { return BigIntCraftingTerminalMenu.class; }
    @Override public Optional<MenuType<BigIntCraftingTerminalMenu>> getMenuType() {
        return Optional.of(BigIntCraftingTerminalMenu.TYPE);
    }
    @Override public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() { return RecipeTypes.CRAFTING; }
    @Override public IRecipeTransferError transferRecipe(BigIntCraftingTerminalMenu menu,
            RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player,
            boolean maxTransfer, boolean doTransfer) {
        if (!recipe.value().canCraftInDimensions(3, 3)) return helper.createUserErrorWithTooltip(
                Component.translatable("gui.ae2.RecipeTooLarge"));
        var ingredients = helper.getGuiSlotIndexToIngredientMap(recipe);
        var missing = menu.findMissingIngredients(ingredients);
        if (!doTransfer && missing.missingSlots().size() == ingredients.size() && !ingredients.isEmpty())
            return helper.createUserErrorWithTooltip(Component.translatable("gui.ae2.NoItems"));
        if (doTransfer) CraftingHelper.performTransfer(menu, recipe.id(), recipe.value(),
                AbstractContainerScreen.hasControlDown());
        return null;
    }
}
