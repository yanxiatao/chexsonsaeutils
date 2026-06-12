package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.stacks.GenericStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PendingAeReturn {

    private static final String NBT_PRIMARY = "primary";
    private static final String NBT_REMAINING = "remaining";
    private static final String NBT_LOGICAL_EXECUTIONS = "logicalExecutions";
    private static final String NBT_COMPLETION_ROUTE = "completionRoute";
    private static final String NBT_PENDING_PAYLOAD = "pendingPayload";
    private static final String NBT_SOURCE_CRAFTING_ID = "sourceCraftingId";

    private final GenericStack primaryResult;
    private final List<GenericStack> remainingItems;
    private final int logicalExecutionCount;
    private final TaskCompletionRoute completionRoute;
    private final List<GenericStack> pendingPayload;
    @Nullable
    private final UUID sourceCraftingId;

    public PendingAeReturn(GenericStack primaryResult, List<GenericStack> remainingItems, int logicalExecutionCount) {
        this(primaryResult, remainingItems, logicalExecutionCount, TaskCompletionRoute.AE_STORAGE);
    }

    public PendingAeReturn(
            GenericStack primaryResult,
            List<GenericStack> remainingItems,
            int logicalExecutionCount,
            TaskCompletionRoute completionRoute
    ) {
        this(primaryResult, remainingItems, logicalExecutionCount, completionRoute, (UUID) null);
    }

    public PendingAeReturn(
            GenericStack primaryResult,
            List<GenericStack> remainingItems,
            int logicalExecutionCount,
            TaskCompletionRoute completionRoute,
            @Nullable UUID sourceCraftingId
    ) {
        this(
                primaryResult,
                remainingItems,
                logicalExecutionCount,
                completionRoute,
                buildDefaultPendingPayload(primaryResult, remainingItems, completionRoute),
                sourceCraftingId
        );
    }

    public PendingAeReturn(
            GenericStack primaryResult,
            List<GenericStack> remainingItems,
            int logicalExecutionCount,
            TaskCompletionRoute completionRoute,
            List<GenericStack> pendingPayload
    ) {
        this(primaryResult, remainingItems, logicalExecutionCount, completionRoute, pendingPayload, null);
    }

    public PendingAeReturn(
            GenericStack primaryResult,
            List<GenericStack> remainingItems,
            int logicalExecutionCount,
            TaskCompletionRoute completionRoute,
            List<GenericStack> pendingPayload,
            @Nullable UUID sourceCraftingId
    ) {
        this.primaryResult = primaryResult;
        this.remainingItems = List.copyOf(remainingItems == null ? List.of() : remainingItems);
        this.logicalExecutionCount = Math.max(1, logicalExecutionCount);
        this.completionRoute = completionRoute == null ? TaskCompletionRoute.AE_STORAGE : completionRoute;
        this.pendingPayload = List.copyOf(pendingPayload == null ? List.of() : pendingPayload);
        this.sourceCraftingId = sourceCraftingId;
    }

    public GenericStack primaryResult() {
        return primaryResult;
    }

    public List<GenericStack> remainingItems() {
        return remainingItems;
    }

    public int logicalExecutionCount() {
        return logicalExecutionCount;
    }

    public TaskCompletionRoute completionRoute() {
        return completionRoute;
    }

    public List<GenericStack> pendingPayload() {
        return pendingPayload;
    }

    @Nullable
    public UUID sourceCraftingId() {
        return sourceCraftingId;
    }

    public PendingAeReturn withPendingPayload(List<GenericStack> nextPayload) {
        return new PendingAeReturn(
                primaryResult,
                remainingItems,
                logicalExecutionCount,
                completionRoute,
                nextPayload,
                sourceCraftingId
        );
    }

    public CompoundTag writeToTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(NBT_PRIMARY, GenericStack.writeTag(registries, primaryResult));
        tag.put(NBT_REMAINING, writeStacks(registries, remainingItems));
        tag.putInt(NBT_LOGICAL_EXECUTIONS, logicalExecutionCount);
        tag.putString(NBT_COMPLETION_ROUTE, completionRoute.name());
        tag.put(NBT_PENDING_PAYLOAD, writeStacks(registries, pendingPayload));
        if (sourceCraftingId != null) {
            tag.putUUID(NBT_SOURCE_CRAFTING_ID, sourceCraftingId);
        }
        return tag;
    }

    @Nullable
    public static PendingAeReturn readFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        GenericStack primary = GenericStack.readTag(registries, tag.getCompound(NBT_PRIMARY));
        if (primary == null) {
            return null;
        }
        List<GenericStack> remaining = readStacks(tag.getList(NBT_REMAINING, Tag.TAG_COMPOUND), registries);
        TaskCompletionRoute completionRoute = tag.contains(NBT_COMPLETION_ROUTE)
                ? TaskCompletionRoute.valueOf(tag.getString(NBT_COMPLETION_ROUTE))
                : TaskCompletionRoute.AE_STORAGE;
        List<GenericStack> pendingPayload = tag.contains(NBT_PENDING_PAYLOAD)
                ? readStacks(tag.getList(NBT_PENDING_PAYLOAD, Tag.TAG_COMPOUND), registries)
                : buildDefaultPendingPayload(primary, remaining, completionRoute);
        UUID sourceCraftingId = tag.contains(NBT_SOURCE_CRAFTING_ID)
                ? tag.getUUID(NBT_SOURCE_CRAFTING_ID)
                : null;
        return new PendingAeReturn(
                primary,
                remaining,
                Math.max(1, tag.contains(NBT_LOGICAL_EXECUTIONS) ? tag.getInt(NBT_LOGICAL_EXECUTIONS) : 1),
                completionRoute,
                pendingPayload,
                sourceCraftingId
        );
    }

    private static List<GenericStack> buildDefaultPendingPayload(
            GenericStack primary,
            List<GenericStack> remaining,
            TaskCompletionRoute completionRoute
    ) {
        List<GenericStack> payload = new ArrayList<>();
        if (completionRoute == TaskCompletionRoute.CPU_WAITING) {
            if (primary != null) {
                payload.add(primary);
            }
            if (remaining != null) {
                payload.addAll(remaining);
            }
            return List.copyOf(payload);
        }
        if (primary != null) {
            payload.add(primary);
        }
        if (remaining != null) {
            payload.addAll(remaining);
        }
        return List.copyOf(payload);
    }

    private static ListTag writeStacks(HolderLookup.Provider registries, List<GenericStack> stacks) {
        ListTag listTag = new ListTag();
        if (stacks == null) {
            return listTag;
        }
        for (GenericStack stack : stacks) {
            if (stack != null) {
                listTag.add(GenericStack.writeTag(registries, stack));
            }
        }
        return listTag;
    }

    private static List<GenericStack> readStacks(ListTag listTag, HolderLookup.Provider registries) {
        List<GenericStack> stacks = new ArrayList<>();
        for (Tag stackEntry : listTag) {
            if (!(stackEntry instanceof CompoundTag stackTag)) {
                continue;
            }
            GenericStack stack = GenericStack.readTag(registries, stackTag);
            if (stack != null) {
                stacks.add(stack);
            }
        }
        return List.copyOf(stacks);
    }
}
