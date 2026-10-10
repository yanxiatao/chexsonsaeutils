package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobTaskProgressAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 递归样板任务排序。
 *
 * <p>递归作业优先推送能产出内部催化物的样板，避免下游样板抢走最后一个 seed。
 */
public final class RecursiveTaskOrdering {
    private RecursiveTaskOrdering() {
    }

    /**
     * 把 AE2 普通 CPU 的任务表重排为递归友好顺序。
     */
    public static void reorderMixinTaskMap(Map<IPatternDetails, Object> tasks, KeyCounter internalItems) {
        if (tasks == null || tasks.size() <= 1 || internalItems == null || internalItems.isEmpty()) {
            return;
        }
        List<Map.Entry<IPatternDetails, Object>> entries = new ArrayList<>(tasks.entrySet());
        entries.sort(Comparator.<Map.Entry<IPatternDetails, Object>>comparingInt(
                        entry -> refundsInternalItem(entry.getKey(), internalItems) ? 0 : 1)
                .thenComparingInt(entry -> consumesInternalItem(entry.getKey(), internalItems) ? 1 : 0));
        Map<IPatternDetails, Object> ordered = new LinkedHashMap<>();
        for (Map.Entry<IPatternDetails, Object> entry : entries) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        tasks.clear();
        tasks.putAll(ordered);
    }

