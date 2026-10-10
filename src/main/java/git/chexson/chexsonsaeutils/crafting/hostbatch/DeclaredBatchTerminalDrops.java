package git.chexson.chexsonsaeutils.crafting.hostbatch;

/** 全场 JVM 累计终局丢弃快照；noLedger 为未绑定，idMismatch 为批次标识不匹配。 */
public record DeclaredBatchTerminalDrops(long noLedger, long idMismatch) {
}
