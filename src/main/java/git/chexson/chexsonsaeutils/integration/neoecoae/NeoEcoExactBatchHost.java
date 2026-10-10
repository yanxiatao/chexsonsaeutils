package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** 能接收精确批次数的装配宿主；有限实体处理机不得声明此能力。 */
public interface NeoEcoExactBatchHost extends NeoEcoBatchHost {
    /** 扣料由 NeoEco 事务负责；宿主成功后持久化单份输出乘精确次数的实际产物。 */
    boolean chexsonsaeutils$pushNeoEcoExactBatch(IPatternDetails pattern, List<GenericStack> inputs,
            List<GenericStack> outputs, List<GenericStack> remainders, UUID jobId, BigInteger count);
}
