package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface SourceCpuHandle {

    boolean isActive();

    @Nullable
    UUID craftingId();

    long getRequestedAmount(@Nullable AEKey what);

    long insert(@Nullable AEKey what, long amount, Actionable mode, IActionSource source);
}
