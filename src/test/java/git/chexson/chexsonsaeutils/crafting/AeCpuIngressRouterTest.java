package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.support.TestKeySupport;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AeCpuIngressRouterTest {

    @Test
    void exactSourceCpuIsTriedEvenWhenRequestedSnapshotIsZero() {
        AEKey key = newDummyKey("exact-source");
        RecordingSourceCpuHandle sourceCpu = new RecordingSourceCpuHandle(UUID.randomUUID(), 0L, 8L, 8L);

        AeCpuIngressRouter.StackRoutingResult result = AeCpuIngressRouter.routeStack(
                null,
                null,
                IActionSource.empty(),
                new GenericStack(key, 8L),
                sourceCpu
        );

        assertEquals(0L, sourceCpu.requestedAmountCalls,
                "exact-source ingress should not gate the insert attempt on a stale requested-amount snapshot");
        assertEquals(1L, sourceCpu.simulateCalls,
                "exact-source ingress should probe the source CPU with AE-style simulation first");
        assertEquals(1L, sourceCpu.modulateCalls,
                "exact-source ingress should commit only the amount the source CPU simulated");
        assertEquals(8L, result.acceptedBySourceCpu(),
                "exact-source ingress should accept payload returned by the source CPU");
        assertEquals(8L, result.acceptedByAnyCpu(),
                "source CPU acceptance should count toward total CPU acceptance");
        assertEquals(0L, result.insertedIntoAe(),
                "payload accepted by the source CPU must not fall through into ordinary AE storage");
        assertEquals(0L, result.remainingAmount(),
                "payload accepted by the source CPU must not leave a retry remainder");
    }

    @Test
    void aeNetworkFallbackKeepsCpuAcceptanceOutOfAeStorage() {
        AEKey key = newDummyKey("network-cpu");
        RecordingCraftingService craftingService = new RecordingCraftingService(8L, 0L);
        RecordingMeStorage networkInventory = new RecordingMeStorage(8L, 8L);

        AeCpuIngressRouter.StackRoutingResult result = AeCpuIngressRouter.routeStack(
                craftingService,
                newStorageService(networkInventory),
                IActionSource.empty(),
                new GenericStack(key, 8L),
                null
        );

        assertEquals(1L, craftingService.simulateCalls,
                "strict AE ingress should classify CPU demand by simulating insertIntoCpus first");
        assertEquals(0L, craftingService.modulateCalls,
                "strict AE ingress should not hand-modulate CPUs before routing through the AE network inventory");
        assertEquals(1L, networkInventory.simulateCalls,
                "strict AE ingress should then simulate against the full AE network inventory");
        assertEquals(1L, networkInventory.modulateCalls,
                "strict AE ingress should commit only the amount the full AE network simulated");
        assertEquals(8L, result.acceptedByAnyCpu(),
                "network insertion that AE would route into CPUs must still count as CPU acceptance");
        assertEquals(0L, result.insertedIntoAe(),
                "CPU-routable network insertion must not be misclassified as ordinary AE storage");
        assertEquals(0L, result.remainingAmount(),
                "accepted ingress must not leave a retry remainder");
    }

    @Test
    void aeNetworkFallbackCountsOnlyTheExcessAsAeStorage() {
        AEKey key = newDummyKey("network-ae");
        RecordingCraftingService craftingService = new RecordingCraftingService(3L, 0L);
        RecordingMeStorage networkInventory = new RecordingMeStorage(8L, 8L);

        AeCpuIngressRouter.StackRoutingResult result = AeCpuIngressRouter.routeStack(
                craftingService,
                newStorageService(networkInventory),
                IActionSource.empty(),
                new GenericStack(key, 8L),
                null
        );

        assertEquals(3L, result.acceptedByAnyCpu(),
                "only the demand CPUs advertised during simulation should count as CPU acceptance");
        assertEquals(5L, result.insertedIntoAe(),
                "the remainder beyond simulated CPU demand should be treated as ordinary AE storage");
        assertEquals(0L, result.remainingAmount(),
                "network insertion that fully succeeded must not leave a retry remainder");
    }

    private static AEKey newDummyKey(String id) {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        return new DummyKey(id);
    }

    private static IStorageService newStorageService(@Nullable MEStorage inventory) {
        return (IStorageService) Proxy.newProxyInstance(
                AeCpuIngressRouterTest.class.getClassLoader(),
                new Class[]{IStorageService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInventory" -> inventory;
                    case "addGlobalStorageProvider", "removeGlobalStorageProvider",
                            "refreshNodeStorageProvider", "refreshGlobalStorageProvider", "invalidateCache" -> null;
                    default -> defaultValue(method);
                }
        );
    }

    private static IGrid newGrid() {
        return (IGrid) Proxy.newProxyInstance(
                AeCpuIngressRouterTest.class.getClassLoader(),
                new Class[]{IGrid.class},
                (proxy, method, args) -> defaultValue(method)
        );
    }

    private static IEnergyService newEnergyService() {
        return (IEnergyService) Proxy.newProxyInstance(
                AeCpuIngressRouterTest.class.getClassLoader(),
                new Class[]{IEnergyService.class},
                (proxy, method, args) -> defaultValue(method)
        );
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class RecordingCraftingService extends CraftingService {

        private final long simulateAccepted;
        private final long modulateAccepted;
        private long simulateCalls;
        private long modulateCalls;

        private RecordingCraftingService(long simulateAccepted, long modulateAccepted) {
            super(newGrid(), newStorageService(null), newEnergyService());
            this.simulateAccepted = simulateAccepted;
            this.modulateAccepted = modulateAccepted;
        }

        @Override
        public long insertIntoCpus(AEKey what, long amount, Actionable type) {
            if (what == null || amount <= 0L || type == null) {
                return 0L;
            }
            if (type == Actionable.SIMULATE) {
                simulateCalls++;
                return Math.min(amount, simulateAccepted);
            }
            modulateCalls++;
            return Math.min(amount, modulateAccepted);
        }
    }

    private static final class RecordingMeStorage implements MEStorage {

        private final long simulateAccepted;
        private final long modulateAccepted;
        private long simulateCalls;
        private long modulateCalls;

        private RecordingMeStorage(long simulateAccepted, long modulateAccepted) {
            this.simulateAccepted = simulateAccepted;
            this.modulateAccepted = modulateAccepted;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (what == null || amount <= 0L || mode == null) {
                return 0L;
            }
            if (mode == Actionable.SIMULATE) {
                simulateCalls++;
                return Math.min(amount, simulateAccepted);
            }
            modulateCalls++;
            return Math.min(amount, modulateAccepted);
        }

        @Override
        public Component getDescription() {
            return Component.literal("test-network-storage");
        }
    }

    private static final class RecordingSourceCpuHandle implements SourceCpuHandle {

        private final UUID craftingId;
        private final long requestedAmount;
        private final long simulatedAcceptedAmount;
        private final long modulatedAcceptedAmount;
        private long requestedAmountCalls;
        private long simulateCalls;
        private long modulateCalls;

        private RecordingSourceCpuHandle(
                UUID craftingId,
                long requestedAmount,
                long simulatedAcceptedAmount,
                long modulatedAcceptedAmount
        ) {
            this.craftingId = craftingId;
            this.requestedAmount = requestedAmount;
            this.simulatedAcceptedAmount = simulatedAcceptedAmount;
            this.modulatedAcceptedAmount = modulatedAcceptedAmount;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public @Nullable UUID craftingId() {
            return craftingId;
        }

        @Override
        public long getRequestedAmount(@Nullable AEKey what) {
            requestedAmountCalls++;
            return what == null ? 0L : requestedAmount;
        }

        @Override
        public long insert(@Nullable AEKey what, long amount, Actionable mode, IActionSource source) {
            if (what == null || amount <= 0L || mode == null) {
                return 0L;
            }
            if (mode == Actionable.SIMULATE) {
                simulateCalls++;
                return Math.min(amount, simulatedAcceptedAmount);
            }
            modulateCalls++;
            return Math.min(amount, modulatedAcceptedAmount);
        }
    }
}
