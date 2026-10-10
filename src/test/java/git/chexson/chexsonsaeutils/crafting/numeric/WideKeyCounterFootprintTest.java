package git.chexson.chexsonsaeutils.crafting.numeric;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.KeyIndex;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;

/**
 * 度量用例（第 72 刀第一步）：量「每张样板一份」形态的 {@link WideKeyCounter}
 * 常驻内存与构建耗时，作为初容量收紧前后的回归闸门。
 *
 * <p>被测三形态：
 * <ul>
 * <li>{@code NOARG}：无参 {@code new WideKeyCounter()}——planMaterialEaten /
 * planMaterialPlan（{@code ParallelExecutingCraftingJob:265}）、bill 的
 * materialPlan（{@code MaterialClosureGate:365}）实际用的就是这个，
 * 每份自带一个小底 {@code KeyIndex}；</li>
 * <li>{@code SHARED}：{@code new WideKeyCounter(index)}——copy() /
 * GraphPlanningState 的形态，键表摊薄为零；</li>
 * <li>{@code AE2}：{@code new KeyCounter()}——AE2 原版账，作为显著性参照基线。</li>
 * </ul>
 *
 * <p>本刀两个基准点（同机复跑可再现量级）：收紧前 NOARG 恒为 3048B/份
 * （N=10000 → 30.5MB/账系，与 M 无关，大头是自带 KeyIndex 的 fastutil 256 槽初始表）；
 * 初容量收紧后 NOARG 929B/份（N=10000 → 9.3MB），全程低于 AE2 KeyCounter 在
 * M≥4 时的 2500B/8853B。构建耗时每份约降 3 倍。
 *
 * <p>常驻量法：强 GC 前后堆用量差（N=10000 一批，摊到每份）；构建耗时取 3 次
 * 重复的最小值。数字随 JVM 压缩指针/对齐略有浮动，但各形态间的倍数关系稳定。
 *
 * <h3>本类是度量台，不是门禁</h3>
 * 堆用量与耗时读数没有可钉的容差：同一份代码换 GC 策略、换压缩指针、换机器就会给出不同数字，
 * 拿它当回归闸门等于抛硬币。故整类挂 {@code @Tag("footprint")}，{@code build.gradle} 默认
 * {@code excludeTags} 排除（本机单条 43.4s，占全量 44.2s 的 98%）。
 * 要量时显式跑：{@code ./gradlew test --rerun -Pfootprint -Ponly=WideKeyCounterFootprint}。
 * 表内读数只打印不硬断；唯一保留的断言是批次活性核对（它保证读数不是 GC 假象，与内存大小无关）。
 */
@Tag("footprint")
class WideKeyCounterFootprintTest {

    private static final int[] N_GRID = {1, 100, 10000};
    private static final int[] M_GRID = {1, 4, 16};
    private static final int BATCH_FOR_PER_INSTANCE = 10000;

    private static final AEKey[] KEYS = buildKeys();

    private static AEKey[] buildKeys() {
        Item[] items = {Items.PAPER, Items.STONE, Items.COBBLESTONE, Items.DIRT, Items.SAND,
                Items.OAK_LOG, Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.REDSTONE,
                Items.LAPIS_LAZULI, Items.COAL, Items.GLASS, Items.BUCKET, Items.CLOCK, Items.STICK};
        AEKey[] keys = new AEKey[items.length];
        for (int i = 0; i < items.length; i++) {
            keys[i] = AEItemKey.of(new ItemStack(items[i]));
        }
        return keys;
    }

    private enum Shape {
        NOARG, SHARED, AE2
    }

