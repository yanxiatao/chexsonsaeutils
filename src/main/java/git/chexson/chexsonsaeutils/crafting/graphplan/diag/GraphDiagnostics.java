package git.chexson.chexsonsaeutils.crafting.graphplan.diag;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

/**
 * Per-request verbose diagnostics collector for level-1 graph planning.
 *
 * <p>One calculation runs entirely on a single {@code AE Crafting Calculator}
 * thread, so a {@link ThreadLocal} buffer is a safe, allocation-free way to
 * gather a compile/solve/convert trace without threading a context object
 * through every layer. The shell that used to call {@link #begin()} when the
 * config switch was on, and {@link #end()} in a {@code finally}, was
 * {@code GraphPlanningCalculation} — it has been removed and nothing re-wired
 * that pair, so today {@link #active()} is always {@code false}, every
 * {@code log} call is a no-op, and {@link #dumpAndClear(String)} has no caller
 * either. 登记：这一整套诊断现在是空转的，要复活得在现役规划入口补 begin/end 那一对。
 *
 * <p>All {@code log} calls are no-ops when the buffer is not active, so the
 * instrumentation costs nothing in production.
 *
 * <p>两档：{@link #log} 是每阶段 O(1) 量级的摘要行，诊断一开就记；
 * {@link #trace} 是逐站点 / 逐键量级的行，额外要求 {@code graphPlanningTrace}。
 * 缓冲另有硬上限——此前缓冲无上限、只截断转储，modpack 实测单次计算攒到
 * 39 MB，光是 {@code String.format} 与 StringBuilder 扩容就吃掉整秒级耗时，
 * 诊断本身变成了被测对象。
 */
public final class GraphDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    /** Hard cap on one dump so a huge network cannot flood the log. */
    private static final int MAX_DUMP_CHARS = 512_000;
    /** 缓冲硬上限：超出即停止追加，只累计丢弃行数。 */
    private static final int MAX_BUFFER_CHARS = MAX_DUMP_CHARS;

    private static final class Session {
        private final StringBuilder buffer = new StringBuilder(4096);
        private final boolean trace;
        private long droppedLines;

        private Session(boolean trace) {
            this.trace = trace;
        }
    }

    private GraphDiagnostics() {
    }

    public static void begin() {
        begin(ChexsonsaeutilsCompatibilityConfig.graphPlanningTraceEnabled());
    }

    public static void begin(boolean trace) {
        SESSION.set(new Session(trace));
    }

    public static void end() {
        SESSION.remove();
    }

    public static boolean active() {
        return SESSION.get() != null;
    }

    /** 逐站点 / 逐键量级的行是否要记（默认关）。 */
    public static boolean traceActive() {
        Session session = SESSION.get();
        return session != null && session.trace;
    }

    /** Appends one summary line when the buffer is active; no-op otherwise. */
    public static void log(String format, Object... args) {
        append(SESSION.get(), format, args);
    }

    /**
     * 逐站点 / 逐键量级的行：只在 {@code graphPlanningTrace} 开启时记。
     * 调用方仍应用 {@link #traceActive()} 包住参数求值。
     */
    public static void trace(String format, Object... args) {
        Session session = SESSION.get();
        if (session == null || !session.trace) {
            return;
        }
        append(session, format, args);
    }

    private static void append(Session session, String format, Object[] args) {
        if (session == null) {
            return;
        }
        if (session.buffer.length() >= MAX_BUFFER_CHARS) {
            session.droppedLines++;
            return;
        }
        StringBuilder buffer = session.buffer;
        buffer.append('[').append(Thread.currentThread().getName()).append("] ");
        if (args.length == 0) {
            buffer.append(format);
        } else {
            buffer.append(String.format(format, args));
        }
        buffer.append('\n');
    }

    /** Dumps the collected trace (truncated) and clears the buffer. */
    public static void dumpAndClear(String header) {
        Session session = SESSION.get();
        if (session == null) {
            return;
        }
        StringBuilder buffer = session.buffer;
        String text = buffer.length() > MAX_DUMP_CHARS
                ? buffer.substring(0, MAX_DUMP_CHARS) + "\n... truncated ("
                        + (buffer.length() - MAX_DUMP_CHARS) + " chars dropped)"
                : buffer.toString();
        if (session.droppedLines > 0) {
            text = text + "\n... " + session.droppedLines + " further lines dropped at the "
                    + MAX_BUFFER_CHARS + " char buffer cap";
        }
        LOGGER.info("{}\n{}", header, text);
        SESSION.remove();
    }
}
