package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.ClampedMath;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class CraftingOutputRouter {

    private CraftingOutputRouter() {
    }

    /** 先把真实缓冲按同网 CPU 的精确欠量转移，余量仍留给 AE2 的 long 存储接口。 */
    public static void claimBufferedOutputs(WideKeyCounter buffer, IGrid sourceGrid,
            DeclaredBatchOutputReceiver receiver) {
        // 接收器会唤醒任务并刷新等待索引；这里只交出缓冲实物，不使用样板计划数。
        var keys = new ArrayList<AEKey>(buffer.size());
        buffer.keys().forEach(keys::add);
        for (var key : keys) {
            BigInteger available = buffer.getExact(key);
            if (available.signum() <= 0) continue;
            BigInteger accepted = receiver.receive(sourceGrid, key, available);
            buffer.add(key, accepted.negate());
        }
    }

    /** 向同网公开精确接收能力的执行器交付，每个执行器只认领自己实际等待的份额。 */
    public static void claimBufferedCpuOutputs(WideKeyCounter buffer, IGrid grid) {
        for (var cpu : grid.getCraftingService().getCpus()) {
            if (buffer.isEmpty()) return;
            if (cpu instanceof DeclaredBatchOutputReceiver receiver) claimBufferedOutputs(buffer, grid, receiver);
        }
    }

    /** 无 CPU 等待该键时，将剩余实物经精确存储能力回网，避免抢走未回流的中间产物。 */
    public static void storeUnrequestedBufferedOutputs(WideKeyCounter buffer,
            appeng.api.storage.MEStorage storage, IActionSource source,
            java.util.function.Predicate<AEKey> requesting) {
        if (!(storage instanceof git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion)) return;
        var keys = new ArrayList<AEKey>(buffer.size());
        buffer.keys().forEach(keys::add);
        for (var key : keys) {
            if (requesting.test(key)) continue;
            BigInteger accepted = git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion.insert(
                    storage, key, buffer.getExact(key), Actionable.MODULATE, source);
            buffer.add(key, accepted.negate());
        }
    }

    public static RoutingResult routePayload(
            @Nullable IStorageService storageService,
            IActionSource actionSource,
            List<GenericStack> payload,
            @Nullable SourceCpuHandle sourceCpu
    ) {
        if (payload == null || payload.isEmpty()) {
            return new RoutingResult(List.of(), List.of(), 0L, 0L, 0L);
        }

        List<GenericStack> remainingPayload = new ArrayList<>(payload.size());
        List<StackRoutingResult> stackResults = new ArrayList<>(payload.size());
        long acceptedBySourceCpu = 0L;
        long acceptedByAnyCpu = 0L;
        long insertedIntoAe = 0L;

        for (GenericStack genericStack : payload) {
            StackRoutingResult stackResult = routeStack(
                    storageService,
                    actionSource,
                    genericStack,
                    sourceCpu
            );
            if (stackResult.originalAmount() <= 0L) {
                continue;
            }
            stackResults.add(stackResult);
            acceptedBySourceCpu = ClampedMath.addUp(acceptedBySourceCpu, stackResult.acceptedBySourceCpu());
            acceptedByAnyCpu = ClampedMath.addUp(acceptedByAnyCpu, stackResult.acceptedByAnyCpu());
            insertedIntoAe = ClampedMath.addUp(insertedIntoAe, stackResult.insertedIntoAe());
            if (stackResult.remainingAmount() > 0L && stackResult.key() != null) {
                remainingPayload.add(new GenericStack(stackResult.key(), stackResult.remainingAmount()));
            }
        }

        return new RoutingResult(
                List.copyOf(remainingPayload),
                List.copyOf(stackResults),
                acceptedBySourceCpu,
                acceptedByAnyCpu,
                insertedIntoAe
        );
    }

    public static StackRoutingResult routeStack(
            @Nullable IStorageService storageService,
            IActionSource actionSource,
            @Nullable GenericStack genericStack,
            @Nullable SourceCpuHandle sourceCpu
    ) {
        if (genericStack == null || genericStack.what() == null || genericStack.amount() <= 0L) {
            return new StackRoutingResult(null, 0L, 0L, 0L, 0L, false, 0L);
        }

        AEKey key = genericStack.what();
        long remaining = genericStack.amount();
        long acceptedBySourceCpu = insertIntoSourceCpu(sourceCpu, key, remaining, actionSource);
        remaining = Math.max(0L, remaining - acceptedBySourceCpu);
        boolean attemptedAeFallback = storageService != null && remaining > 0L;
        long insertedIntoAe = attemptedAeFallback
                ? insertIntoAeNetwork(storageService, key, remaining, actionSource)
                : 0L;
        remaining = Math.max(0L, remaining - insertedIntoAe);

        return new StackRoutingResult(
                key,
                genericStack.amount(),
                acceptedBySourceCpu,
                ClampedMath.addUp(acceptedBySourceCpu, insertedIntoAe),
                insertedIntoAe,
                attemptedAeFallback,
                remaining
        );
    }

    public static RoutingResult routePayloadIntoSourceCpu(
            IActionSource actionSource,
            List<GenericStack> payload,
            @Nullable SourceCpuHandle sourceCpu
    ) {
        if (payload == null || payload.isEmpty()) {
            return new RoutingResult(List.of(), List.of(), 0L, 0L, 0L);
        }

        List<GenericStack> remainingPayload = new ArrayList<>(payload.size());
        List<StackRoutingResult> stackResults = new ArrayList<>(payload.size());
        long acceptedBySourceCpu = 0L;

        for (GenericStack genericStack : payload) {
            StackRoutingResult stackResult = routeStackIntoSourceCpu(actionSource, genericStack, sourceCpu);
            if (stackResult.originalAmount() <= 0L) {
                continue;
            }
            stackResults.add(stackResult);
            acceptedBySourceCpu = ClampedMath.addUp(acceptedBySourceCpu, stackResult.acceptedBySourceCpu());
            if (stackResult.remainingAmount() > 0L && stackResult.key() != null) {
                remainingPayload.add(new GenericStack(stackResult.key(), stackResult.remainingAmount()));
            }
        }

        return new RoutingResult(
                List.copyOf(remainingPayload),
                List.copyOf(stackResults),
                acceptedBySourceCpu,
                acceptedBySourceCpu,
                0L
        );
    }

    public static StackRoutingResult routeStackIntoSourceCpu(
            IActionSource actionSource,
            @Nullable GenericStack genericStack,
            @Nullable SourceCpuHandle sourceCpu
    ) {
        if (genericStack == null || genericStack.what() == null || genericStack.amount() <= 0L) {
            return new StackRoutingResult(null, 0L, 0L, 0L, 0L, false, 0L);
        }

        AEKey key = genericStack.what();
        long acceptedBySourceCpu = insertIntoSourceCpu(sourceCpu, key, genericStack.amount(), actionSource);
        long remaining = Math.max(0L, genericStack.amount() - acceptedBySourceCpu);

        return new StackRoutingResult(
                key,
                genericStack.amount(),
                acceptedBySourceCpu,
                acceptedBySourceCpu,
                0L,
                false,
                remaining
        );
    }

    private static long insertIntoSourceCpu(
            @Nullable SourceCpuHandle sourceCpu,
            AEKey key,
            long amount,
            IActionSource actionSource
    ) {
        if (amount <= 0L || sourceCpu == null || !sourceCpu.isActive()) {
            return 0L;
        }
        return clampAccepted(sourceCpu.insert(key, amount, Actionable.MODULATE, actionSource), amount);
    }

    private static long insertIntoAeNetwork(
            IStorageService storageService,
            AEKey key,
            long amount,
            IActionSource actionSource
    ) {
        if (amount <= 0L) {
            return 0L;
        }
        return clampAccepted(
                storageService.getInventory().insert(key, amount, Actionable.MODULATE, actionSource),
                amount
        );
    }

    private static long clampAccepted(long accepted, long limit) {
        if (accepted <= 0L || limit <= 0L) {
            return 0L;
        }
        return Math.min(limit, accepted);
    }

    public record RoutingResult(
            List<GenericStack> remainingPayload,
            List<StackRoutingResult> stackResults,
            long acceptedBySourceCpu,
            long acceptedByAnyCpu,
            long insertedIntoAe
    ) {
    }

    public record StackRoutingResult(
            @Nullable AEKey key,
            long originalAmount,
            long acceptedBySourceCpu,
            long acceptedByAnyCpu,
            long insertedIntoAe,
            boolean attemptedAeFallback,
            long remainingAmount
    ) {
    }
}
