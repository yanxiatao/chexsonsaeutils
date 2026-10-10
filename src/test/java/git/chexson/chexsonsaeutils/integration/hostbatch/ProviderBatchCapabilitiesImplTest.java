package git.chexson.chexsonsaeutils.integration.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchProvider;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerBatchClient;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessFurnaceBatchHost;
import java.math.BigInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 宿主探测、适配身份会话与队列诊断直接行为验证。 */
final class ProviderBatchCapabilitiesImplTest {
    private static final IPatternDetails PATTERN = new Pattern(AEItemKey.of(Items.IRON_INGOT));

    @Test
    void nativeDeclarationHonorsSupportAndReadinessWithoutAdaptedProbe() {
        var session = new ProviderBatchCapabilitiesImpl(() -> { throw unused(); });
        var host = new NativeHost();
        var readyCalls = new AtomicInteger();
        assertNull(session.nativeDeclaredHost(host, PATTERN, ignored -> {
            readyCalls.incrementAndGet();
            return true;
        }));
        assertEquals(1, host.supportCalls);
        assertEquals(0, readyCalls.get());
        host.supported = true;
        assertNull(session.nativeDeclaredHost(host, PATTERN, ignored -> false));
        assertSame(host, session.nativeDeclaredHost(host, PATTERN, ignored -> true));
        assertEquals(3, host.supportCalls);
        assertNull(session.adaptedDeclaredHost(host, PATTERN, ignored -> { throw unused(); }));
    }

    @Test
    void nativeHolderRechecksDynamicMaximumBeforePreparing() {
        var host = new NativeHolder();
        var session = new ProviderBatchCapabilitiesImpl(() -> { throw unused(); });
        var capability = session.holderCapability(host);
        assertNotNull(capability);
        assertFalse(capability.foldsPattern());
        assertEquals(8L, capability.maxPushExecutions(PATTERN));
        host.maximum = 2L;
        assertNull(capability.preparePushPattern(PATTERN, 4L));
        assertEquals(2, host.maxCalls);
        assertSame(PATTERN, capability.preparePushPattern(PATTERN, 2L));
        assertEquals(3, host.maxCalls);
    }

    @Test
    void foldingHolderPreparesWithoutReadingMaximumAgainAndPreservesNull() {
        var host = new FoldHost();
        var session = new ProviderBatchCapabilitiesImpl(() -> null);
        var capability = session.holderCapability(host);
        assertNotNull(capability);
        assertTrue(capability.foldsPattern());
        assertEquals(12L, capability.maxPushExecutions(PATTERN));
        var folded = new Pattern(AEItemKey.of(Items.GOLD_INGOT));
        host.folded = folded;
        assertSame(folded, capability.preparePushPattern(PATTERN, 5L));
        assertEquals(5L, host.lastFoldExecutions);
        host.folded = null;
        assertNull(capability.preparePushPattern(PATTERN, 7L));
        assertEquals(7L, host.lastFoldExecutions);
        assertEquals(1, host.maxCalls);
        assertEquals(2, host.foldCalls);
    }

    @Test
    void dualHolderInterfacePrioritizesNativeAndNeverCreatesAdaptedShell() {
        var host = new DualHolder();
        var session = new ProviderBatchCapabilitiesImpl(() -> { throw unused(); });
        var capability = session.holderCapability(host);
        assertNotNull(capability);
        assertFalse(capability.foldsPattern());
        assertEquals(8L, capability.maxPushExecutions(PATTERN));
        assertSame(PATTERN, capability.preparePushPattern(PATTERN, 3L));
        assertNull(session.adaptedDeclaredHost(host, PATTERN, ignored -> { throw unused(); }));
        assertEquals(2, host.maxCalls);
    }

    @Test
    void unsupportedProviderHasNoHolderOrDeclaration() {
        var provider = new PlainProvider();
        var session = new ProviderBatchCapabilitiesImpl(() -> { throw unused(); });
        assertNull(session.holderCapability(provider));
        assertNull(session.nativeDeclaredHost(provider, PATTERN, ignored -> { throw unused(); }));
        assertNull(session.adaptedDeclaredHost(provider, PATTERN, ignored -> { throw unused(); }));
    }

