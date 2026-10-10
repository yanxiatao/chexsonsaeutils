package git.chexson.chexsonsaeutils.terminal.sync;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
/** 会话内条目，未确认网络读数不参与精确和。 */
public record TerminalEntry(int id, AEKey key, StorageAmount amount, boolean craftable, Long unverified) {}
