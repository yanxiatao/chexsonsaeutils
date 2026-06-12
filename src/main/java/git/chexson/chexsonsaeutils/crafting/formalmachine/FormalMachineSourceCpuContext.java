package git.chexson.chexsonsaeutils.crafting.formalmachine;

import git.chexson.chexsonsaeutils.blockentity.crafting.CompiledTask;
import git.chexson.chexsonsaeutils.blockentity.crafting.TaskCompletionRoute;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

public final class FormalMachineSourceCpuContext {

    private static final ThreadLocal<UUID> CURRENT_SOURCE_CRAFTING_ID = new ThreadLocal<>();

    private FormalMachineSourceCpuContext() {
    }

    public static <T> T withSourceCraftingId(@Nullable UUID sourceCraftingId, Supplier<T> action) {
        if (action == null) {
            return null;
        }
        if (sourceCraftingId == null) {
            return action.get();
        }

        UUID previous = CURRENT_SOURCE_CRAFTING_ID.get();
        CURRENT_SOURCE_CRAFTING_ID.set(sourceCraftingId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_SOURCE_CRAFTING_ID.remove();
            } else {
                CURRENT_SOURCE_CRAFTING_ID.set(previous);
            }
        }
    }

    public static void applyToCompiledTask(@Nullable CompiledTask compiledTask) {
        if (compiledTask == null) {
            return;
        }

        UUID sourceCraftingId = CURRENT_SOURCE_CRAFTING_ID.get();
        if (sourceCraftingId == null) {
            return;
        }

        compiledTask.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        if (compiledTask.getSourceCraftingId() == null) {
            compiledTask.setSourceCraftingId(sourceCraftingId);
        }
    }
}