    @Test
    void retainedAndBuildTimeGrid() {
        List<String> lines = new ArrayList<>();
        lines.add("VM=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + " maxHeapMB=" + (Runtime.getRuntime().maxMemory() >> 20));
        lines.add(String.format("%-7s %-3s %8s %16s %15s %16s",
                "shape", "M", "N", "retained_total_B", "per_instance_B", "build_us_total"));
        for (Shape shape : Shape.values()) {
            for (int m : M_GRID) {
                long perInstance = measureRetained(shape, m, BATCH_FOR_PER_INSTANCE)
                        / BATCH_FOR_PER_INSTANCE;
                long nsEach = measureBuildNanos(shape, m);
                for (int n : N_GRID) {
                    long totalRetained = n == BATCH_FOR_PER_INSTANCE
                            ? measureRetained(shape, m, n)
                            : perInstance * n;
                    long buildUs = TimeUnit.NANOSECONDS.toMicros(nsEach * n);
                    lines.add(String.format("%-7s %-3d %8d %16d %15d %16d",
                            shape, m, n, totalRetained, perInstance, buildUs));
                }
            }
        }
        String table = String.join(System.lineSeparator(), lines);
        System.out.println("== WideKeyCounter 常驻/构建度量表 ==");
        System.out.println(table);

        // 只报读数不硬断（见类注释）：形状间的倍数关系由人看表判定，绝不当闸门。
        long noarg = measureRetained(Shape.NOARG, 4, BATCH_FOR_PER_INSTANCE) / BATCH_FOR_PER_INSTANCE;
        long shared = measureRetained(Shape.SHARED, 4, BATCH_FOR_PER_INSTANCE) / BATCH_FOR_PER_INSTANCE;
        System.out.println("NOARG 每份常驻=" + noarg + "B  SHARED 每份常驻=" + shared
                + "B  比值=" + (shared == 0L ? "n/a" : (noarg / shared))
                + "（钉住的基准：收紧前 NOARG 3048B，收紧后 929B）");
    }

    /** 强 GC 前后堆用量差 = N 份账（含 M 个键的在册值）+ 持有数组本身的常驻字节。三轮取中位数。 */
    private static long measureRetained(Shape shape, int m, int n) {
        long[] rounds = new long[3];
        for (int r = 0; r < rounds.length; r++) {
            rounds[r] = measureRetainedOnce(shape, m, n);
        }
        java.util.Arrays.sort(rounds);
        return rounds[rounds.length / 2];
    }

    private static long measureRetainedOnce(Shape shape, int m, int n) {
        KeyIndex sharedIndex = shape == Shape.SHARED ? new KeyIndex(64) : null;
        if (shape == Shape.SHARED) {
            for (int i = 0; i < m; i++) {
                sharedIndex.idOf(KEYS[i]);
            }
        }
        long before = settledHeapUsed();
        Object[] holders = new Object[n];
        for (int i = 0; i < n; i++) {
            holders[i] = buildCounter(shape, m, sharedIndex);
        }
        long after = settledHeapUsed();
        // 活性核对必须在 after 读数【之后】做：JVM 只保证对象活到最后一次使用，
        // holders 若在 after 读数前就已「最后使用」，编译后的帧允许 GC 回收整批，
        // 读数会假回落基线（本机实测踩过：30MB 存活批次在沉降读数中归零）。
        long liveKeys = 0L;
        for (Object h : holders) {
            liveKeys += h instanceof WideKeyCounter c
                    ? c.size()
                    : ((KeyCounter) h).size();
        }
        assertEquals((long) n * m, liveKeys, "批次必须活过 after 读数，否则测量被 GC 假象污染");
        long holderBytes = 16L + 4L * n;
        if (after <= before) {
            // 不为负 delta 抛断言：那只是 GC 在两次读数之间收了别处的垃圾，读数照常报出，人看表判定。
            System.out.println("note: " + shape + " m=" + m + " 堆用量差非正("
                    + (after - before) + "B)，本轮读数不可信");
        }
        return after - before - holderBytes;
    }

    /** 3 次独立构建取最小值，单位：每份账的纳秒。 */
    private static long measureBuildNanos(Shape shape, int m) {
        KeyIndex sharedIndex = shape == Shape.SHARED ? new KeyIndex(64) : null;
        if (shape == Shape.SHARED) {
            for (int i = 0; i < m; i++) {
                sharedIndex.idOf(KEYS[i]);
            }
        }
        long best = Long.MAX_VALUE;
        int reps = 3;
        int n = Math.max(1000, 100000 / Math.max(1, m));
        for (int r = 0; r < reps; r++) {
            // 轮转小槽位：既防止 C2 把整轮构造当死代码消除，又只留 64 份驻留、
            // 不把耗时测成 GC 开销。
            Object[] sink = new Object[64];
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink[i & 63] = buildCounter(shape, m, sharedIndex);
            }
            long t1 = System.nanoTime();
            assertTrue(sink[0] != null);
            best = Math.min(best, (t1 - t0) / n);
        }
        return best;
    }

    private static Object buildCounter(Shape shape, int m, KeyIndex sharedIndex) {
        switch (shape) {
            case NOARG: {
                var counter = new WideKeyCounter();
                for (int j = 0; j < m; j++) {
                    counter.add(KEYS[j], j + 1L);
                }
                return counter;
            }
            case SHARED: {
                var counter = new WideKeyCounter(sharedIndex);
                for (int j = 0; j < m; j++) {
                    counter.add(KEYS[j], j + 1L);
                }
                return counter;
            }
            case AE2: {
                var counter = new KeyCounter();
                for (int j = 0; j < m; j++) {
                    counter.add(KEYS[j], j + 1L);
                }
                return counter;
            }
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * 沉降后的堆用量。不能用 {@code Runtime.totalMemory()-freeMemory()}：G1 下
     * freeMemory 读数会把装着存活对象的年轻代区域算成空闲（本机实测同一批 30MB
     * 存活对象，读数回落到基线）；也不能只看老年代池——System.gc 后批次可能
     * 仍整体存活于年轻代。改取全部堆池 used 之和，存活对象无论驻留哪个池都计入。
     */
    private static long settledHeapUsed() {
        long prev = -1L;
        for (int i = 0; i < 8; i++) {
            System.gc();
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long used = heapPoolUsedBytes();
            // 连续两次读数差 < 256KB 才算堆已沉降；单点读数会被年轻代残垃圾污染。
            if (prev >= 0L && Math.abs(used - prev) < (256L << 10)) {
                return used;
            }
            prev = used;
        }
        return prev;
    }

    private static long heapPoolUsedBytes() {
        long sum = 0L;
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                sum += pool.getUsage().getUsed();
            }
        }
        return sum;
    }
}
