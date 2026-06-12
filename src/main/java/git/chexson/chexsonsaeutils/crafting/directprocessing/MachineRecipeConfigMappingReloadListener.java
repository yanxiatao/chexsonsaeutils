package git.chexson.chexsonsaeutils.crafting.directprocessing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeType;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class MachineRecipeConfigMappingReloadListener implements PreparableReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "chexsonsaeutils/direct_processing_machines";

    @Override
    public CompletableFuture<Void> reload(
            PreparationBarrier barrier,
            ResourceManager resourceManager,
            ProfilerFiller preparationsProfiler,
            ProfilerFiller reloadProfiler,
            Executor backgroundExecutor,
            Executor gameExecutor
    ) {
        return CompletableFuture
                .supplyAsync(() -> loadMappings(resourceManager), backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenAcceptAsync(parsedMappings -> {
                    MachineRecipeConfigMappingRegistry.instance().replaceParsedMappings(parsedMappings);
                    MachineRecipeReloadTracker.markRecipeReloaded();
                }, gameExecutor);
    }

    private static List<MachineRecipeConfigMappingRegistry.ParsedConfigMapping> loadMappings(
            ResourceManager resourceManager
    ) {
        if (resourceManager == null) {
            return List.of();
        }
        List<MachineRecipeConfigMappingRegistry.ParsedConfigMapping> mappings = new ArrayList<>();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(
                DIRECTORY,
                location -> location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            readMappingResource(entry.getKey(), entry.getValue(), mappings);
        }
        return List.copyOf(mappings);
    }

    private static void readMappingResource(
            ResourceLocation resourceId,
            Resource resource,
            List<MachineRecipeConfigMappingRegistry.ParsedConfigMapping> mappings
    ) {
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            if (object.has("enabled") && !object.get("enabled").getAsBoolean()) {
                return;
            }
            ResourceLocation machineId = readResourceLocation(object, "machine_item");
            if (machineId == null) {
                machineId = readResourceLocation(object, "machine_block");
            }
            if (machineId == null || !object.has("recipe_types")) {
                LOGGER.warn("Ignoring malformed AE direct processing machine mapping {}", resourceId);
                return;
            }
            int defaultTicks = object.has("default_ticks")
                    ? Math.max(1, object.get("default_ticks").getAsInt())
                    : 20;
            JsonArray recipeTypes = object.getAsJsonArray("recipe_types");
            for (JsonElement recipeTypeElement : recipeTypes) {
                addRecipeTypeMapping(resourceId, machineId, recipeTypeElement, defaultTicks, mappings);
            }
        } catch (RuntimeException | java.io.IOException exception) {
            LOGGER.warn("Failed to read AE direct processing machine mapping {}", resourceId, exception);
        }
    }

    private static void addRecipeTypeMapping(
            ResourceLocation resourceId,
            ResourceLocation machineId,
            JsonElement recipeTypeElement,
            int defaultTicks,
            List<MachineRecipeConfigMappingRegistry.ParsedConfigMapping> mappings
    ) {
        if (recipeTypeElement == null || !recipeTypeElement.isJsonPrimitive()) {
            return;
        }
        ResourceLocation recipeTypeId = ResourceLocation.tryParse(recipeTypeElement.getAsString());
        if (recipeTypeId == null) {
            LOGGER.warn("Ignoring invalid recipe type in AE direct processing mapping {}", resourceId);
            return;
        }
        RecipeType<?> recipeType = BuiltInRegistries.RECIPE_TYPE.get(recipeTypeId);
        if (recipeType == null) {
            LOGGER.warn("Ignoring unknown recipe type {} in AE direct processing mapping {}", recipeTypeId, resourceId);
            return;
        }
        mappings.add(new MachineRecipeConfigMappingRegistry.ParsedConfigMapping(
                machineId,
                recipeType,
                defaultTicks
        ));
    }

    private static ResourceLocation readResourceLocation(JsonObject object, String memberName) {
        if (!object.has(memberName) || !object.get(memberName).isJsonPrimitive()) {
            return null;
        }
        return ResourceLocation.tryParse(object.get(memberName).getAsString());
    }
}
