package git.chexson.chexsonsaeutils.crafting.hostbatch;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** 一笔已接受声明批的终局凭据；不将计划产量当作实物交付证据。 */
public interface DeclaredBatchReceipt {
    /** 同次接单标识；宿主未报告时为 null，CPU 全额等待实物。 */
    @Nullable UUID batchId();
    /** 同次计划量是否被 long 分段上限夹过；只用于真实读数标记。 */
    boolean plannedOutputsClamped();
    /** 绑定同批次终局入口；允许注册时同步回调，调用方须先登记在飞记录。 */
    void bindTerminal(DeclaredBatchTerminalCallback callback, UUID attachedBatchId);
}
