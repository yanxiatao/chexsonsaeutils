package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.resources.ResourceLocation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每次重载新建的规则 Builder；不复用上次脚本状态，构建后生成独立快照。 */
public final class DirectProcessingPackRulesBuilder {
    final Set<ResourceLocation> disabledMachines = new LinkedHashSet<>(), disabledTypes = new LinkedHashSet<>();
    final Set<ResourceLocation> blockedImportMachines = new LinkedHashSet<>(), blockedImportTypes = new LinkedHashSet<>();
    final Set<ResourceLocation> noAutomaticMachines = new LinkedHashSet<>(), replacementMachines = new LinkedHashSet<>();
    final Set<ResourceLocation> customMarkers = new LinkedHashSet<>(), importWhitelist = new LinkedHashSet<>();
    final Map<ResourceLocation, List<ResourceLocation>> machineTypes = new LinkedHashMap<>();
    boolean automaticDiscovery = true, jeiImport = true, hasImportWhitelist;

    public DirectProcessingPackRulesBuilder disableMachine(ResourceLocation id) { disabledMachines.add(id); return this; }
    public DirectProcessingPackRulesBuilder disableType(ResourceLocation id) { disabledTypes.add(id); return this; }
    public DirectProcessingPackRulesBuilder disableImportMachine(ResourceLocation id) { blockedImportMachines.add(id); return this; }
    public DirectProcessingPackRulesBuilder disableImportType(ResourceLocation id) { blockedImportTypes.add(id); return this; }
    public DirectProcessingPackRulesBuilder automaticDiscovery(boolean enabled) { automaticDiscovery = enabled; return this; }
    public DirectProcessingPackRulesBuilder jeiImport(boolean enabled) { jeiImport = enabled; return this; }
    public DirectProcessingPackRulesBuilder disableAutomaticDiscovery(ResourceLocation machine) { noAutomaticMachines.add(machine); return this; }
    public DirectProcessingPackRulesBuilder importTypeWhitelist(List<ResourceLocation> types) {
        hasImportWhitelist = true; importWhitelist.clear(); importWhitelist.addAll(types); return this;
    }
    public DirectProcessingPackRulesBuilder addMachineTypes(ResourceLocation machine, List<ResourceLocation> types) {
        var merged = new LinkedHashSet<>(machineTypes.getOrDefault(machine, List.of()));
        merged.addAll(types); machineTypes.put(machine, List.copyOf(merged)); return this;
    }
    public DirectProcessingPackRulesBuilder replaceMachineTypes(ResourceLocation machine, List<ResourceLocation> types) {
        replacementMachines.add(machine); machineTypes.put(machine, List.copyOf(new LinkedHashSet<>(types))); return this;
    }
    public DirectProcessingPackRulesBuilder marker(ResourceLocation item, List<ResourceLocation> types) {
        customMarkers.add(item); return addMachineTypes(item, types);
    }
    /** 读取服务端发来的完整规则快照，替换而非追加，避免重载后的客户端残留。 */
    public static DirectProcessingPackRulesBuilder fromJson(String json) {
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        var builder = new DirectProcessingPackRulesBuilder();
        readIds(root, "disabled_machines", builder.disabledMachines);
        readIds(root, "disabled_types", builder.disabledTypes);
        readIds(root, "blocked_import_machines", builder.blockedImportMachines);
        readIds(root, "blocked_import_types", builder.blockedImportTypes);
        readIds(root, "no_automatic_machines", builder.noAutomaticMachines);
        readIds(root, "replacement_machines", builder.replacementMachines);
        readIds(root, "custom_markers", builder.customMarkers);
        readIds(root, "import_whitelist", builder.importWhitelist);
        builder.automaticDiscovery = root.get("automatic_discovery").getAsBoolean();
        builder.jeiImport = root.get("jei_import").getAsBoolean();
        builder.hasImportWhitelist = root.get("has_import_whitelist").getAsBoolean();
        for (var entry : root.getAsJsonObject("machine_types").entrySet()) {
            var ids = new LinkedHashSet<ResourceLocation>();
            for (var value : entry.getValue().getAsJsonArray()) ids.add(ResourceLocation.parse(value.getAsString()));
            builder.machineTypes.put(ResourceLocation.parse(entry.getKey()), List.copyOf(ids));
        }
        return builder;
    }
    private static void readIds(com.google.gson.JsonObject root, String key, Set<ResourceLocation> target) {
        for (var value : root.getAsJsonArray(key)) target.add(ResourceLocation.parse(value.getAsString()));
    }

    public DirectProcessingPackRules build() { return new DirectProcessingPackRulesImpl(this); }
}
