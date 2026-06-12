package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.me.service.CraftingService;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class AeCpuIngressRouter {

    private AeCpuIngressRouter() {
    }

    public static RoutingResult routePayload(
            @Nullable CraftingService craftingService,
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
                    craftingService,
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
            @Nullable CraftingService craftingService,
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
        long acceptedBySourceCpu = tryInsertIntoSourceCpu(sourceCpu, key, remaining, actionSource);
        remaining -= acceptedBySourceCpu;

        boolean attemptedAeFallback = false;
        long acceptedByFallbackCpu = 0L;
        long insertedIntoAe = 0L;
        if (remaining > 0L && storageService != null) {
            long simulatedByAnyCpu = craftingService == null
                    ? 0L
                    : clampAccepted(
                            craftingService.insertIntoCpus(key, remaining, Actionable.SIMULATE),
                            remaining
                    );
            long simulatedByNetwork = clampAccepted(
                    storageService.getInventory().insert(key, remaining, Actionable.SIMULATE, actionSource),
                    remaining
            );
            if (simulatedByNetwork > 0L) {
                attemptedAeFallback = true;
                long insertedIntoNetwork = clampAccepted(
                        storageService.getInventory().insert(
                                key,
                                simulatedByNetwork,
                                Actionable.MODULATE,
                                actionSource
                        ),
                        simulatedByNetwork
                );
                acceptedByFallbackCpu = Math.min(simulatedByAnyCpu, insertedIntoNetwork);
                insertedIntoAe = Math.max(0L, insertedIntoNetwork - acceptedByFallbackCpu);
                remaining -= insertedIntoNetwork;
            }
        } else if (remaining > 0L && craftingService != null) {
            acceptedByFallbackCpu = tryInsertIntoCraftingService(craftingService, key, remaining);
            remaining -= acceptedByFallbackCpu;
        }

        long acceptedByAnyCpu = saturatedAdd(acceptedBySourceCpu, acceptedByFallbackCpu);
        return new StackRoutingResult(
                key,
                genericStack.amount(),
                acceptedBySourceCpu,
                acceptedByAnyCpu,
                insertedIntoAe,
                attemptedAeFallback,
                Math.max(0L, remaining)
        );
    }

    private static long tryInsertIntoSourceCpu(
            @Nullable SourceCpuHandle sourceCpu,
            AEKey key,
            long amount,
            IActionSource actionSource
    ) {
        if (sourceCpu == null || !sourceCpu.isActive() || amount <= 0L) {
            return 0L;
        }
        long simulated = clampAccepted(
                sourceCpu.insert(key, amount, Actionable.SIMULATE, actionSource),
                amount
        );
        if (simulated <= 0L) {
            return 0L;
        }
        return clampAccepted(
                sourceCpu.insert(key, simulated, Actionable.MODULATE, actionSource),
                simulated
        );
    }

    private static long tryInsertIntoCraftingService(
            CraftingService craftingService,
            AEKey key,
            long amount
    ) {
        if (amount <= 0L) {
            return 0L;
        }
        long simulated = clampAccepted(
                craftingService.insertIntoCpus(key, amount, Actionable.SIMULATE),
                amount
        );
        if (simulated <= 0L) {
            return 0L;
        }
        return clampAccepted(
                craftingService.insertIntoCpus(key, simulated, Actionable.MODULATE),
                simulated
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
