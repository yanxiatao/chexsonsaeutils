package git.chexson.chexsonsaeutils.network.terminal;
import java.util.UUID;
/** 客户端菜单分片接收边界，专用服务器无需加载屏幕类。 */
public interface TerminalClientTarget {
    /** 校验菜单会话后组装和提交分片。 */
    void receiveTerminalChunk(UUID session, long revision, int index, int count, byte[] data);
}
