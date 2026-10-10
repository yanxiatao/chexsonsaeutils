package git.chexson.chexsonsaeutils.integration.kubejs;

import com.google.gson.JsonElement;
import dev.latvian.mods.kubejs.core.RecipeManagerKJS;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import dev.latvian.mods.kubejs.recipe.component.ItemStackComponent;
import dev.latvian.mods.kubejs.recipe.component.FluidStackComponent;
import dev.latvian.mods.kubejs.recipe.component.SizedIngredientComponent;
import dev.latvian.mods.kubejs.recipe.component.SizedFluidIngredientComponent;
import dev.latvian.mods.kubejs.recipe.component.StringComponent;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchema;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRecipe;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRulesRegistry;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Map;

/** 可选 KubeJS 插件；仅由 KubeJS 的插件发现入口加载，核心层不会加载脚本引擎。 */
public final class ChexsonsaeutilsKubeJSPlugin implements KubeJSPlugin {
    public static final EventGroup GROUP = EventGroup.of("ChexsonsaeutilsEvents");
    public static final EventHandler DIRECT_PROCESSING = GROUP.server("directProcessing", () -> DirectProcessingKubeEvent.class)
            .exceptionHandler((event, handler, error) -> {
                ((DirectProcessingKubeEvent) event).markFailed();
                return error;
            });

    @Override public void registerEvents(EventGroupRegistry registry) { registry.register(GROUP); }
    @Override public void beforeRecipeLoading(RecipesKubeEvent event, RecipeManagerKJS manager,
                                             Map<ResourceLocation, JsonElement> recipes) {
        var configuration = new DirectProcessingKubeEvent();
        DIRECT_PROCESSING.post(configuration);
        DirectProcessingPackRulesRegistry.replace(configuration.finish());
    }
    @Override public void registerRecipeSchemas(RecipeSchemaRegistry registry) {
        var machines = StringComponent.ID.instance().asList().otherKey("machines");
        var itemOutputs = ItemStackComponent.ITEM_STACK.instance().asList().outputKey("item_outputs").optional(List.of());
        var itemInputs = SizedIngredientComponent.SIZED_INGREDIENT.instance().asList().inputKey("item_inputs").optional(List.of());
        var fluidOutputs = FluidStackComponent.FLUID_STACK.instance().asList().outputKey("fluid_outputs").optional(List.of());
        var fluidInputs = SizedFluidIngredientComponent.NESTED.instance().asList().inputKey("fluid_inputs").optional(List.of());
        registry.register(DirectProcessingPackRecipe.TYPE_ID,
                new RecipeSchema(machines, itemOutputs, itemInputs, fluidOutputs, fluidInputs)
                        .constructor(itemOutputs, itemInputs, machines).constructor(machines));
    }
}