    @Test
    void adapterRequiresRealChannelRatherThanUnconditionalSupport() {
        var gridReads = new AtomicInteger();
        var session = new ProviderBatchCapabilitiesImpl(() -> { gridReads.incrementAndGet(); return null; });
        var host = new FoldHost();
        assertNull(session.adaptedDeclaredHost(host, PATTERN, ignored -> { throw unused(); }));
        assertTrue(session.bigintDeclaredHost(host).supportsDeclaredBatch(PATTERN));
        assertEquals(1, gridReads.get());
        assertSame(host, session.bigintDeclaredHost(host).declaredBatchBackoffOwner());
    }

    @Test
    void sessionDefersGridReadUntilFirstShellAndReusesProviderIdentity() {
        var gridReads = new AtomicInteger();
        var session = new ProviderBatchCapabilitiesImpl(() -> { gridReads.incrementAndGet(); return null; });
        var host = new FoldHost();
        assertEquals(0, gridReads.get());
        assertEquals(" queuePending[useless侧]=? queuePendingCov=shown=0/total=0", session.queuePendingReadout());
        assertEquals(0, gridReads.get());
        var first = session.bigintDeclaredHost(host);
        assertSame(first, session.bigintDeclaredHost(host));
        assertEquals(1, gridReads.get());
        assertNotSame(first, session.bigintDeclaredHost(new FoldHost()));
        assertEquals(2, gridReads.get());
    }

    @Test
    void thirtyThirdDistinctProviderClearsCacheButThirtySecondDoesNot() {
        var gridReads = new AtomicInteger();
        var session = new ProviderBatchCapabilitiesImpl(() -> { gridReads.incrementAndGet(); return null; });
        var providers = new ArrayList<FoldHost>();
        for (int i = 0; i < 32; i++) { providers.add(new FoldHost()); }
        var first = session.bigintDeclaredHost(providers.getFirst());
        for (int i = 1; i < 32; i++) { session.bigintDeclaredHost(providers.get(i)); }
        assertSame(first, session.bigintDeclaredHost(providers.getFirst()));
        assertEquals(32, gridReads.get());
        var thirtyThirdProvider = new FoldHost();
        var thirtyThird = session.bigintDeclaredHost(thirtyThirdProvider);
        assertNotSame(first, session.bigintDeclaredHost(providers.getFirst()));
        assertSame(thirtyThird, session.bigintDeclaredHost(thirtyThirdProvider));
        assertEquals(34, gridReads.get());
    }

    @Test
    void equalButDistinctProvidersRetainSeparateShellIdentities() {
        var session = new ProviderBatchCapabilitiesImpl(() -> null);
        var first = new EqualFoldHost();
        var second = new EqualFoldHost();
        assertEquals(first, second);
        assertNotSame(first, second);
        var firstShell = session.bigintDeclaredHost(first);
        var secondShell = session.bigintDeclaredHost(second);
        assertNotSame(firstShell, secondShell);
        assertSame(firstShell, session.bigintDeclaredHost(first));
        assertSame(secondShell, session.bigintDeclaredHost(second));
        assertSame(first, firstShell.declaredBatchBackoffOwner());
        assertSame(second, secondShell.declaredBatchBackoffOwner());
    }

    @Test
    void factoryCreatesIndependentLaneSessions() {
        var factory = new ProviderBatchCapabilitiesFactoryImpl();
        var host = new FoldHost();
        var gridReads = new AtomicInteger();
        java.util.function.Supplier<appeng.api.networking.IGrid> grid = () -> {
            gridReads.incrementAndGet();
            return null;
        };
        var first = (ProviderBatchCapabilitiesImpl) factory.create(grid);
        var second = (ProviderBatchCapabilitiesImpl) factory.create(grid);
        assertNotSame(first.bigintDeclaredHost(host), second.bigintDeclaredHost(host));
        assertEquals(2, gridReads.get());
    }

