package git.chexson.chexsonsaeutils.crafting.graphplan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简采样剖析器：按固定间隔抓目标线程的栈，按「本 mod 包内最深的那一帧」
 * 归并计数。只用于基准测试里定位热点，不参与产品代码。
 */
final class StackSampler implements AutoCloseable {

    private static final String PACKAGE_PREFIX = "git.chexson.chexsonsaeutils.";

    private final Thread target;
    private final Thread sampler;
    private final Map<String, Integer> selfCounts = new HashMap<>();
    private final Map<String, Integer> inclusiveCounts = new HashMap<>();
    private volatile boolean running = true;
    private int samples;

    StackSampler(Thread target, long intervalMillis) {
        this.target = target;
        this.sampler = new Thread(() -> run(intervalMillis), "chexsonsaeutils-stack-sampler");
        this.sampler.setDaemon(true);
        this.sampler.start();
    }

    private void run(long intervalMillis) {
        while (running) {
            StackTraceElement[] stack = target.getStackTrace();
            record(stack);
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    private synchronized void record(StackTraceElement[] stack) {
        if (stack.length == 0) {
            return;
        }
        samples++;
        String deepest = null;
        var seen = new java.util.HashSet<String>();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!className.startsWith(PACKAGE_PREFIX)) {
                continue;
            }
            String name = shortName(frame);
            if (deepest == null) {
                deepest = name;
            }
            if (seen.add(name)) {
                inclusiveCounts.merge(name, 1, Integer::sum);
            }
        }
        // 最深的非本包帧也记一笔自耗（JDK / AE2 里的调用点）
        if (deepest == null) {
            deepest = shortName(stack[0]);
        }
        selfCounts.merge(deepest, 1, Integer::sum);
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith(PACKAGE_PREFIX)) {
                break;
            }
            selfCounts.merge("<外部> " + shortName(frame), 1, Integer::sum);
            break;
        }
    }

    private static String shortName(StackTraceElement frame) {
        String className = frame.getClassName();
        int dot = className.lastIndexOf('.');
        return (dot < 0 ? className : className.substring(dot + 1)) + "." + frame.getMethodName();
    }

    @Override
    public void close() {
        running = false;
        sampler.interrupt();
        try {
            sampler.join(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized void report(String header, int top) {
        System.out.printf("%s samples=%d%n", header, samples);
        System.out.println("  -- 自耗最高（栈顶所在方法）--");
        print(selfCounts, top);
        System.out.println("  -- 含子调用最高（出现在栈中的方法）--");
        print(inclusiveCounts, top);
    }

    private void print(Map<String, Integer> counts, int top) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
        for (int i = 0; i < Math.min(top, entries.size()); i++) {
            var entry = entries.get(i);
            System.out.printf("     %5.1f%%  %s%n", 100.0 * entry.getValue() / Math.max(1, samples),
                    entry.getKey());
        }
    }
}
