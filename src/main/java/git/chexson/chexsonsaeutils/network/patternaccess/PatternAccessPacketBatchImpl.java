package git.chexson.chexsonsaeutils.network.patternaccess;

import appeng.core.network.clientbound.PatternAccessTerminalPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** 每包至多 128 槽，与项目 AE2 依赖的原生槽位 codec 一致，不修改集合上限或丢弃样板。 */
public final class PatternAccessPacketBatchImpl implements PatternAccessPacketBatch {
    public static final int SLOTS_PER_PACKET = 128;

    @Override
    public List<PatternAccessTerminalPacket> split(PatternAccessTerminalPacket packet) {
        if (packet.slots().size() <= SLOTS_PER_PACKET) return List.of(packet);
        var result = new ArrayList<PatternAccessTerminalPacket>();
        var slots = new Int2ObjectLinkedOpenHashMap<ItemStack>(SLOTS_PER_PACKET);
        for (var entry : packet.slots().int2ObjectEntrySet()) {
            // 网络编码晚于菜单采样；冻结栈，不能让之后的槽位操作改变已排队的数据。
            slots.put(entry.getIntKey(), entry.getValue().copy());
            if (slots.size() == SLOTS_PER_PACKET) {
                result.add(part(packet, result.isEmpty(), slots));
                slots = new Int2ObjectLinkedOpenHashMap<>(SLOTS_PER_PACKET);
            }
        }
        if (!slots.isEmpty()) result.add(part(packet, false, slots));
        return List.copyOf(result);
    }

    private static PatternAccessTerminalPacket part(PatternAccessTerminalPacket source, boolean first,
            Int2ObjectLinkedOpenHashMap<ItemStack> slots) {
        return first && source.fullUpdate()
                ? PatternAccessTerminalPacket.fullUpdate(source.inventoryId(), source.inventorySize(), source.sortBy(), source.group(), slots)
                : PatternAccessTerminalPacket.incrementalUpdate(source.inventoryId(), slots);
    }
}
