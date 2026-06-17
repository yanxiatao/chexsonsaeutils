package git.chexson.chexsonsaeutils.client.integration.jei;

import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingJeiImportRecipeTypeGuard;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JeiMachineRecipeTypeHintBridge {

    private static final int DEFAULT_TICKS = 20;
    private static final double CATALYST_CONFIDENCE = 0.75D;

    public List<JeiMachineRecipeTypeHint> collectHints(IJeiRuntime runtime) {
        if (runtime == null) {
            return List.of();
        }
        IRecipeManager recipeManager = runtime.getRecipeManager();
        if (recipeManager == null) {
            return List.of();
        }
        Map<Key, JeiMachineRecipeTypeHint> hints = new LinkedHashMap<>();
        recipeManager.createRecipeCategoryLookup()
                .get()
                .forEach(category -> collectCategoryHints(recipeManager, category, hints));
        return List.copyOf(hints.values());
    }

    public List<JeiMachineRecipeTypeHint> collectHintsForMachine(
            @Nullable IJeiRuntime runtime,
            @Nullable ResourceLocation machineItemId,
            @Nullable ResourceLocation machineBlockId
    ) {
        if (runtime == null || (machineItemId == null && machineBlockId == null)) {
            return List.of();
        }
        List<JeiMachineRecipeTypeHint> matches = new ArrayList<>();
        for (JeiMachineRecipeTypeHint hint : collectHints(runtime)) {
            if (hint == null || hint.machineId() == null) {
                continue;
            }
            if (hint.machineId().equals(machineBlockId) || hint.machineId().equals(machineItemId)) {
                matches.add(hint);
            }
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    private void collectCategoryHints(
            IRecipeManager recipeManager,
            IRecipeCategory<?> category,
            Map<Key, JeiMachineRecipeTypeHint> hints
    ) {
        if (category == null) {
            return;
        }
        ResourceLocation recipeTypeId = category.getRecipeType().getUid();
        if (!DirectProcessingJeiImportRecipeTypeGuard.isSupportedRecipeType(recipeTypeId)) {
            return;
        }
        List<ResourceLocation> machineIds = new ArrayList<>();
        recipeManager.createRecipeCatalystLookup(category.getRecipeType())
                .getItemStack()
                .map(this::machineId)
                .filter(id -> id != null && !machineIds.contains(id))
                .forEach(machineIds::add);
        for (ResourceLocation machineId : machineIds) {
            Key key = new Key(machineId, recipeTypeId);
            hints.putIfAbsent(key, new JeiMachineRecipeTypeHint(
                    machineId,
                    recipeTypeId,
                    DEFAULT_TICKS,
                    CATALYST_CONFIDENCE,
                    JeiMachineRecipeTypeHint.SOURCE
            ));
        }
    }

    private ResourceLocation machineId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (stack.getItem() instanceof BlockItem blockItem) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
            return blockId == null ? itemId : blockId;
        }
        return itemId;
    }

    private record Key(ResourceLocation machineId, ResourceLocation recipeTypeId) {
    }
}
