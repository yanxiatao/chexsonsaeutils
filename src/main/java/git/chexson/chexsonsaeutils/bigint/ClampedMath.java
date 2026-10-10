package git.chexson.chexsonsaeutils.bigint;

/**
 * 数域钳位加法（K2）。两档三方法，档位差别只在极性，不在便利。
 *
 * <ul>
 *   <li>{@link #add(long, long)}：<b>双向饱和</b>——正向越顶夹在 MAX、
 *       负向越底夹在 MIN。逐字复刻现网 {@code saturatingAdd(long,long)} 的四份同形实现
 *       （{@code DirectProcessingStackSupport#saturatingAdd}、
 *       {@code AEDirectProcessingMachineBlockEntity#saturatingAdd}、
 *       {@code ProcessingLatencyOrigin#saturatingAdd}、{@code HostChainSegmenter#saturatingAdd}）。</li>
 *   <li>{@link #addUp(long, long)} / {@link #addUp(int, int)}：<b>只夹上界</b>——正向越顶夹在 MAX，
 *       负方向如实相加（不夹底、不回绕补偿），溢出点与现网单向档逐字一致，
 *       即 {@code right > 0} 且 {@code left >= MAX - right} 才夹。</li>
 * </ul>
 *
 * <p>重载即档位：{@link #addUp(int, int)} 存在，所以两个 {@code int} 实参只会走 {@code int} 档、
 * 夹在 {@code Integer.MAX_VALUE}，不会悄悄升到 {@code long} 档夹在 {@code Long.MAX_VALUE}。
 * {@link #add(long, long)} 只有 {@code long} 档——现网双向饱和的实现全在 {@code long} 上，
 * 没有 {@code int} 档调用者，所以 K3 把那个 0 调用者的重载删了：给它 {@code int} 实参会加宽到
 * {@code long} 档、夹在 {@code Long.MAX_VALUE}，这是删档后唯一一条要记住的档位差别。
 * 上述两条由 {@code ClampedMathTest} 的漂移钉子钉住。
 *
 * <p><b>为什么只有 add 两档，不落 {@code sub}／{@code mul}</b>：现网的减法只有一份
 * {@code ParallelPlanReservation#saturatingSubtract} 的"夹 0 底"版（业务底，不是数域底），
 * 乘法有六份且全是"非正⇒0／⇒1"版（{@code saturatingMultiply} 一族，含溢出即判不可用的
 * {@code multiplyOrZero} 型），两者都不是数域钳位——把它们折进本类会立刻造出 0 调用者的死口，
 * 还会把"夹业务底"的判据静默改成"夹数域顶"。等真有数域语义的减法／乘法调用者时（K6）再按同一
 * 极性纪律补档，不预先造便捷重载。
 *
 * <p>本类无状态、无日志、无开关；钳位值就是类型顶底，不是业务配额。
 */
public final class ClampedMath {

    private ClampedMath() {
    }

    /**
     * 双向饱和加法（long 档）：正向夹 {@link Long#MAX_VALUE}，负向夹 {@link Long#MIN_VALUE}。
     */
    public static long add(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    /**
     * 只夹上界的加法（long 档）：正向越顶夹在 {@link Long#MAX_VALUE}，负方向如实相加不夹底。
     */
    public static long addUp(long left, long right) {
        if (right > 0L && left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * 只夹上界的加法（int 档）：正向越顶夹在 {@link Integer#MAX_VALUE}，负方向如实相加不夹底。
     */
    public static int addUp(int left, int right) {
        if (right > 0 && left >= Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }
}
