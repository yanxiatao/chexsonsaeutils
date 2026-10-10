package git.chexson.chexsonsaeutils.bootstrap.terminal;

import git.chexson.chexsonsaeutils.terminal.storage.ExactStorageAdapter;
import git.chexson.chexsonsaeutils.terminal.storage.StorageMountDiscovery;
import net.neoforged.bus.api.Event;
import java.util.List;
import java.util.Objects;

/** 公共事件：第三方在 NeoForge 总线上补充主机发现和精确数量适配，无需修改终端核心。 */
public final class TerminalStorageRegistrationEvent extends Event {
    private final List<ExactStorageAdapter> adapters;
    private final List<StorageMountDiscovery> discoveries;
    private boolean open = true;
    TerminalStorageRegistrationEvent(List<ExactStorageAdapter> adapters, List<StorageMountDiscovery> discoveries) {
        this.adapters = adapters; this.discoveries = discoveries;
    }
    /** 注册只读精确数量适配；同一库存匹配多个适配时采集拒绝而非依赖注册顺序。 */
    public void registerAdapter(ExactStorageAdapter adapter) { requireOpen(); adapters.add(Objects.requireNonNull(adapter)); }
    /** 注册实际挂载发现；不得重放存在写操作的 mountInventories。 */
    public void registerDiscovery(StorageMountDiscovery discovery) {
        requireOpen(); discoveries.add(Objects.requireNonNull(discovery));
    }
    void seal() { open = false; }
    private void requireOpen() { if (!open) throw new IllegalStateException("精确存储注册事件已结束"); }
}