    /**
     * 返回 parallel CPU 当前应该优先尝试的任务列表。
     */
    public static <T> List<Map.Entry<IPatternDetails, T>> orderedEntries(
            Map<IPatternDetails, T> tasks,
            KeyCounter internalItems
    ) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<IPatternDetails, T>> entries = new ArrayList<>(tasks.entrySet());
        if (internalItems == null || internalItems.isEmpty()) {
            return entries;
        }
        entries.sort(Comparator.<Map.Entry<IPatternDetails, T>>comparingInt(
                        entry -> refundsInternalItem(entry.getKey(), internalItems) ? 0 : 1)
                .thenComparingInt(entry -> consumesInternalItem(entry.getKey(), internalItems) ? 1 : 0));
        return entries;
    }

    /**
     * 判断样板当前是否应被内部催化物保留规则阻止。
     */
    public static boolean shouldDeferConsumer(
            IPatternDetails pattern,
            KeyCounter internalItems,
            ICraftingInventory inventory
    ) {
        return shouldDeferConsumer(pattern, internalItems, inventory, null);
    }

    /**
     * 当前样板执行后仍须留在 CPU 内的递归内部物。
     *
     * <p>环内回补链、能把内部物原量转回来的样板不受保留下限约束；普通消费者的单份守门
     * 与 {@link #shouldDeferConsumer(IPatternDetails, KeyCounter, ICraftingInventory, Map)}
     * 共用此表，避免两套豁免规则漂移。
     *
     * <p><b>这一把尺现在只剩 AE2 原生 CPU 那一腿在读</b>（{@code mixin/ae2/crafting
     * /CraftingCpuLogicRecursivePlanMixin}）：自有并行 CPU 这一路的环种守门已经合流到取料点上的
     * 那一份留底（{@code ParallelPlanReservation#spendableAfterRingSeedReserve}），任务循环头上那道
     * 单发让路已删——同一判据两份实现就是漂移的入口，覆盖面与力度都由留底那一把更宽更严。
     *
     * <p>每发消耗表在本方法里<b>只建一次</b>（{@link #perFiringConsumesOf}），逐内部键复用；
     * 外面那圈 {@code internalItems} 每键都要问"这张吃不吃 K""这张还不还得上 K"，
     * 把建表留在键循环里就成了每 tick 每键每样板重建一张 map。
     */
    public static KeyCounter requiredPostExecutionReserve(
            IPatternDetails pattern,
            KeyCounter internalItems,
            @Nullable Map<IPatternDetails, ?> tasks
    ) {
        if (pattern == null || internalItems == null || internalItems.isEmpty()) {
            return new KeyCounter();
        }
        return requiredPostExecutionReserve(pattern, internalItems, tasks, perFiringConsumesOf(pattern));
    }

    /** 已算好每发消耗表的那一路：一张样板一次建表，复用给本次调用里的所有内部物键。 */
    private static KeyCounter requiredPostExecutionReserve(
            IPatternDetails pattern,
            KeyCounter internalItems,
            @Nullable Map<IPatternDetails, ?> tasks,
            Map<AEKey, Long> consumesByPattern
    ) {
        KeyCounter reserve = new KeyCounter();
        if (pattern == null || internalItems == null || internalItems.isEmpty()) {
            return reserve;
        }
        for (var internalItem : internalItems) {
            AEKey key = internalItem.getKey();
            long retainedAmount = internalItem.getLongValue();
            if (key == null
                    || retainedAmount <= 0L
                    || !consumesKey(consumesByPattern, key)
                    || refundsKey(pattern, key, consumesByPattern)
                    || (tasks != null
                            && feedsPendingInternalProducer(pattern, key, tasks.entrySet()))) {
                continue;
            }
            reserve.add(key, retainedAmount);
        }
        return reserve;
    }

    /**
     * 判断样板当前是否应被内部催化物保留规则阻止。
     *
     * 当当前样板会直接喂给仍待执行的内部生产样板时，它属于递归环本身，不能被保留规则延后。
     *
     * <p>与 {@link #requiredPostExecutionReserve(IPatternDetails, KeyCounter, Map, Map)} 共用
     * <b>同一份</b>每发消耗表：这一路对外是每 tick × 每张样板跑一次，两张表就是白建的一倍成本。
     */
    public static boolean shouldDeferConsumer(
            IPatternDetails pattern,
            KeyCounter internalItems,
            ICraftingInventory inventory,
            @Nullable Map<IPatternDetails, ?> tasks
    ) {
        if (pattern == null || internalItems == null || internalItems.isEmpty() || inventory == null) {
            return false;
        }
        Map<AEKey, Long> consumesByPattern = perFiringConsumesOf(pattern);
        for (var internalItem : requiredPostExecutionReserve(
                pattern, internalItems, tasks, consumesByPattern)) {
            AEKey key = internalItem.getKey();
            long retainedAmount = internalItem.getLongValue();
            long requiredByPattern = inputAmount(consumesByPattern, key);
            long available = inventory.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE);
            long remainingAfterPattern = available <= requiredByPattern ? 0L : available - requiredByPattern;
            if (remainingAfterPattern < retainedAmount) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本样板是否把"还回这一枚内部物"的那张待办 producer 的口粮喂给了它。
     *
     * <p><b>单发让路只用这一条判据</b>（{@link #requiredPostExecutionReserve}）：链上的一环不许被
     * 自己的下游夹住。取料点上的环种留底<b>不</b>走这里——它的豁免名单是"种键所在<b>环分量的成员</b>"，
     * 由 {@link RingSeedLedger#seedDemandsByFamily()} 随每发消耗一起交出（分量只在那一处算）。
     *
     * <p>入参收 {@link Iterable} 而不是 {@link Map}：让路那一路每 tick 手里是任务表的
     * <b>序</b>（推送顺位排好的条目列表），不是那张身份表本身；这一问句只遍历、从不按键查表。
     */
    private static <T> boolean feedsPendingInternalProducer(
            IPatternDetails pattern,
            AEKey internalItem,
            @Nullable Iterable<Map.Entry<IPatternDetails, T>> tasks
    ) {
        if (pattern == null || internalItem == null || tasks == null || !tasks.iterator().hasNext()) {
            return false;
        }
        for (GenericStack output : pattern.getOutputs()) {
            if (output == null || output.what() == null || output.amount() <= 0L) {
                continue;
            }
            // 输出即内部物本身同样豁免：待办 producer 净产出该内部物，
            // 本任务是在往补库方向产，此时 defer 会与缺料的 producer
            // 形成双向等待死锁（consumer 等 producer 产，producer 等
            // consumer 的输出）。原 `!matches` 排除无语义必要。
            if (pendingProducerConsumesKey(tasks, output.what(), internalItem)) {
                return true;
            }
        }
        return false;
    }

    private static <T> boolean pendingProducerConsumesKey(
            Iterable<Map.Entry<IPatternDetails, T>> tasks,
            AEKey key,
            AEKey internalItem
    ) {
        if (tasks == null || key == null || internalItem == null) {
            return false;
        }
        for (Map.Entry<IPatternDetails, T> entry : tasks) {
            IPatternDetails candidate = entry.getKey();
            if (candidate == null || !consumesKey(candidate, key) || !refundsKey(candidate, internalItem)) {
                continue;
            }
            if (taskProgressValue(entry.getValue()) > 0L) {
                return true;
            }
        }
        return false;
    }

    public interface ParallelTaskProgressView {
        /**
         * 任务剩余发数的<b>饱和 {@code long} 镜像</b>（自有那一腿的读数是
         * {@code ParallelExecutingCraftingJob.TaskProgress} 的 {@code value} 那一格）。
         *
         * <p><b>它不参与排序</b>：{@link #orderedEntries} 与 {@link #reorderMixinTaskMap} 一个字都没读它，
         * 那两把尺只看"吃不吃内部物"与"还不还得上内部物"。读它的是<b>存活判据</b>那几处的
         * {@code > 0} 那一句——{@link #hasPendingProducer}、{@link #pendingProducerConsumesKey}
         * 与 {@link #hasPendingTasks}（后两处的发数取自 {@link #taskProgressValue}）。
         *
         * <p>自 {@code cf74bd4} 删掉自有并行 CPU 任务循环头上那道单发让路之后，上面那几处在生产路径上
         * <b>只剩 AE2 原生 CPU 那一腿在调</b>（{@code mixin/ae2/crafting/CraftingCpuLogicRecursivePlanMixin}
         * 的 {@code :159}／{@code :163}／{@code :312}）。而那一腿喂进来的任务表值是 AE2 自己的
         * {@code ExecutingCraftingJob$TaskProgress}，{@link #taskProgressValue} 先命中
         * {@code ExecutingCraftingJobTaskProgressAccessor} 那一支 ⇒ 本视图这一支只剩"表值是自有
         * {@code TaskProgress}"的那一形会走到（现存读家全在用例里）。
         *
         * <p>份数可以越出 {@code long}（任务表宽域），那时本方法交回的是代表值
         * {@code Long.MAX_VALUE}。扣减与判完成一律不准走这里——那是
         * {@code ParallelExecutingCraftingJob.TaskProgress} 的精确账
         * （{@code consumeFirings(long)} 与 {@code isFinished()}）。
         *
         * <p><b>这一镜像只带"剩余发数"一维</b>：取料点那份环种留底自 {@code 87c994c} 起读的是
         * "剩余发数 + 义务账还没交割的发数"（{@link RingSeedLedger#outstandingFirings}），这一腿没有
         * 后面那一维——申报后剩余归零、还挂着欠账的那一张在这里读成 0。
         * <b>这是已知边界，不是待修缺陷</b>：算法与宽域只服务自有 CPU，回落腿保留但不特殊处理，
         * <b>不许</b>顺手给原生那一腿补义务维（那一路压根没有申报批与义务账）。
         */
        long chexsonsaeutils$dyeableRecursiveTaskProgressValue();
    }

    /**
     * 判断任务表里是否还有能把内部催化物转回来的任务。
     */
    public static boolean hasPendingProducer(Map<IPatternDetails, ?> tasks, KeyCounter internalItems) {
        if (tasks == null || tasks.isEmpty() || internalItems == null || internalItems.isEmpty()) {
            return false;
        }
        for (Map.Entry<IPatternDetails, ?> entry : tasks.entrySet()) {
            if (!refundsInternalItem(entry.getKey(), internalItems)) {
                continue;
            }
            Object value = entry.getValue();
            long progress = value instanceof ExecutingCraftingJobTaskProgressAccessor accessor
                    ? accessor.getValue()
                    : value instanceof ParallelTaskProgressView progressView
                            ? progressView.chexsonsaeutils$dyeableRecursiveTaskProgressValue()
                            : 0L;
            if (progress > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断任务表里是否还存在正数剩余次数。
     */
    public static boolean hasPendingTasks(Map<IPatternDetails, ?> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }
        for (Object value : tasks.values()) {
            if (taskProgressValue(value) > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回普通 CPU 任务剩余次数。
     */
    public static long mixinTaskProgressValue(@Nullable Object taskProgress) {
        return taskProgressValue(taskProgress);
    }

    private static long taskProgressValue(@Nullable Object taskProgress) {
        if (taskProgress instanceof ExecutingCraftingJobTaskProgressAccessor accessor) {
            return accessor.getValue();
        }
        if (taskProgress instanceof ParallelTaskProgressView progressView) {
            return progressView.chexsonsaeutils$dyeableRecursiveTaskProgressValue();
        }
        return 0L;
    }

    private static boolean refundsInternalItem(IPatternDetails pattern, KeyCounter internalItems) {
        if (pattern == null || internalItems == null) {
            return false;
        }
        for (var entry : internalItems) {
            if (entry.getKey() != null && refundsKey(pattern, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本样板是否把自己吃掉的那份键原样转回来（产出不小于投入）。
     *
     * <p>自循环环每发吃一枚回一枚，净产出为 0，按"严格净正产出"判会被误认成旁人，
     * 抢在环转起来之前抽走池里那份种子。
     *
     * <p><b>这一条就是本类唯一的"还回 K"判据</b>：单发让路（{@link #requiredPostExecutionReserve}
     * 与 {@link #pendingProducerConsumesKey}）算它。取料点上的环种留底<b>不</b>算它——链式环
     * （A 吃 K 出 L、B 吃 L 出 K）里没有任何样板把物料还给自己，按"还回 K"起算会得到
     * {@code owed(K)=0}、整族不进账；那一边的基准是"K 所在<b>环分量</b>自己的剩余需求"，
     * 分量口径由 {@link RingSeedLedger#seedDemandsByFamily()} 交出。
     */
    private static boolean refundsKey(IPatternDetails pattern, AEKey key) {
        return refundsKey(pattern, key, perFiringConsumesOf(pattern));
    }

    /** 已带每发消耗表的那一路：产出侧照旧现读，投入侧只从表里取读数。 */
    private static boolean refundsKey(IPatternDetails pattern, AEKey key, Map<AEKey, Long> consumes) {
        long produced = outputAmount(pattern, key);
        return produced > 0L && produced >= inputAmount(consumes, key);
    }

    private static boolean consumesInternalItem(IPatternDetails pattern, KeyCounter internalItems) {
        if (pattern == null || internalItems == null) {
            return false;
        }
        for (var entry : internalItems) {
            if (entry.getKey() != null && consumesKey(pattern, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean consumesKey(IPatternDetails pattern, AEKey key) {
        return consumesKey(perFiringConsumesOf(pattern), key);
    }

    /** 已带每发消耗表的那一路：只问表里这一键有没有量，不再重建整样板的表。 */
    private static boolean consumesKey(Map<AEKey, Long> consumes, AEKey key) {
        return inputAmount(consumes, key) > 0L;
    }

    private static long outputAmount(IPatternDetails pattern, AEKey key) {
        long amount = 0L;
        if (pattern == null || key == null) {
            return 0L;
        }
        for (var output : pattern.getOutputs()) {
            if (output != null && output.what() != null && key.matches(output)) {
                amount += output.amount();
            }
        }
        return amount;
    }

    /**
     * 这一张样板的<b>每发消耗表</b>：全仓唯一实现是 {@link RingSeedLedger#perFiringConsumes}，
     * 本方法只加"样板或输入表为空就是空表"那道读数护栏。
     *
     * <p><b>调用位置一律在按内部键的循环之外</b>：一张样板一次建好、复用给本次调用里所有键
     * （{@link #requiredPostExecutionReserve} 与 {@link #shouldDeferConsumer} 各一次）。
     * 把它塞回键循环里，热路就成了每 tick 每键每样板重建一张 map。
     * 这里也<b>不</b>留跨调用缓存：本仓被 {@code IdentityHashMap} 的遍历序与跨实例身份坑过多次。
     */
    private static Map<AEKey, Long> perFiringConsumesOf(IPatternDetails pattern) {
        if (pattern == null || pattern.getInputs() == null) {
            return Map.of();
        }
        return RingSeedLedger.perFiringConsumes(pattern);
    }

    /**
     * 这一张样板<b>一发</b>吃掉该键几枚：同槽多候选是"任选其一"，取槽内该键的最大模板量乘槽倍数，
     * 逐槽相加后饱和到 {@code long}。
     *
     * <p><b>实现只有 {@link RingSeedLedger#perFiringConsumes} 那一份</b>，本方法只是按键取读数：
     * 两份同形状实现（族内取槽内最大候选 × 槽倍数、逐槽相加）此前各写一遍、饱和点不同，
     * 在这一刀的取值域（非负模板量 × 非负槽倍数，AE2 样板的全部实际取值）里恒
     * {@code = min(真值, long 域顶点)}——逐项夹顶再夹顶相加，与宽域相加后再窄化一次同一个数，
     * 所以合并不换语义。合流后"让路那一侧"与"分量口径那一侧"读的是同一把尺。
     *
     * <p>取料点上的环种留底<b>不</b>读这里：它要的"每发消耗"随分量口径一起由
     * {@link RingSeedLedger#seedDemandsByFamily()} 交出，留底那边只剩"还要吃的发数 × 每发消耗"的复加。
     */
    private static long inputAmount(Map<AEKey, Long> consumes, AEKey key) {
        if (key == null) {
            return 0L;
        }
        return consumes.getOrDefault(key, 0L);
    }
}
