package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.ClampedMath;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CPU 池子的物理进出账：任何一次 MODULATE 提取/存入都按当时的代码路径记一笔。
 *
 * <p>流水账（提走 / 报称 / 回池）靠调用点自己申报，漏一处就对不上账；这份账挂在
 * 池子本身的方法上，谁都绕不过去。路径没设上就记成 untagged 并抄一次调用栈，
 * 停滞日志据此指认是谁在提料。
 *
 * <p><b>挂在哪些方法上（台账 #87）</b>：不只 AE2 基类那两条 {@code long} 口。物理池换装成
 * {@link git.chexson.chexsonsaeutils.bigint.WideCraftingInventory} 之后多出一对宽域
 * 直写／直取口（{@code extractExact}／{@code insertExact}），它们走侧表那条内部路径、
 * <b>不经过</b>基类的 {@code extract(long)}/{@code insert(long)}。只挂基类那两条口的结果是：
 * 退网交付（{@code ParallelCraftingCpuLogic#storeWideInventory} 每轮把残池分轮回投网络）与
 * 续传读回的越界条目，在 {@code in}／{@code out} 两格里<b>整段隐身</b>——不丢料，但本仓
 * 按 {@code debug.log} 定性时会把"池子被人抽走一批"读成"没人伸手"。现在四条口挂在同一本账上
 * （唯一构造口 {@code ParallelCraftingCpuLogic#newLedgerBackedPool}），宽域那一笔与 {@code long}
 * 那一笔共用下面同一条饱和合并（报小、不许回绕），不新开第二把尺。
 */
final class ParallelPoolLedger {

    static final String PATH_NONE = "untagged";

    /**
     * 达顶那一格的读法（<b>标记，不是量</b>）：这本账饱和在 {@link Long#MAX_VALUE}，
     * 读出那个数分不清"真这么多"与"夹在顶上"，一律按这个标记印。
     */
    static final String SATURATED_READOUT = "<sat>";

    private final Map<String, KeyCounter> outByPath = new LinkedHashMap<>();
    private final Map<String, KeyCounter> inByPath = new LinkedHashMap<>();
    private final Map<String, String> siteByPath = new HashMap<>();
    private String path = PATH_NONE;
    /**
     * NeoEco 这条腿的出账对照读数（纯读数，不进任何判据）：同一批的
     * {@code 键:池子宽域差值/reported=对方 long 读数} 配对。
     */
    private String ecoOutPair = "";

    void noteEcoOut(@Nullable String pair) {
        if (pair != null && !pair.isEmpty()) {
            ecoOutPair = pair;
        }
    }

    /** 停滞行的 ecoOut= 读数：没有推过 NeoEco 批次就是空串。 */
    String ecoOut() {
        return ecoOutPair;
    }

    /**
     * NeoEco 请求侧<b>折回发数</b>这一条腿的纯读数（{@code #dispatchFiringsInsideQuota} 把宿主
     * 申报的发数折回份额内那一发数）：本 tick 折了几次、合计折掉几发。
     *
     * <p>为什么要单列：{@code affordable <= 1 ⇒ return 0L} 与"预算耗尽 / {@code NOT_BATCHED} /
     * 宿主交回 {@code null}"在既有读数上<b>逐字同形</b>——停滞行分不出"份额把请求折光了"与
     * "压根没问到批"。这里只是把已经发生的那一步数出来，随 tick 现算（{@link #reset()} 清）。
     * <b>不参与任何判据</b>：不得据此早退、拒绝、夹发数。
     */
    private long ecoFoldTimes;
    private long ecoFoldFirings;

    void noteEcoFold(long foldedFirings) {
        if (foldedFirings <= 0L) {
            return;
        }
        ecoFoldTimes = ecoFoldTimes == Long.MAX_VALUE ? ecoFoldTimes : ecoFoldTimes + 1L;
        ecoFoldFirings = ecoFoldFirings > Long.MAX_VALUE - foldedFirings
                ? Long.MAX_VALUE : ecoFoldFirings + foldedFirings;
    }

    /** 停滞行的 ecoFold= 读数：这一 tick 没折过就是空串（与"折掉 0 发"分得开）。 */
    String ecoFold() {
        return ecoFoldTimes == 0L ? "" : ecoFoldTimes + "/" + ecoFoldFirings;
    }

    /** 切到 next 路径，返回上一条路径（调用点用完即还原）。 */
    String path(@Nullable String next) {
        String previous = path;
        path = next == null ? PATH_NONE : next;
        return previous;
    }

    void restore(@Nullable String previous) {
        path = previous == null ? PATH_NONE : previous;
    }

    void noteOut(@Nullable AEKey what, long amount) {
        book(outByPath, what, amount);
    }

    void noteIn(@Nullable AEKey what, long amount) {
        book(inByPath, what, amount);
    }

    /**
     * 宽域直写／直取口的出账（{@code extractExact} 那一族）：<b>同一本账、同一条饱和口径</b>，
     * 不新开第二把尺（台账 #87）。
     */
    void noteOut(@Nullable AEKey what, @Nullable BigInteger amount) {
        book(outByPath, what, amount);
    }

    /** 同上，入账侧（{@code insertExact} 越出 {@code long} 的那一截）。 */
    void noteIn(@Nullable AEKey what, @Nullable BigInteger amount) {
        book(inByPath, what, amount);
    }

    private void book(Map<String, KeyCounter> ledger, @Nullable AEKey what, long amount) {
        book(ledger, what, BigInteger.valueOf(amount));
    }

    private void book(Map<String, KeyCounter> ledger, @Nullable AEKey what, @Nullable BigInteger amount) {
        if (what == null || amount == null || amount.signum() <= 0L) {
            return;
        }
        var held = ledger.computeIfAbsent(path, ignored -> new KeyCounter());
        // 分批投料之后同一条路径上同一键会被记 N 笔 ≤long：KeyCounter.add 走 fastutil，越界是
        // **回绕**不是饱和，in{submit} 会报成负数或小得离谱的数——诊断读数一失真，排查丢料就跟着错。
        // 这里按天花板夹住：宁可报小，不许回绕。宽域那一笔先夹成这一个 long 再进同一条合并。
        long delta = amount.compareTo(WideCeilings.LONG_ACCOUNT) > 0
                ? Long.MAX_VALUE : amount.longValue();
        long before = held.get(what);
        held.set(what, before > Long.MAX_VALUE - delta ? Long.MAX_VALUE : before + delta);
        if (PATH_NONE.equals(path)) {
            siteByPath.computeIfAbsent(PATH_NONE, ignored -> captureSite());
        }
    }

    long out(@Nullable AEKey what) {
        return look(outByPath, what);
    }

    long in(@Nullable AEKey what) {
        return look(inByPath, what);
    }

    private static long look(Map<String, KeyCounter> ledgers, @Nullable AEKey what) {
        if (what == null) {
            return 0L;
        }
        long total = 0L;
        for (KeyCounter counter : ledgers.values()) {
            long amount = counter.get(what);
            if (amount > 0L && total > Long.MAX_VALUE - amount) {
                return Long.MAX_VALUE;
            }
            total += amount;
        }
        return total;
    }

    void reset() {
        outByPath.clear();
        inByPath.clear();
        siteByPath.clear();
        path = PATH_NONE;
        ecoOutPair = "";
        ecoFoldTimes = 0L;
        ecoFoldFirings = 0L;
    }

    /** 各路径的进出总量与样例键：账对不上时按这张表点名吃料的路径。 */
    String describePaths() {
        var described = new StringBuilder();
        appendLedger(described, "out", outByPath);
        appendLedger(described, "in", inByPath);
        String site = siteByPath.get(PATH_NONE);
        if (site != null) {
            described.append(" site=").append(site);
        }
        return described.length() == 0 ? "" : "[" + described + "]";
    }

    /**
     * 单键逐路径进出量（台账 #226 笔 3b 的读口）：{@link #describePaths()} 只报每条路径的全局
     * 总量与一个样例键，点名不到"这个缺口键是被哪条路送进／送出池子"。停滞行的
     * {@code poolKeys=} 那一格读的就是这一本（唯一生产调用点在
     * {@code ParallelCraftingCpuLogic.StallProbe#describePoolKeyCells}）。
     *
     * <p><b>形状</b>：{@code 进{路径=量,路径=量}/出{路径=量}}——一格一 token（整串零空白，
     * 路径之间用 {@code ","}，进／出两段用 {@code "/"} 分隔），一门没记到就如实印空花括号
     * {@code 进{}/出{}}（那是"零笔"，不是量）。
     *
     * <p><b>这本账是窄域饱和 {@code long}</b>（{@link #book}：宁可报小、越界夹在
     * {@link Long#MAX_VALUE}），没有任何宽域出口 ⇒ 下游那一格必须自报 {@code view=narrow}，
     * 而且读到达顶那一格时<b>不许把饱和值当量印</b>（用 {@link #SATURATED_READOUT} 顶上去）。
     *
     * <p><b>窗</b>：这一本只在 {@link #reset()} 时清零，而 {@code reset()} 只在<b>提交那一刻</b>
     * 调（{@code ParallelCraftingCpuLogic#trySubmitJob}，时机一字未动）⇒ 交出来的数是从那次提交
     * 起<b>累计</b>到现在的进出量，不是本轮、也不是百轮窗。下游格子把这一条写在 {@code since=reset}
     * 那一个子字段里。
     */
    String describeKey(@Nullable AEKey key) {
        if (key == null) {
            return "";
        }
        var described = new StringBuilder();
        appendKeyLedger(described, "进", inByPath, key);
        appendKeyLedger(described, "出", outByPath, key);
        // 两段都没记到也交出 "进{}/出{}"：这一格在场、读数是零笔，与"这一格没印"分得开。
        if (described.length() == 0) {
            return "进{}/出{}";
        }
        return described.toString();
    }

    private static void appendKeyLedger(StringBuilder described, String label,
            Map<String, KeyCounter> ledger, AEKey key) {
        var parts = new StringBuilder(64);
        for (var entry : ledger.entrySet()) {
            long amount = entry.getValue().get(key);
            if (amount <= 0L) {
                continue;
            }
            if (parts.length() > 0) {
                parts.append(',');
            }
            // 达顶那一格按标记印，不按量印：这本账是饱和 long（见 describeKey 的注释），
            // 读出 Long.MAX_VALUE 分不清"真这么多"与"夹在顶上"——顶格哨兵不许冒充量。
            parts.append(entry.getKey()).append('=')
                    .append(amount == Long.MAX_VALUE ? SATURATED_READOUT : Long.toString(amount));
        }
        if (parts.length() == 0) {
            return;
        }
        if (described.length() > 0) {
            described.append('/');
        }
        described.append(label).append('{').append(parts).append('}');
    }

    private static void appendLedger(StringBuilder described, String label,
            Map<String, KeyCounter> ledger) {
        if (ledger.isEmpty()) {
            return;
        }
        if (described.length() > 0) {
            described.append(' ');
        }
        described.append(label).append('{');
        boolean first = true;
        for (var entry : ledger.entrySet()) {
            if (!first) {
                described.append(", ");
            }
            first = false;
            long total = 0L;
            AEKey sampleKey = null;
            long sampleAmount = 0L;
            for (var stored : entry.getValue()) {
                total = ClampedMath.addUp(total, stored.getLongValue());
                if (sampleAmount < stored.getLongValue()) {
                    sampleKey = stored.getKey();
                    sampleAmount = stored.getLongValue();
                }
            }
            described.append(entry.getKey()).append('=').append(total);
            if (sampleKey != null) {
                described.append('(').append(sampleKey).append(" x ").append(sampleAmount).append(')');
            }
        }
        described.append('}');
    }

    private static String captureSite() {
        try {
            return StackWalker.getInstance().walk(frames -> frames
                    .map(frame -> frame.getClassName() + '#' + frame.getMethodName()
                            + ':' + frame.getLineNumber())
                    .filter(line -> !line.startsWith(ParallelPoolLedger.class.getName()))
                    .limit(4)
                    .reduce((left, right) -> left + "<-" + right)
                    .orElse(""));
        } catch (RuntimeException e) {
            return "unavailable";
        }
    }
}
