package git.chexson.chexsonsaeutils.crafting;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface SourceCpuResolver {

    @Nullable
    SourceCpuHandle chexsonsaeutils$findSourceCpu(UUID craftingId);
}
