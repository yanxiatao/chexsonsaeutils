package git.chexson.chexsonsaeutils.integration.ae2ct;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import appeng.api.stacks.GenericStack;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * ae2ct 那份计划数据的反射桥：只在运行期按"形状"认 ae2ct 注入在
 * {@code appeng.menu.me.crafting.CraftingPlanSummary} 上的那对 {@code getJob/setJob}，
 * 编译期与运行期都不引用 {@code com.neuvillette.ae2ct} 里的任何类。
 *
 * <p>为什么要这座桥：ae2ct 给 {@code write} 挂的是无条件 {@code jobs.write(buffer)} 的
 * TAIL 注入——字段没填就是 NPE，AE2 的 {@code ae2:craft_confirm_plan} 编码失败会直接把
 * 整条连接掐断。而我们的计划本来就不是 {@code appeng.crafting.CraftingPlan}
 * （{@code RecursiveCraftingPlan}/{@code WideAmountCraftingPlan} 是 record 实现的
 * {@code ICraftingPlan}），残缺计划还要经 {@code PartialPlanMissingSummary} 重建一个
 * 全新对象，两条路都能让那个字段停在 null 上。
 *
 * <p>两条硬规矩：
 * <ul>
 *   <li>没装 ae2ct：目标类上没有那对方法，返回 {@link Outcome#NO_AE2CT_SHAPE}，一个字节都不改；</li>
 *   <li>反射自身出任何问题：返回 {@link Outcome#FAILED_SILENT}，同样什么都不做，
 *       异常绝不漏回编码路径。</li>
 * </ul>
 *
 * <p>识别条件刻意收紧到与 {@code com.neuvillette.ae2ct.api.RecipeHelper} 同形
 * （公开构造器 {@code (GenericStack, List)}、实例 {@code write(RegistryFriendlyByteBuf)}、
 * 静态 {@code read(RegistryFriendlyByteBuf)}），免得认错别的 addon 注入的同名方法。
 */
public final class Ae2ctJobBridge {

    /** 桥的处置结果；调用方一律忽略，只给用例断言用。 */
    public enum Outcome {
        /** 目标对象没有 ae2ct 那套形状（没装 ae2ct），什么都没做 */
        NO_AE2CT_SHAPE,
        /** 字段本来就有值，原样放过 */
        UNCHANGED,
        /** 字段为空，填进去一个空的合法 recipe helper */
        FILLED_EMPTY,
        /** 把原对象上的 job 搬到了新对象上 */
        COPIED,
        /** 反射本身出问题了：静默放行，什么都没做 */
        FAILED_SILENT
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 这条计划要不要由 ae2ct 兼容层接管：原生 {@code appeng.crafting.CraftingPlan} 交回 AE2
     * 与 ae2ct 自己跑；其余一律由我们造 summary 并把 helper 填好。
     *
     * <p>判据是「ae2ct 认不认得这条计划」：它在 {@code fromJob} 的 TAIL 上无条件把
     * {@code ICraftingPlan} 强转成 {@code CraftingPlan}，我们的宽域/递归载体不是它的子类——
     * 放下去要么 CCE 被 AE2 吞成「Failed to start crafting job」（预览出不来），
     * 要么留下没填 helper 的 summary 在 {@code write} 上把连接掐断。
     */
    public static boolean takesOver(appeng.api.networking.crafting.ICraftingPlan job) {
        return job != null && !(job instanceof appeng.crafting.CraftingPlan);
    }

    private static final String GET_JOB = "getJob";
    private static final String SET_JOB = "setJob";
    private static final String WRITE = "write";
    private static final String READ = "read";

    /** 认不出形状时的哨兵，按目标类缓存，避免每次发包都翻一遍方法表。 */
    private static final Shape UNSUPPORTED = new Shape(null, null, null);
    private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();

    private static final AtomicBoolean FILLED_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FAILED_LOGGED = new AtomicBoolean();

    private Ae2ctJobBridge() {
    }

    /**
     * {@code write} 的 HEAD 兜底：ae2ct 的 job 字段为空时塞一个空的合法 helper，
     * 让它的 TAIL 注入 {@code jobs.write(buffer)} 不再 NPE。
     *
     * <p>空 helper 的线格式与 {@code read} 对称：{@code output=null} 写成 {@code false}、
     * recipes 写成 {@code varint 0}，客户端只会看到一棵空的合成树，连接照常。
     */
    public static Outcome fillEmptyJobWhenMissing(@Nullable Object summary) {
        if (summary == null) {
            return Outcome.NO_AE2CT_SHAPE;
        }
        try {
            Shape shape = shapeOf(summary.getClass());
            if (!shape.supported()) {
                return Outcome.NO_AE2CT_SHAPE;
            }
            if (shape.getJob().invoke(summary) != null) {
                return Outcome.UNCHANGED;
            }
            Object emptyJob = shape.jobConstructor().newInstance(null, new ArrayList<>());
            shape.setJob().invoke(summary, emptyJob);
            if (FILLED_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("[chexsonsaeutils] ae2ct recipe helper was null before CraftingPlanSummary.write "
                        + "on {}; installed an empty one so ae2:craft_confirm_plan can still be encoded. "
                        + "The ae2ct crafting-tree view stays empty for this plan; "
                        + "further occurrences are logged at debug level.",
                        summary.getClass().getName());
            } else {
                LOGGER.debug("[chexsonsaeutils] ae2ct recipe helper still null on {}, empty one installed again",
                        summary.getClass().getName());
            }
            return Outcome.FILLED_EMPTY;
        } catch (Throwable failure) {
            warnOnce(failure);
            return Outcome.FAILED_SILENT;
        }
    }

    /**
     * 重建概要时把 job 搬过去：{@code PartialPlanMissingSummary} 会 new 一个全新的
     * {@code CraftingPlanSummary}，ae2ct 那份 recipe helper 挂在旧对象上，不搬就丢——
     * 丢了要么编码 NPE、要么合成树变空。原对象没有 job 时什么都不做。
     */
    public static Outcome copyJob(@Nullable Object from, @Nullable Object to) {
        if (from == null || to == null || from == to) {
            return Outcome.NO_AE2CT_SHAPE;
        }
        try {
            Shape fromShape = shapeOf(from.getClass());
            Shape toShape = shapeOf(to.getClass());
            if (!fromShape.supported() || !toShape.supported()) {
                return Outcome.NO_AE2CT_SHAPE;
            }
            Object job = fromShape.getJob().invoke(from);
            if (job == null) {
                return Outcome.NO_AE2CT_SHAPE;
            }
            if (toShape.getJob().invoke(to) != null) {
                return Outcome.UNCHANGED;
            }
            toShape.setJob().invoke(to, job);
            return Outcome.COPIED;
        } catch (Throwable failure) {
            warnOnce(failure);
            return Outcome.FAILED_SILENT;
        }
    }

    private static Shape shapeOf(Class<?> summaryClass) {
        return SHAPES.computeIfAbsent(summaryClass, Ae2ctJobBridge::resolveShape);
    }

    private static Shape resolveShape(Class<?> summaryClass) {
        try {
            Method getJob = summaryClass.getMethod(GET_JOB);
            Class<?> jobType = getJob.getReturnType();
            Method setJob = summaryClass.getMethod(SET_JOB, jobType);
            if (getJob.getParameterCount() != 0
                    || Modifier.isStatic(getJob.getModifiers())
                    || Modifier.isStatic(setJob.getModifiers())) {
                return UNSUPPORTED;
            }
            if (jobType == void.class || jobType.isPrimitive() || jobType.isArray()
                    || jobType.getName().startsWith("java.")
                    || jobType.getName().startsWith("net.minecraft.")
                    || jobType.getName().startsWith("appeng.")) {
                return UNSUPPORTED;
            }
            Constructor<?> jobConstructor = jobType.getConstructor(GenericStack.class, List.class);
            Method jobWrite = jobType.getMethod(WRITE, RegistryFriendlyByteBuf.class);
            Method jobRead = jobType.getMethod(READ, RegistryFriendlyByteBuf.class);
            if (Modifier.isStatic(jobWrite.getModifiers()) || !Modifier.isStatic(jobRead.getModifiers())) {
                return UNSUPPORTED;
            }
            makeAccessible(getJob);
            makeAccessible(setJob);
            makeAccessible(jobConstructor);
            return new Shape(getJob, setJob, jobConstructor);
        } catch (Throwable unrecognized) {
            // 没装 ae2ct 时走到这就是 NoSuchMethodException，属正常路径。
            return UNSUPPORTED;
        }
    }

    private static void makeAccessible(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // 模块化环境拒绝也没关系：invoke 那步会走到 FAILED_SILENT。
        }
    }

    private static void warnOnce(Throwable failure) {
        if (FAILED_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("[chexsonsaeutils] could not inspect ae2ct's recipe helper via reflection; "
                    + "leaving the crafting plan summary untouched", failure);
        }
    }

    /** 反射句柄三元组；{@code getJob == null} 即"认不出 ae2ct 形状"。 */
    private record Shape(@Nullable Method getJob, @Nullable Method setJob, @Nullable Constructor<?> jobConstructor) {

        boolean supported() {
            return getJob != null && setJob != null && jobConstructor != null;
        }
    }
}
