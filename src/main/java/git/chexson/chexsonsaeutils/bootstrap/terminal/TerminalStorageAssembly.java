package git.chexson.chexsonsaeutils.bootstrap.terminal;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import net.neoforged.fml.ModList;
import java.util.ArrayList;
import java.util.List;
/** 可选适配装配，盘和终端核心不引用 NeoEco 类型。 */
public final class TerminalStorageAssembly {
    private static TerminalInventoryReader reader;
    private TerminalStorageAssembly() {}
    public static synchronized TerminalInventoryReader reader() {
        if (reader == null) {
            var adapters = new ArrayList<ExactStorageAdapter>();
            var discoveries = new ArrayList<StorageMountDiscovery>();
            discoveries.add(new Ae2StorageMountDiscoveryImpl());
            adapters.add(new CellExactStorageAdapterImpl());
            if (ModList.get().isLoaded("extendedae_plus"))
                adapters.add(new git.chexson.chexsonsaeutils.integration.terminalstorage.extendedaeplus.PlusExactStorageAdapterImpl());
            NetworkExactInventory network = null;
            if (ModList.get().isLoaded("neoecoae")) {
                adapters.add(new git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoExactStorageAdapterImpl());
                network = new git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoNetworkExactInventoryImpl();
            }
            var event = new TerminalStorageRegistrationEvent(adapters, discoveries);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
            event.seal();
            reader = new TerminalInventoryReaderImpl(discoveries, adapters, network);
        }
        return reader;
    }
}