    @Test
    void throwingQueueReadRemainsUnknownAndOtherMachinesStayVisible() {
        var cells = ProviderBatchCapabilitiesImpl.queuePendingReadout(List.of(
                new StubMachine("bad", null, true), new StubMachine("good", 2L, 8L, false)));
        assertEquals(" queuePending[useless侧]=2/8 queuePendingCov=shown=1/total=2", cells);
        assertEquals(" queuePending[useless侧]=? queuePendingCov=shown=0/total=1",
                ProviderBatchCapabilitiesImpl.queuePendingReadout(List.of(new StubMachine("bad", null, true))));
    }

    private record Pattern(AEItemKey definition) implements IPatternDetails {
        public AEItemKey getDefinition() { return definition; }
        public IInput[] getInputs() { return new IInput[0]; }
        public List<GenericStack> getOutputs() { return List.of(new GenericStack(definition, 1L)); }
    }

    private static class PlainProvider implements ICraftingProvider {
        public List<IPatternDetails> getAvailablePatterns() { throw unused(); }
        public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { throw unused(); }
        public boolean isBusy() { throw unused(); }
    }

    private static final class NativeHost extends PlainProvider implements DeclaredBatchHost {
        boolean supported;
        int supportCalls;
        public boolean supportsDeclaredBatch(IPatternDetails pattern) { supportCalls++; return supported; }
        public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] inputs, BigInteger executions) {
            throw unused();
        }
    }

    private static class NativeHolder extends PlainProvider implements HostBatchProvider {
        long maximum = 8L;
        int maxCalls;
        public long maxPushExecutions(IPatternDetails pattern) { maxCalls++; return maximum; }
        public int getCurrentOperationTicks() { throw unused(); }
        public BlockPos getBlockPos() { throw unused(); }
    }

    private static final class DualHolder extends NativeHolder implements UselessFurnaceBatchHost {
        public long chexsonsaeutils$maxFoldedPushExecutions(IPatternDetails pattern) { throw unused(); }
        public IPatternDetails chexsonsaeutils$foldPushPattern(IPatternDetails pattern, long executions) {
            throw unused();
        }
    }

    private static class FoldHost extends PlainProvider implements UselessFurnaceBatchHost {
        int maxCalls;
        int foldCalls;
        long lastFoldExecutions;
        IPatternDetails folded;
        public long chexsonsaeutils$maxFoldedPushExecutions(IPatternDetails pattern) { maxCalls++; return 12L; }
        public IPatternDetails chexsonsaeutils$foldPushPattern(IPatternDetails pattern, long executions) {
            foldCalls++;
            lastFoldExecutions = executions;
            return folded;
        }
    }

    private static final class EqualFoldHost extends FoldHost {
        @Override public boolean equals(Object other) { return other instanceof EqualFoldHost; }
        @Override public int hashCode() { return 1; }
    }

    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException("测试未配置该入口");
    }

    // ==================== 读数 5：回网队列深度（public 两口的换算读数）====================

    /**
     * 臂 (a)：喂一台"剩余 &lt; 上限"的机器 ⇒ 那一格必须印出<b>真数</b>（条目数＝上限−剩余），
     * 覆盖面 {@code shown=1/total=1}。把 {@code queuePendingReadout} 还原成恒印 {@code ?}
     * 的那一行，这一条立刻红。
     */
    @Test
    void queuePendingReadoutPrintsRealEntriesWhenMachineAnswers() {
        var cells = ProviderBatchCapabilitiesImpl.queuePendingReadout(
                List.of(new StubMachine("overworld@1,2,3", 3L, 8L, false)));
        assertEquals(" queuePending[useless侧]=3/8 queuePendingCov=shown=1/total=1", cells,
                "真数与覆盖面都要印出来，且一格一 token：" + cells);
        assertFalse(cells.contains("?"), () -> "问到了就不许留问号：" + cells);
    }

    /**
     * 臂 (b)：喂一台 {@code remaining==0}（饱和）的机器 ⇒ 那一格<b>额外标 {@code (full)}</b>：
     * 分子只是"≥"的下限，读口给不出更多。两台里有一台饱和也必须带标记，且分子/分母各台相加。
     */
    @Test
    void saturatedMachineMarksTheCellAsFloorInsteadOfQuantity() {
        var cells = ProviderBatchCapabilitiesImpl.queuePendingReadout(List.of(
                new StubMachine("overworld@1,2,3", 8L, 8L, true),
                new StubMachine("overworld@9,9,9", 2L, 8L, false)));
        assertEquals(" queuePending[useless侧]=10/16(full) queuePendingCov=shown=2/total=2", cells,
                "一台饱和就要整格标 (full)，两台各按自己的上限相加：" + cells);
        assertFalse(cells.contains("?"), () -> "两台都问到了，不许混进问号：" + cells);
    }

    /**
     * 臂 (c)：喂三台"问不到"的机器（BE 不是那一型／没装 useless／读口抛异常）⇒ 那一格印
     * {@code ?} 而<b>不是 {@code 0}</b>，覆盖面 {@code shown=0/total=3}——一台都没问到，
     * 但碰到了三台，这两个数不许糊成一个。
     */
    @Test
    void unansweredQueuePendingPrintsQuestionMarkNeverZero() {
        var cells = ProviderBatchCapabilitiesImpl.queuePendingReadout(List.of(
                new StubMachine("overworld@1,2,3", null),
                new StubMachine("overworld@4,5,6", null),
                new StubMachine("overworld@7,8,9", null)));
        assertEquals(" queuePending[useless侧]=? queuePendingCov=shown=0/total=3", cells,
                () -> "问不到那一档永远是问号，把 0 当「没积压」印出来就是撒谎：" + cells);
        // 深度那一格自己不许带任何分数（cov 那一格带的是台数，不是枚数）
        assertFalse(cells.split(" ")[1].contains("/"),
                () -> "问号那一格不许混进分数：" + cells);
    }

    /** 混合覆盖面：两台问得到、一台问不到 ⇒ 分子只加问得到的那两台，total 仍是三台。 */
    @Test
    void queuePendingCoverageCountsTheMachinesItCouldNotAsk() {
        var cells = ProviderBatchCapabilitiesImpl.queuePendingReadout(List.of(
                new StubMachine("overworld@1,2,3", 1L, 8L, false),
                new StubMachine("overworld@4,5,6", null),
                new StubMachine("overworld@7,8,9", 5L, 16L, false)));
        assertEquals(" queuePending[useless侧]=6/24 queuePendingCov=shown=2/total=3", cells,
                () -> "问不到的那一台不许被折成 0/0 冒充分数的一部分：" + cells);
    }


    /**
     * 只扮 {@code queuePending()} 那一只口的假机器：其余四只方法这一格用不上，一律给最省事的答案。
     * {@code pending} 交 {@code null} 即"问不到"（BE 不是那一型／类没装／读口抛异常三型同影）。
     */
    private record StubMachine(String identity,
            @Nullable UselessBigIntegerBatchClient.QueuePending pending, boolean throwing)
            implements UselessBigIntegerBatchClient.Machine {

        StubMachine(String identity, @Nullable UselessBigIntegerBatchClient.QueuePending pending) {
            this(identity, pending, false);
        }

        StubMachine(String identity, long entries, long cap, boolean saturated) {
            this(identity, new UselessBigIntegerBatchClient.QueuePending(entries, cap, saturated), false);
        }

        @Override
        public String machineIdentity() {
            return identity;
        }

        @Override
        public boolean throttled() {
            return false;
        }

        @Override
        public UselessBigIntegerBatchClient.Capacity capacity(appeng.api.crafting.IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested) {
            throw new UnsupportedOperationException("读数用例不碰接单口");
        }

        @Override
        public @Nullable UselessBigIntegerBatchClient.BatchHandle admit(
                appeng.api.crafting.IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger requested, UselessBigIntegerReceipt receipt) {
            throw new UnsupportedOperationException("读数用例不碰接单口");
        }

        @Override
        public @Nullable UselessBigIntegerBatchClient.QueuePending queuePending() {
            if (throwing) { throw new IllegalStateException("测试机器队列读口失败"); }
            return pending;
        }
    }



}
