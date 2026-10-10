package git.chexson.chexsonsaeutils.menu.matrix;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装配矩阵的服务端下发引擎：把全量样板槽的「变化格子」分批推给客户端。
 *
 * <p>只下发展示栈（样板产出物）与状态码，配方本体留在服务端；扫描用游标推进，
 * 单 tick 的产出量由 {@link #ENTRIES_PER_TICK} 限流，因此一万六千槽的机器
 * 打开界面时既不会拼出巨型包，也不会把一 tick 的预算花在比较上。</p>
 */
public final class PatternMatrixServerSync {

    public static final int ENTRIES_PER_PACKET = 128;
    public static final int ENTRIES_PER_TICK = 512;

    private final Map<Integer, ObservedSlot> observedSlots = new HashMap<>();
    private int scanCursor;

    /** 扫描并按包发出变化。 */
    public void broadcastChanges(PatternMatrixSource source, Sender sender) {
        List<PatternMatrixEntry> changes = collectChanges(source, ENTRIES_PER_TICK);
        for (int from = 0; from < changes.size(); from += ENTRIES_PER_PACKET) {
            int to = Math.min(changes.size(), from + ENTRIES_PER_PACKET);
            sender.send(List.copyOf(changes.subList(from, to)));
        }
    }

    /** 收集至多 maxEntries 条变化；未扫完的部分由后续调用从游标处继续。 */
    public List<PatternMatrixEntry> collectChanges(PatternMatrixSource source, int maxEntries) {
        List<PatternMatrixEntry> changes = new ArrayList<>();
        int totalSlots = source.patternMatrixTotalSlots();
        if (totalSlots <= 0 || maxEntries <= 0) {
            return changes;
        }
        long detailVersion = source.patternMatrixDetailVersion();
        for (int scanned = 0; scanned < totalSlots && changes.size() < maxEntries; scanned++) {
            int slot = scanCursor;
            scanCursor = (scanCursor + 1) % totalSlots;
            compareSlot(source, slot, detailVersion, changes);
        }
        return changes;
    }

    /** 菜单关闭时丢弃快照，下次打开重新全量下发。 */
    public void reset() {
        observedSlots.clear();
        scanCursor = 0;
    }

    public int observedSlotCount() {
        return observedSlots.size();
    }

    private void compareSlot(
            PatternMatrixSource source,
            int slot,
            long detailVersion,
            List<PatternMatrixEntry> out
    ) {
        ItemStack stored = source.patternMatrixInventory().getStackInSlot(slot);
        ObservedSlot previous = observedSlots.get(slot);
        if (stored.isEmpty()) {
            if (previous != null) {
                observedSlots.remove(slot);
                out.add(new PatternMatrixEntry(slot, ItemStack.EMPTY, 0, 0));
            }
            return;
        }
        if (previous != null
                && previous.detailVersion == detailVersion
                && ItemStack.isSameItemSameComponents(previous.observedStack, stored)) {
            return;
        }
        out.add(new PatternMatrixEntry(
                slot,
                source.patternMatrixDisplayStack(slot),
                source.patternMatrixStatusCode(slot),
                source.patternMatrixReasonCode(slot)
        ));
        observedSlots.put(slot, new ObservedSlot(stored.copy(), detailVersion));
    }

    private record ObservedSlot(ItemStack observedStack, long detailVersion) {
    }

    public interface Sender {
        void send(List<PatternMatrixEntry> entries);
    }
}
