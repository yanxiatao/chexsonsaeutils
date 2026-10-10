package git.chexson.chexsonsaeutils.parts.playersimulation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import java.util.HashMap;
import java.util.Map;

/** 通过原生冷却回调追踪剩余时长，独立玩家卸载/重载时不跳过使用 CD，无需反射。 */
public final class SimulationCooldowns extends ItemCooldowns {
    private final Map<Item,Integer> remaining = new HashMap<>();
    @Override protected void onCooldownStarted(Item item,int ticks) { remaining.put(item,ticks); }
    @Override protected void onCooldownEnded(Item item) { remaining.remove(item); }
    @Override public void tick() {
        remaining.replaceAll((item,ticks) -> ticks - 1);
        super.tick();
    }
    /** 写入剩余时长而非世界绝对时间，未加载期间不运行模拟玩家。 */
    public CompoundTag save() {
        var tag = new CompoundTag();
        remaining.forEach((item,ticks) -> tag.putInt(BuiltInRegistries.ITEM.getKey(item).toString(),ticks));
        return tag;
    }
    /** 使用注册表严格恢复冷却。 */
    public void load(CompoundTag tag) {
        for (String name : tag.getAllKeys()) {
            var id = ResourceLocation.parse(name);
            if (!BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("模拟玩家冷却物品不存在: " + id);
            int ticks = tag.getInt(name);
            if (ticks <= 0) throw new IllegalArgumentException("无效的模拟玩家冷却: " + ticks);
            addCooldown(BuiltInRegistries.ITEM.get(id),ticks);
        }
    }
}
