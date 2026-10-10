package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 停滞行第二格读数（T2）：配给表行源是 {@code deficits}——缺口为空时 {@code alloc=}/{@code src=}
 * 整段不印，"这份<b>任务表</b>里有没有一张样板产这一枚"是盲区。{@code formatQuotaBlindspots}
 * 对每个"配额&gt;0 而池子与网络都 0"的键点名：{@code src=NONE}（{@code 39002a8} 定过的口径——
 * {@code src=} 读的是 {@code job.tasks}、<b>不按剩余发数过滤</b>，它只说得到"这份任务表里没有一张
 * 样板的输出含这个键"，<b>不等于</b>图上没有生产路线；图上有没有拓扑要读规划侧
 * {@code GraphPlanPostProcessor#describeHoles} 的 {@code producers=}）与
 * {@code src=样板#发数}（任务表里有它、却没跑）必须一眼分得开。
 *
 * <p>两条断言都断<b>整串</b>：摘掉任何一格读数即红。
 */
final class ParallelQuotaBlindspotReadoutTest {

    private static final long SESSION_QUOTA = 42_662_400_000L;
    private static final AEKey SHINING = AEItemKey.of(Items.NETHERITE_BLOCK);
    private static final AEKey CELL = AEItemKey.of(Items.CHEST);

    @Test
    void quotaBlindspotNoneVersusIdleProducerRenderVerbatim() {
        var consumer = consumingPattern();
        var job = jobWithRation(Map.of(consumer, 1L));
        // mountsInUse=?：台账 #225 读数 7——AE2 NetworkStorage 那道递归闸（19.2.17
        // NetworkStorage.java:48 私有字段，:173-176 那一支"闸抬着就交回 0"）问不出，如实问号。
        assertEquals(" quotaBlind=[" + SHINING + " quota=" + SESSION_QUOTA
                        + " stored=0 net=? mountsInUse=? src=NONE]",
                ParallelCraftingCpuLogic.StallProbe.formatQuotaBlindspots(job, Map.of(), null),
                "src=NONE ⇒ 这份任务表里没有一张样板的输出含这一枚（只说得到任务表这一层，"
                        + "图上有没有生产路线要读规划侧 describeHoles 的 producers=）");

        var producer = producingPattern();
        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(consumer, 1L);
        times.put(producer, 1L);
        var job2 = jobWithRation(times);
        assertEquals(" quotaBlind=[" + SHINING + " quota=" + SESSION_QUOTA
                        + " stored=0 net=? mountsInUse=? src=" + producer.getDefinition()
                        + ParallelCraftingCpuLogic.patternIdentityTag(producer)
                        + "#1<-poolFirst>0]",
                ParallelCraftingCpuLogic.StallProbe.formatQuotaBlindspots(job2, Map.of(), null),
                "src=样板#发数 ⇒ 任务表里有它、却没跑：派发/依赖那一型——两种病一眼分开；"
                        + "点名要落到样板<b>实例</b>（#i<hex>），否则与 noCandWhy= 那一张永远配不上号");
    }

    private static ParallelExecutingCraftingJob jobWithRation(Map<IPatternDetails, Long> times) {
        var consumerKey = times.keySet().iterator().next();
        var share = new WideKeyCounter();
        share.add(SHINING, SESSION_QUOTA);
        var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        material.put(consumerKey, share);
        var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                1L, Map.of(), material, null, null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
        return job;
    }

    private static FakePattern consumingPattern() {
        return new FakePattern(AEItemKey.of(Items.GLOWSTONE),
                new GenericStack[] { new GenericStack(CELL, 1L) },
                new IPatternDetails.IInput[] { new FakeInput(new GenericStack(SHINING, 1L)) });
    }

    private static FakePattern producingPattern() {
        return new FakePattern(AEItemKey.of(Items.IRON_BLOCK),
                new GenericStack[] { new GenericStack(SHINING, 1L) },
                new IPatternDetails.IInput[0]);
    }

    private record FakePattern(AEItemKey definition, GenericStack[] outputs,
            IPatternDetails.IInput[] slots) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(outputs);
        }
    }

    private record FakeInput(GenericStack candidate) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {candidate};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return candidate != null && candidate.what().equals(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
