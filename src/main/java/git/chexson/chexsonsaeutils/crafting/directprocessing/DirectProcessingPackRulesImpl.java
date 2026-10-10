package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 整合包规则的不可变实现；执行禁用优先，导入白名单与禁用集合取交集。 */
public final class DirectProcessingPackRulesImpl implements DirectProcessingPackRules {
    private final Set<ResourceLocation> disabledMachines, disabledTypes, blockedImportMachines, blockedImportTypes;
    private final Set<ResourceLocation> noAutomaticMachines, replacementMachines, customMarkers;
    private final Map<ResourceLocation, List<ResourceLocation>> machineTypes;
    private final Set<ResourceLocation> importWhitelist;
    private final boolean automaticDiscovery, jeiImport, hasImportWhitelist;

    DirectProcessingPackRulesImpl(DirectProcessingPackRulesBuilder builder) {
        disabledMachines = Set.copyOf(builder.disabledMachines);
        disabledTypes = Set.copyOf(builder.disabledTypes);
        blockedImportMachines = Set.copyOf(builder.blockedImportMachines);
        blockedImportTypes = Set.copyOf(builder.blockedImportTypes);
        noAutomaticMachines = Set.copyOf(builder.noAutomaticMachines);
        replacementMachines = Set.copyOf(builder.replacementMachines);
        customMarkers = Set.copyOf(builder.customMarkers);
        machineTypes = builder.machineTypes.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        importWhitelist = Set.copyOf(builder.importWhitelist);
        automaticDiscovery = builder.automaticDiscovery;
        jeiImport = builder.jeiImport;
        hasImportWhitelist = builder.hasImportWhitelist;
    }

    private static boolean matches(Set<ResourceLocation> ids, MachineIdentity machine) {
        return machine != null && (ids.contains(machine.machineItemId())
                || machine.blockId() != null && ids.contains(machine.blockId()));
    }

    @Override public boolean allowsMachine(MachineIdentity machine) {
        return machine != null && !matches(disabledMachines, machine);
    }
    @Override public boolean allowsType(MachineIdentity machine, ResourceLocation type) {
        if (!allowsMachine(machine) || type == null || disabledTypes.contains(type)) return false;
        return type.equals(DirectProcessingPackRecipe.TYPE_ID) || !replacesTypes(machine) || addedTypes(machine).contains(type);
    }
    @Override public boolean allowsImport(MachineIdentity machine, ResourceLocation type) {
        return !DirectProcessingPackRecipe.TYPE_ID.equals(type) && jeiImport && allowsType(machine, type) && !matches(blockedImportMachines, machine)
                && !blockedImportTypes.contains(type) && (!hasImportWhitelist || importWhitelist.contains(type));
    }
    @Override public boolean allowsAutomaticDiscovery(MachineIdentity machine) {
        return automaticDiscovery && allowsMachine(machine) && !replacesTypes(machine)
                && !matches(noAutomaticMachines, machine);
    }
    @Override public List<ResourceLocation> addedTypes(MachineIdentity machine) {
        if (machine == null) return List.of();
        var types = new java.util.LinkedHashSet<ResourceLocation>();
        types.addAll(machineTypes.getOrDefault(machine.machineItemId(), List.of()));
        if (machine.blockId() != null) types.addAll(machineTypes.getOrDefault(machine.blockId(), List.of()));
        return List.copyOf(types);
    }
    @Override public boolean replacesTypes(MachineIdentity machine) { return matches(replacementMachines, machine); }
    @Override public boolean isCustomMarker(ResourceLocation item) { return customMarkers.contains(item); }
    @Override public String toJson() {
        var root = new com.google.gson.JsonObject();
        writeIds(root, "disabled_machines", disabledMachines);
        writeIds(root, "disabled_types", disabledTypes);
        writeIds(root, "blocked_import_machines", blockedImportMachines);
        writeIds(root, "blocked_import_types", blockedImportTypes);
        writeIds(root, "no_automatic_machines", noAutomaticMachines);
        writeIds(root, "replacement_machines", replacementMachines);
        writeIds(root, "custom_markers", customMarkers);
        writeIds(root, "import_whitelist", importWhitelist);
        root.addProperty("automatic_discovery", automaticDiscovery);
        root.addProperty("jei_import", jeiImport);
        root.addProperty("has_import_whitelist", hasImportWhitelist);
        var types = new com.google.gson.JsonObject();
        for (var entry : machineTypes.entrySet()) writeIds(types, entry.getKey().toString(), entry.getValue());
        root.add("machine_types", types);
        return root.toString();
    }
    private static void writeIds(com.google.gson.JsonObject root, String key, java.util.Collection<ResourceLocation> ids) {
        var array = new com.google.gson.JsonArray();
        for (var id : ids) array.add(id.toString());
        root.add(key, array);
    }
}
