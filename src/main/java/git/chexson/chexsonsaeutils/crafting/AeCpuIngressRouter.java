package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class AeCpuIngressRouter {

    private AeCpuIngressRouter() {
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
            acceptedBySourceCpu = saturatedAdd(acceptedBySourceCpu, stackResult.acceptedBySourceCpu());
            acceptedByAnyCpu = saturatedAdd(acceptedByAnyCpu, stackResult.acceptedByAnyCpu());
            insertedIntoAe = saturatedAdd(insertedIntoAe, stackResult.insertedIntoAe());
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
                saturatedAdd(acceptedBySourceCpu, insertedIntoAe),
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
            acceptedBySourceCpu = saturatedAdd(acceptedBySourceCpu, stackResult.acceptedBySourceCpu());
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

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
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
