package git.chexson.chexsonsaeutils.network.terminal;
import appeng.helpers.InventoryAction;
import java.util.UUID;
/** 服务端菜单协议边界，网络层不依赖具体菜单。 */
public interface TerminalServerTarget {
    /** 绑定客户端生成的会话并要求一次完整快照。 */
    void requestTerminalSnapshot(UUID session);
    /** 用服务器条目表解析操作，禁止客户端直接提交任意 AEKey。 */
    void interactTerminal(UUID session, int entryId, InventoryAction action);
}
