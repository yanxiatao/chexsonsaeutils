package git.chexson.chexsonsaeutils.crafting.hostbatch;

import java.util.UUID;

/** 回执交回指定批次终局的具名入口，隔离宿主集成与台账实现。 */
@FunctionalInterface
public interface DeclaredBatchTerminalCallback {
    /** 通知该批次交割、拒绝或取消；枚举由通用台账契约定义。 */
    void onTerminal(UUID batchId, DeclaredBatchTerminalLedger.Outcome outcome);
}
