package git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism;

import git.chexson.chexsonsaeutils.crafting.directprocessing.appmek.AppliedMekanisticsChemicalKeyBridge;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.MekanismRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MekanismDirectProcessingSupport {

    public static final String MOD_ID = "mekanism";
    public static final String APPLIED_MEKANISTICS_MOD_ID = "appmek";
    public static final ResourceLocation CRUSHER_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "crusher");
    public static final ResourceLocation ENRICHMENT_CHAMBER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "enrichment_chamber");
    public static final ResourceLocation ENERGIZED_SMELTER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "energized_smelter");
    public static final ResourceLocation CHEMICAL_OXIDIZER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "chemical_oxidizer");
    public static final ResourceLocation ISOTOPIC_CENTRIFUGE_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "isotopic_centrifuge");
    public static final ResourceLocation SOLAR_NEUTRON_ACTIVATOR_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "solar_neutron_activator");
    public static final ResourceLocation OSMIUM_COMPRESSOR_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "osmium_compressor");
    public static final ResourceLocation PURIFICATION_CHAMBER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "purification_chamber");
    public static final ResourceLocation CHEMICAL_INJECTION_CHAMBER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "chemical_injection_chamber");
    public static final ResourceLocation METALLURGIC_INFUSER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "metallurgic_infuser");
    public static final ResourceLocation CHEMICAL_WASHER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "chemical_washer");
    public static final ResourceLocation CHEMICAL_INFUSER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "chemical_infuser");
    public static final ResourceLocation PIGMENT_EXTRACTOR_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "pigment_extractor");
    public static final ResourceLocation PIGMENT_MIXER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "pigment_mixer");
    public static final ResourceLocation PRESSURIZED_REACTION_CHAMBER_ID =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "pressurized_reaction_chamber");
    public static final ResourceLocation CRUSHING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_CRUSHING;
    public static final ResourceLocation ENRICHING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_ENRICHING;
    public static final ResourceLocation SMELTING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_SMELTING;
    public static final ResourceLocation OXIDIZING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_OXIDIZING;
    public static final ResourceLocation CENTRIFUGING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_CENTRIFUGING;
    public static final ResourceLocation ACTIVATING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_ACTIVATING;
    public static final ResourceLocation COMPRESSING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_COMPRESSING;
    public static final ResourceLocation PURIFYING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_PURIFYING;
    public static final ResourceLocation INJECTING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_INJECTING;
    public static final ResourceLocation METALLURGIC_INFUSING_RECIPE_TYPE_ID =
            MekanismRecipeTypes.NAME_METALLURGIC_INFUSING;
    public static final ResourceLocation WASHING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_WASHING;
    public static final ResourceLocation CHEMICAL_INFUSING_RECIPE_TYPE_ID =
            MekanismRecipeTypes.NAME_CHEMICAL_INFUSING;
    public static final ResourceLocation PIGMENT_EXTRACTING_RECIPE_TYPE_ID =
            MekanismRecipeTypes.NAME_PIGMENT_EXTRACTING;
    public static final ResourceLocation PIGMENT_MIXING_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_PIGMENT_MIXING;
    public static final ResourceLocation REACTION_RECIPE_TYPE_ID = MekanismRecipeTypes.NAME_REACTION;
    public static final int DEFAULT_TICKS = 20;
    private static final List<String> SUPPORTED_FACTORY_TIERS = List.of(
            "basic_",
            "advanced_",
            "elite_",
            "ultimate_"
    );

    private static final Map<ResourceLocation, RecipeType<?>> ITEM_ONLY_RECIPE_TYPES = Map.of(
            CRUSHER_ID, MekanismRecipeTypes.TYPE_CRUSHING.value(),
            ENRICHMENT_CHAMBER_ID, MekanismRecipeTypes.TYPE_ENRICHING.value(),
            ENERGIZED_SMELTER_ID, MekanismRecipeTypes.TYPE_SMELTING.value()
    );
    private static final Map<ResourceLocation, RecipeType<?>> KEYED_RECIPE_TYPES = Map.ofEntries(
            Map.entry(CHEMICAL_OXIDIZER_ID, MekanismRecipeTypes.TYPE_OXIDIZING.value()),
            Map.entry(ISOTOPIC_CENTRIFUGE_ID, MekanismRecipeTypes.TYPE_CENTRIFUGING.value()),
            Map.entry(SOLAR_NEUTRON_ACTIVATOR_ID, MekanismRecipeTypes.TYPE_ACTIVATING.value()),
            Map.entry(OSMIUM_COMPRESSOR_ID, MekanismRecipeTypes.TYPE_COMPRESSING.value()),
            Map.entry(PURIFICATION_CHAMBER_ID, MekanismRecipeTypes.TYPE_PURIFYING.value()),
            Map.entry(CHEMICAL_INJECTION_CHAMBER_ID, MekanismRecipeTypes.TYPE_INJECTING.value()),
            Map.entry(METALLURGIC_INFUSER_ID, MekanismRecipeTypes.TYPE_METALLURGIC_INFUSING.value()),
            Map.entry(CHEMICAL_WASHER_ID, MekanismRecipeTypes.TYPE_WASHING.value()),
            Map.entry(CHEMICAL_INFUSER_ID, MekanismRecipeTypes.TYPE_CHEMICAL_INFUSING.value()),
            Map.entry(PIGMENT_EXTRACTOR_ID, MekanismRecipeTypes.TYPE_PIGMENT_EXTRACTING.value()),
            Map.entry(PIGMENT_MIXER_ID, MekanismRecipeTypes.TYPE_PIGMENT_MIXING.value()),
            Map.entry(PRESSURIZED_REACTION_CHAMBER_ID, MekanismRecipeTypes.TYPE_REACTION.value())
    );
    private static final Map<String, ResourceLocation> SUPPORTED_FACTORY_MACHINE_IDS = Map.of(
            "crushing_factory", CRUSHER_ID,
            "enriching_factory", ENRICHMENT_CHAMBER_ID,
            "smelting_factory", ENERGIZED_SMELTER_ID,
            "compressing_factory", OSMIUM_COMPRESSOR_ID,
            "purifying_factory", PURIFICATION_CHAMBER_ID,
            "injecting_factory", CHEMICAL_INJECTION_CHAMBER_ID,
            "infusing_factory", METALLURGIC_INFUSER_ID
    );
    private static final Set<ResourceLocation> ITEM_ONLY_RECIPE_TYPE_IDS = Set.of(
            CRUSHING_RECIPE_TYPE_ID,
            ENRICHING_RECIPE_TYPE_ID,
            SMELTING_RECIPE_TYPE_ID
    );
    private static final Set<ResourceLocation> KEYED_RECIPE_TYPE_IDS = Set.of(
            OXIDIZING_RECIPE_TYPE_ID,
            CENTRIFUGING_RECIPE_TYPE_ID,
            ACTIVATING_RECIPE_TYPE_ID,
            COMPRESSING_RECIPE_TYPE_ID,
            PURIFYING_RECIPE_TYPE_ID,
            INJECTING_RECIPE_TYPE_ID,
            METALLURGIC_INFUSING_RECIPE_TYPE_ID,
            WASHING_RECIPE_TYPE_ID,
            CHEMICAL_INFUSING_RECIPE_TYPE_ID,
            PIGMENT_EXTRACTING_RECIPE_TYPE_ID,
            PIGMENT_MIXING_RECIPE_TYPE_ID,
            REACTION_RECIPE_TYPE_ID
    );

    private MekanismDirectProcessingSupport() {
    }

    public static boolean isSupportedMachine(ResourceLocation machineId) {
        return isItemOnlySupportedMachine(machineId)
                || isAppliedMekanisticsBridgeAvailable() && isKeyedSupportedMachine(machineId);
    }

    public static boolean isItemOnlySupportedMachine(ResourceLocation machineId) {
        ResourceLocation normalizedMachineId = normalizeSupportedMachineId(machineId);
        return normalizedMachineId != null && ITEM_ONLY_RECIPE_TYPES.containsKey(normalizedMachineId);
    }

    public static boolean isKeyedSupportedMachine(ResourceLocation machineId) {
        ResourceLocation normalizedMachineId = normalizeSupportedMachineId(machineId);
        return normalizedMachineId != null && KEYED_RECIPE_TYPES.containsKey(normalizedMachineId);
    }

    public static boolean isSupportedRecipeType(ResourceLocation recipeTypeId) {
        return recipeTypeId != null
                && (ITEM_ONLY_RECIPE_TYPE_IDS.contains(recipeTypeId) || KEYED_RECIPE_TYPE_IDS.contains(recipeTypeId));
    }

    public static boolean isSupportedRecipeType(RecipeType<?> recipeType) {
        return recipeType != null
                && (ITEM_ONLY_RECIPE_TYPES.containsValue(recipeType) || KEYED_RECIPE_TYPES.containsValue(recipeType));
    }

    public static List<RecipeType<?>> resolveRecipeTypes(ResourceLocation machineId) {
        ResourceLocation normalizedMachineId = normalizeSupportedMachineId(machineId);
        if (normalizedMachineId == null) {
            return List.of();
        }
        List<RecipeType<?>> resolved = new ArrayList<>(2);
        RecipeType<?> itemOnly = ITEM_ONLY_RECIPE_TYPES.get(normalizedMachineId);
        if (itemOnly != null) {
            resolved.add(itemOnly);
        }
        if (isAppliedMekanisticsBridgeAvailable()) {
            RecipeType<?> keyed = KEYED_RECIPE_TYPES.get(normalizedMachineId);
            if (keyed != null) {
                resolved.add(keyed);
            }
        }
        return resolved.isEmpty() ? List.of() : List.copyOf(resolved);
    }

    public static boolean isAppliedMekanisticsBridgeAvailable() {
        return ModList.get() != null
                && ModList.get().isLoaded(MOD_ID)
                && ModList.get().isLoaded(APPLIED_MEKANISTICS_MOD_ID)
                && AppliedMekanisticsChemicalKeyBridge.instance().isAvailable();
    }

    public static RecipeType<ItemStackToItemStackRecipe> resolveRecipeType(ResourceLocation machineId) {
        ResourceLocation normalizedMachineId = normalizeSupportedMachineId(machineId);
        if (CRUSHER_ID.equals(normalizedMachineId)) {
            return MekanismRecipeTypes.TYPE_CRUSHING.value();
        }
        if (ENRICHMENT_CHAMBER_ID.equals(normalizedMachineId)) {
            return MekanismRecipeTypes.TYPE_ENRICHING.value();
        }
        if (ENERGIZED_SMELTER_ID.equals(normalizedMachineId)) {
            return MekanismRecipeTypes.TYPE_SMELTING.value();
        }
        return null;
    }

    private static ResourceLocation normalizeSupportedMachineId(ResourceLocation machineId) {
        if (machineId == null || !MOD_ID.equals(machineId.getNamespace())) {
            return machineId;
        }
        ResourceLocation normalizedFactoryMachineId = normalizeSupportedFactoryMachineId(machineId);
        return normalizedFactoryMachineId == null ? machineId : normalizedFactoryMachineId;
    }

    private static ResourceLocation normalizeSupportedFactoryMachineId(ResourceLocation machineId) {
        String machinePath = machineId.getPath();
        for (String tierPrefix : SUPPORTED_FACTORY_TIERS) {
            if (!machinePath.startsWith(tierPrefix)) {
                continue;
            }
            return SUPPORTED_FACTORY_MACHINE_IDS.get(machinePath.substring(tierPrefix.length()));
        }
        return null;
    }
}
