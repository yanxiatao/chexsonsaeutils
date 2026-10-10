package git.chexson.chexsonsaeutils.terminal.storage;
import java.util.Objects;
/** 终端读取契约的启动期绑定，运行层不反向依赖注册装配。 */
public final class TerminalInventoryAccess {
    private static TerminalInventoryReader reader;
    private TerminalInventoryAccess() {}
    /** common setup 完成可选模组装配后绑定一次。 */
    public static void bind(TerminalInventoryReader value) {
        if (reader != null) throw new IllegalStateException("终端读取重复装配");
        reader = Objects.requireNonNull(value);
    }
    public static TerminalInventoryReader reader() { return Objects.requireNonNull(reader, "精确终端尚未装配"); }
}
