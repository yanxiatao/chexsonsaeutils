package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.stacks.AEKeyType;
import java.math.BigInteger;

/** 补充 NeoEco long 进度入口无法表示的实际完成量；供精确产物接收事务使用。 */
public interface NeoEcoExactCompletedWork {
    /** 累加已经进入 CPU 库存的数量，并维护原生状态页的饱和 long 投影。 */
    void chexsonsaeutils$recordCompleted(BigInteger amount, AEKeyType type);
}
