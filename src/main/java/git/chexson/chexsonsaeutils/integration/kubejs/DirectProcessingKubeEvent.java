package git.chexson.chexsonsaeutils.integration.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRules;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRulesBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import dev.latvian.mods.rhino.util.HideFromJS;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** 服务端直连魔改事件；每次配方重载创建新 Builder，事件关闭后不能再修改。 */
public final class DirectProcessingKubeEvent implements KubeEvent {
    private final DirectProcessingPackRulesBuilder builder = new DirectProcessingPackRulesBuilder();
    private boolean failed, closed;

    public void disableImportType(String id) { change(b -> b.disableImportType(id(id))); }
    public void disableImportMachine(String id) { change(b -> b.disableImportMachine(machine(id))); }
    public void disableType(String id) { change(b -> b.disableType(id(id))); }
    public void disableMachine(String id) { change(b -> b.disableMachine(machine(id))); }
    public void jeiImport(boolean enabled) { change(b -> b.jeiImport(enabled)); }
    public void automaticDiscovery(boolean enabled) { change(b -> b.automaticDiscovery(enabled)); }
    public void disableAutomaticDiscovery(String machine) { change(b -> b.disableAutomaticDiscovery(machine(machine))); }
    public void importTypeWhitelist(String[] types) { change(b -> b.importTypeWhitelist(ids(types))); }
    public void addMachineTypes(String machine, String[] types) { change(b -> b.addMachineTypes(machine(machine), ids(types))); }
    public void replaceMachineTypes(String machine, String[] types) { change(b -> b.replaceMachineTypes(machine(machine), ids(types))); }
    public void marker(String item) { marker(item, new String[0]); }
    public void marker(String item, String[] types) { change(b -> b.marker(machine(item), ids(types))); }

    @HideFromJS
    public void markFailed() { failed = true; }
    @HideFromJS
    public DirectProcessingPackRules finish() {
        closed = true;
        if (failed) throw new IllegalStateException("直连 KubeJS 规则存在脚本错误，拒绝发布部分配置");
        return builder.build();
    }
    private void change(Consumer<DirectProcessingPackRulesBuilder> operation) {
        if (closed) throw new IllegalStateException("直连魔改事件已结束，只能在事件回调中配置");
        try {
            operation.accept(builder);
        } catch (RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }
    private static ResourceLocation id(String id) { return ResourceLocation.parse(id); }
    private static ResourceLocation machine(String id) {
        ResourceLocation location = id(id);
        if (!BuiltInRegistries.ITEM.containsKey(location) || BuiltInRegistries.ITEM.get(location) == Items.AIR) {
            throw new IllegalArgumentException("未知或无效的标记物品: " + id);
        }
        return location;
    }
    private static List<ResourceLocation> ids(String[] ids) { return Arrays.stream(ids).map(DirectProcessingKubeEvent::id).toList(); }
}
