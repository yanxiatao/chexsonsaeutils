package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.me.service.CraftingService;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * AE2 交来的那份清单的<b>并列项定序</b>（台账 #140/#143）：把"次序本来由别人家的哈希序决定"的那些
 * 读数，改按可跨 JVM 会话稳定的键定死次序。
 *
 * <h3>管到哪一条路（#143 按事实降级，不许再当成全局）</h3>
 * 本类定死的是 <b>graph 路</b>（自有并行 CPU 的主规划路）的两个读取点：
 * <ul>
 * <li>{@code GraphCompiler.Compilation#craftingFor} 读的<b>提供者清单</b>（{@link #stabilized}）；</li>
 * <li>{@code GraphCompiler.Compilation#resolveNodeKey} 读的<b>模糊代用清单</b>
 * （{@link FuzzyClassView}，台账 #143）。</li>
 * </ul>
 * <b>管不到 AE2 的树路</b>：graph DECLINE 时的回落 {@code OrderedPlanningCalculation#runAe2Native}
 * 用的是 AE2 自己的 {@code CraftingTreeNode}，
 * 它在 {@code CraftingTreeNode.java:86/:126} 读 {@code getCraftingFor}、在 {@code :98} 读
 * {@code getFuzzyCraftable}，都不经本类；它那份份数账 {@code CraftingSimulationState.java:56}
 * 是 {@code HashMap}，交回的 {@code plan.patternTimes()} 因而是哈希序，而
 * {@code ParallelExecutingCraftingJob} 构造任务表时正是按 {@code patternTimes().keySet()} 的遍历序
 * 落底序。这一腿<b>活着</b>且我们够不着——
 * 要把它也定死，需要（二选一）在 {@code CraftingTreeNode} 那两个读取点上做 mixin 把清单穿回本类，
 * 或把 DECLINE 回落换成我们自己的树/清单。<b>在它换成这两者之一以前，凡走树路交出的计划，
 * 顺位仍然随会话翻。</b>
 *
 * <h3>为什么要在我们这一侧定序</h3>
 * {@code getCraftingFor(key)} 的那份清单出自 AE2 的
 * {@code NetworkCraftingProviders.PatternsForKey}（vendored 源码
 * {@code reference-sources/ae2-19.2.17-sources/appeng/me/service/helpers/NetworkCraftingProviders.java}）：
 * <ul>
 * <li>{@code :237} {@code patterns} 是一只 {@code HashSet<PatternInfo>}；</li>
 * <li>{@code :241-247} 只按 {@code state.priority}（提供者的 {@code getPatternPriority()}，
 * {@code :184/:190}）<b>稳定</b>排序后 {@code map + distinct}；</li>
 * <li>⇒ <b>并列 priority 的那一截留的就是 {@code HashSet} 的遍历序</b>，而 {@code PatternInfo} 的哈希
 * 混入 {@code IPatternDetails#hashCode}，后者在 AE2 四条样板实现里一律是 {@code definition.hashCode()}
 * ＝{@code ItemStack.hashItemAndComponents}＝含 {@code Item} 的<b>身份哈希</b>。</li>
 * </ul>
 * 这份清单序就是 {@link GraphCompiler} 建 {@code producers} 的序，也就是求解器的<b>首触序</b>：
 * {@code LinearPlanner#fire} / 环宏发射 → {@code PlanJournal#craftsMerge} →
 * {@code WideCraftCounter#registerSlot} 的槽号 → {@code Ae2PlanConverter} 交出的字典序 →
 * 执行侧任务表底序。所以"注册序"只在<b>转换器以下</b>与哈希无关，graph 路的源头在这两个读取点上
 * 由本类定死；树路的源头还在别人家那只 {@code HashMap} 上（见上一段）。
 *
 * <h3>定序键为什么不用任何哈希</h3>
 * 键取"注册表 id 字符串 + 组件逐项读数 + 样板声明（输出/逐候选的退料键/候选输入/倍率）+ 实现类名"这一串
 * <b>字典序</b>比较。两处差异是台账 #142 补进来的，因为漏掉它们时并列项自己拆不开：
 * <ul>
 * <li>{@code IInput#getRemainingKey}——退料键不同而其余声明逐字相同的两张样板（本仓大量带 leftover
 * 的配方都是这个形状），不写进键就只能由比较返回 0，次序交回来序。台账 #143 MF-1 把这一项改成
 * <b>按候选逐个求</b>：AE2 的退料挂在<b>实际用的那个 template</b> 上
 * （{@code AECraftingPattern.java:629-633} 走 {@code getRecipeRemainder(slot, itemKey)}），
 * 只按首位候选求时"首位候选相同、非首位候选的退料不同"两张样板在这一串上仍并列；</li>
 * <li>{@code AEItemKey} 的组件——{@code AEItemKey#toString}（{@code AEItemKey.java:258-262}）
 * 对带组件的键只报到 {@code "(with patches)"} 为止，同一 item 的多个组件变体（实机染色/精华族
 * 就是这个形状）在这一串上并列。这里按 {@code BuiltInRegistries.DATA_COMPONENT_TYPE} 的注册名
 * 升序逐项拼，值取 {@code DataComponentType#codec()} 对 {@code NbtOps} 的编码结果再<b>自写规范渲染</b>
 * （CompoundTag 的键名单独排序——它自身的迭代序是 String 哈希桶序，不参与定序）。</li>
 * </ul>
 * 两条边界按事实写明（台账 #143 T3）：{@code codec()==null} 的瞬时（transient）组件与编码失败
 * 的值先交回固定标记，标记后面仍续上<b>不依赖 codec</b> 的那一份文本读数（值自己覆写过的
 * {@code toString}）；只有那一档也取不到（该类没覆写 {@code toString}，默认读数就是身份哈希的
 * 十六进制）时才留在并列上——此时这一对确实没有任何可区分的稳定读数，而宁可并列也绝不读哈希。
 * 顺带把一句流传的说法按事实否掉：AE2 的 {@code EncodedCraftingPattern.java:67-72} 与
 * {@code EncodedStonecuttingPattern.java:58-62} 的 {@code hashCode} 里混了
 * {@code ItemStack.hashItemAndComponents}，所以"编码样板带 recipeId 因此哈希跨会话稳"不成立；
 * 而且那两只类是<b>样板物品</b>的编码记录，其哈希根本不是 {@code patternTimes()} 的账本键。
 * 因此这里一个字都不碰 {@code hashCode}/{@code identityHashCode}/内容哈希。
 *
 * <h3>序完全由（优先级，稳定键）决定，与来序无关</h3>
 * 比较器是<b>全序</b>：先按机器优先级降序、同档再按稳定键升序。因此交回的次序与喂进来那份的先后
 * <b>一个字的关系都没有</b>——任何非降序的来序都会被整体重排。这是要的性质，不是副作用：
 * 来序本身就是别人家 {@code HashSet}/{@code KeyCounter} 的哈希序（第三方给的清单序没有值得保住的语义），
 * 保住它等于保住一次会话的运气。要"与来序无关"就必须全序，tie-only 的"只动并列项"做不到——
 * 那种实现下非并列项仍随来序漂。
 * <p>机器优先级仍是玩家意图，而且是<b>第一档尺</b>：它先于稳定键参与比较，
 * 不同优先级之间的先后就是 AE2 的"高优先级在前"，稳定键不许把它们换过来
 * （{@code GraphCompilerProviderTieOrderTest} 那条"反着喂两张不同优先级 ⇒ 高优先级在前"的臂
 * 钉的就是这一档）。优先级读得到才动这份清单——{@code priorityReadable} 为假
 * （既不是 AE2 的 {@code CraftingService}、也没人报优先级）时<b>原样交回</b>，
 * 免得把别人家的优先级意图打乱。这不是第三把尺：路线顺位那两把尺
 * （规划侧注册序 + {@code RecursiveTaskOrdering}）一律不动。
 *
 * <h3>代价</h3>
 * 只在"这个键有多张样板"时才付一趟定序，且每个键一次编译只算一次（{@code GraphCompiler} 的
 * provider 记忆化）；单样板键零分配零字符串。补进退料键与组件读数没有改变这两条：
 * 稳定键仍是<b>每张样板一次</b>（{@code keys.computeIfAbsent}），不是每次比较重算。
 * 退料段按候选逐个求，代价就是<b>每张样板每个候选一次</b> {@code getRemainingKey}。
 *
 * <p>但"每张样板一次"在 {@code stabilized} 那一层只是<b>一次排序</b>内的一次：一次编译里同一张
 * 样板会出现在<b>每一个</b>它参与的键的那份清单上（{@code GraphCompiler.craftingFor} 的记忆化按
 * 键存），于是这一张样板的退料段被整段重算一遍又一遍——而 AE2 那条实现自己注明了它更适合被缓存
 * （{@code AECraftingPattern.java:281-298}："Consider making this more efficient in the future?
 * (e.g. cache the produced remainders)"，每次调用会重排网格并跑 {@code recipe.getRemainingItems}）。
 * 台账 #143 T4 因此把这一份读数的记忆收在<b>本类内部</b>（{@link #STABLE_ORDER_KEYS}）：
 * 一次编译内每张样板的 {@code getRemainingKey} 只走一趟候选表。不开对外的缓存口、不改 AE2 侧——
 * 要不要在 {@code AECraftingPattern} 里缓存退料本体仍归 AE2 那一侧定，这里只保证本类不再重复问。
 */
public final class ProviderPatternOrder {

    /**
     * 稳定键在<b>本类内部</b>的一次编译级记忆（台账 #143 T4）。
     *
     * <p>键是样板实例本身、按<b>身份</b>弱引用（guava {@code MapMaker#weakKeys}）：
     * 不用 {@code equals} 定身位，因为 AE2 四条样板实现的 {@code equals} 只看
     * {@code 类 + definition}——配方重算后换一张同 definition 的新实例进来，按 {@code equals}
     * 查就会领到旧声明算出来的那份串。身份弱引用让样板一被网络丢掉这一格就跟着走，
     * 于是既不漏读数也不占内存。值是纯由该实例的声明算出的字符串（同实例上恒等），
     * 所以并发下偶尔重算一次也无害，这里不为它加锁。
     */
    private static final java.util.concurrent.ConcurrentMap<IPatternDetails, String> STABLE_ORDER_KEYS =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private ProviderPatternOrder() {
    }

    /**
     * 能报出"这张样板在网格上的机器优先级"的清单来源。
     *
     * <p>生产侧由 {@code GraphPlanCapture} 的录制代理实现（它把查询穿回真网格）；测试侧由
     * {@code FakePlanGrid.FakeCraftingService} 实现——AE2 真网格不需要它，
     * 那条路直接走 {@link CraftingService#getProviders(IPatternDetails)}。
     * 录制代理的存在意义是：记进工件的那份清单必须与实机真正用过的那份<b>同一个次序</b>，
     * 否则回放侧的路线序与实机不同。
     */
    public interface PatternPrioritySource {
        /** 持有这张样板的提供者里最高的那个优先级；没有提供者按 AE2 的默认优先级 0。 */
        int patternPriority(IPatternDetails pattern);
    }

    /** 这份清单能不能定序：读得到机器优先级才动它。 */
    public static boolean priorityReadable(@Nullable ICraftingService service) {
        return service instanceof CraftingService || service instanceof PatternPrioritySource;
    }

    /**
     * 按"机器优先级降序 + 稳定键升序"交出这份清单：次序完全由这两档决定，与喂进来的先后无关
     * （全序，见类抬头"序完全由（优先级，稳定键）决定"）。
     *
     * @param service 清单的来源（同时是优先级来源）
     * @param raw     AE2 交来的原样清单
     */
    public static List<IPatternDetails> stabilized(@Nullable ICraftingService service,
            Collection<IPatternDetails> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() < 2 || !priorityReadable(service)) {
            // 单样板键没有并列可言；读不到优先级就原样交回（见类抬头"优先级是第一档尺"那一段）
            return raw instanceof List<IPatternDetails> alreadyList ? alreadyList : List.copyOf(raw);
        }
        var keys = new HashMap<IPatternDetails, SortKey>(raw.size() * 2);
        var ordered = new ArrayList<IPatternDetails>(raw);
        ordered.sort((left, right) -> {
            var leftKey = keys.computeIfAbsent(left, pattern -> SortKey.of(service, pattern));
            var rightKey = keys.computeIfAbsent(right, pattern -> SortKey.of(service, pattern));
            int byPriority = Integer.compare(rightKey.priority(), leftKey.priority());
            return byPriority != 0 ? byPriority : leftKey.orderKey().compareTo(rightKey.orderKey());
        });
        return List.copyOf(ordered);
    }

    /**
     * 这张样板的机器优先级：本类的两个来源（AE2 真网格 / 报优先级的视图）里能读到哪个用哪个，
     * 都读不到按 AE2 的默认值 0（＝与所有默认优先级并列，交给稳定键定序）。
     */
    public static int observedPriority(@Nullable ICraftingService service, IPatternDetails pattern) {
        if (service instanceof PatternPrioritySource source) {
            return source.patternPriority(pattern);
        }
        if (service instanceof CraftingService craftingService) {
            return maxProviderPriority(craftingService, pattern);
        }
        return 0;
    }

    /**
     * AE2 的清单序是按<b>该样板在任一提供者上的最高优先级</b>排的
     * （{@code sortPatterns} 先按 {@code state.priority} 稳定降序、再 {@code distinct}，
     * 一张样板在两个优先级不同的提供者上时留在前者那一位）——这里取同一口径。
     */
    private static int maxProviderPriority(CraftingService service, IPatternDetails pattern) {
        int best = Integer.MIN_VALUE;
        for (ICraftingProvider provider : service.getProviders(pattern)) {
            if (provider != null) {
                best = Math.max(best, provider.getPatternPriority());
            }
        }
        return best == Integer.MIN_VALUE ? 0 : best;
    }

    /** 优先级与稳定键一次算好、按样板复用（一次排序里每张样板只求一次）。 */
    private record SortKey(int priority, String orderKey) {

        private static SortKey of(ICraftingService service, IPatternDetails pattern) {
            return new SortKey(observedPriority(service, pattern), stableOrderKeyOnce(pattern));
        }
    }

    /**
     * 这张样板的稳定键：本类内部记一次（{@link #STABLE_ORDER_KEYS}），不在每次排序、每个键的
     * 清单上把退料段重走一遍。
     */
    private static String stableOrderKeyOnce(IPatternDetails pattern) {
        var cached = STABLE_ORDER_KEYS.get(pattern);
        if (cached != null) {
            return cached;
        }
        var computed = stableOrderKey(pattern);
        var previous = STABLE_ORDER_KEYS.putIfAbsent(pattern, computed);
        return previous != null ? previous : computed;
    }

    /**
     * 稳定键：注册表 id 字符串（带组件时逐项读数）→ 样板声明（输出 / 逐候选的退料键 / 候选输入 / 倍率）
     * → 实现类名，全字典序比较，一个字都不含哈希。
     */
    private static String stableOrderKey(IPatternDetails pattern) {
        var key = new StringBuilder(96);
        appendKey(key, pattern.getDefinition());
        key.append('\u0000');
        var outputs = pattern.getOutputs();
        if (outputs != null) {
            for (var output : outputs) {
                appendStack(key, output);
                key.append('\u0001');
            }
        }
        key.append('\u0000');
        var inputs = pattern.getInputs();
        if (inputs != null) {
            for (var input : inputs) {
                if (input == null) {
                    continue;
                }
                var possibles = input.getPossibleInputs();
                if (possibles != null) {
                    // 退料键按每个候选各求一次（台账 #143 MF-1）：AE2 的退料挂在实际用的那个
                    // template 上（AECraftingPattern.java:629-633 走 getRecipeRemainder(slot, itemKey)），
                    // 只对首位候选求会让「首位候选相同、非首位候选的退料不同」的两张样板在这一串上
                    // 逐字相同，比较返回 0，次序又交回给来序。候选数组序原样写，不排序。
                    for (var candidate : possibles) {
                        appendStack(key, candidate);
                        key.append('\u0003');
                        appendKey(key, candidate == null ? null : input.getRemainingKey(candidate.what()));
                        key.append('\u0001');
                    }
                }
                key.append('\u0002').append(input.getMultiplier());
            }
        }
        key.append('\u0000').append(pattern.getClass().getName());
        return key.toString();
    }

    private static void appendStack(StringBuilder key, @Nullable GenericStack stack) {
        if (stack == null) {
            key.append("<null>");
            return;
        }
        appendKey(key, stack.what());
        key.append('x').append(stack.amount());
    }

    /**
     * 一个 AE 键的稳定读数（与稳定键里那一段同一把尺）：注册表 id 字符串，带组件时续上逐项读数。
     * 模糊代用清单的族内排序用它（{@link FuzzyClassView}），与样板定序不会走成两把尺。
     */
    private static String stableKey(AEKey what) {
        var key = new StringBuilder(48);
        appendKey(key, what);
        return key.toString();
    }

    /**
     * 模糊代用清单的读取视图（台账 #143）：将"某一族里能合成的键"按 {@link #stableKey} 升序交出。
     *
     * <h3>这一改动的是挑选次序，不是给同一份次序排序（台账 #143 MF-3）</h3>
     * AE2 对<b>可损伤</b>的 item 本来就有一套有意的挑选次序：{@code FuzzySearch.KeyComparator}
     * （{@code reference-sources/ae2-19.2.17-sources/appeng/api/stacks/FuzzySearch.java:97-98}）把
     * <b>受损重的排在前面</b>，只在损伤读数并列时才退到 {@code hashCode}（{@code :103-108}，它自己写明
     * "只需要损伤档可预测"）。换成 {@link #stableKey} 的字面字典序之后，谁在前由那串读数的字符决定，
     * 实测出两条码（都不是耐久语义）：
     * <ul>
     * <li>同 item 的两档，<b>带组件补丁那一档在前</b>——{@code minecraft:iron_pickaxe (with patches)[…]}
     * 里的那个空格（{@code 0x20}）排在无补丁那一档的组件段 {@code [}（{@code 0x5B}）之前。
     * "无补丁"不等于"无组件"：满耐久的铁镐同样带着整套默认组件（离线用例里实测 9 项），只是补丁表为空，
     * 所以它并不是"无组件读数在前"这一形；</li>
     * <li>两档都带补丁、只差 {@code DamageValue} 时按<b>字典序</b>排，<b>不是数值序</b>：同宽数字
     * （{@code int:8} 与 {@code int:9}）给出耐久<b>升</b>序，正好与 AE2 的受损优先<b>相反</b>；
     * 不同宽（{@code int:10} 与 {@code int:9}）给出 {@code int:10} 在前，两把尺都不是。</li>
     * </ul>
     * 这不是 bug——要的是节点身份不再跟着 {@code Item} 的身份哈希翻；但它确实换掉了别人的语义，
     * 所以 {@code GraphCompilerFuzzyClassOrderTest} 把这几条码都钉住，谁要撤定序就得先动那条用例。
     *
     * <h3>为什么要在读取点自己挑</h3>
     * AE2 的 {@code getFuzzyCraftable}（{@code NetworkCraftingProviders.java:138-139}）遍历
     * {@code craftableItemsList}（一只 {@code KeyCounter}）交回<b>哈希遍历序里的第一个</b>匹配项：
     * 不可破坏的 item 走 {@code VariantCounter.UnorderedVariantMap}＝{@code AEKey2LongMap.OpenHashMap}
     * 的哈希序，可损伤的 item 走 {@code FuzzySearch.KeyComparator}（受损重的在前，损伤档并列时才按
     * {@code hashCode} 升序）。
     * 两个族的成员都是 {@code AEItemKey}，哈希一路取到 {@code Item} 的身份哈希 ⇒ 换一次会话就能
     * 挑中另一个键。而这个返回值在 {@code GraphCompiler.resolveNodeKey} 里决定<b>节点身份</b>
     * （进 {@code producers} 与 routes），不是一个可选读数。
     *
     * <h3>清单宇宙与 AE2 一致</h3>
     * {@code craftableItemsList} 与 {@code craftableItems} 在 {@code mount()/unmount()} 里同增同减
     * （{@code NetworkCraftingProviders.java:201-205/:220-226}），所以"在这一族里"等价于
     * {@code getCraftingFor(key)} 非空；而 {@code ICraftingService#getCraftables} 交回的是
     * "可合成 ∩ 可发射"的全集，因此这里只留 {@link ICraftingService#isCraftable} 为真的那批——
     * 不把纯可发射的键当成模糊代用（与 AE2 与 {@code CraftingTreeNode#findCraftedStack} 同一口径）。
     *
     * <h3>代价</h3>
     * 一次编译一份视图：{@code getCraftables} 只走一趟（不逐次重扫全网络），族内过滤与排序
     * 按族惰性记忆——没被查询落到的族一个键都不排。族内稳定键仍并列的那一档，现在只剩
     * "取不到 codec 读数、而值所属的类又没覆写 {@code toString}"这一种（台账 #143 T3 把
     * 不依赖 codec 的值文本接进了那一档）；这一档本串上没有任何可区分的稳定读数，交回物化序。
     */
    public static FuzzyClassView fuzzyClassView(ICraftingService service) {
        return new FuzzyClassView(service);
    }

    /** 一份编译期快照的模糊代用视图（见 {@link #fuzzyClassView}）。 */
    public static final class FuzzyClassView {

        private final ICraftingService service;
        /** 主键 → 这一族的全部键；一次 {@code getCraftables} 物化，不逐次重扫全网络。 */
        @Nullable
        private Map<Object, List<AEKey>> byClass;
        /** 主键 → 只留真能合成的那批并按稳定键升序；逐族惰性，查询没落到的族一个都不排。 */
        private final Map<Object, List<AEKey>> orderedClassCache = new IdentityHashMap<>();

        private FuzzyClassView(ICraftingService service) {
            this.service = service;
        }

        /**
         * 与 {@code getFuzzyCraftable(candidate, …)} 同宇宙的一份清单，按稳定键升序
         * （口径见上面"这一改动的是挑选次序"：带组件补丁那一档在前，且读数按字典序而非数值序，
         * 与 AE2 的受损优先不是一把尺）。
         * 调用方在<b>读这一份清单的那一步</b>自己挑第一个通过的候选，不在下游再排一遍。
         */
        public List<AEKey> craftableClass(AEKey candidate) {
            var primaryKey = candidate.getPrimaryKey();
            var cached = orderedClassCache.get(primaryKey);
            if (cached != null) {
                return cached;
            }
            var members = materialiseClassIndex().get(primaryKey);
            List<AEKey> ordered;
            if (members == null || members.isEmpty()) {
                ordered = List.of();
            } else if (members.size() == 1) {
                // 单成员族没有并列可言；仍要按 isCraftable 筛掉纯可发射的键
                var only = members.get(0);
                ordered = only.getPrimaryKey() == primaryKey && service.isCraftable(only)
                        ? List.of(only)
                        : List.of();
            } else {
                var craftable = new ArrayList<AEKey>(members.size());
                for (var member : members) {
                    if (member.getPrimaryKey() != primaryKey || !service.isCraftable(member)) {
                        continue;
                    }
                    craftable.add(member);
                }
                craftable.sort(java.util.Comparator.comparing(ProviderPatternOrder::stableKey));
                ordered = List.copyOf(craftable);
            }
            orderedClassCache.put(primaryKey, ordered);
            return ordered;
        }

        /** 把 {@code getCraftables} 那一份全集按主键分组（身份比较，与 AE2 的 Reference2Object 索引同形）。 */
        private Map<Object, List<AEKey>> materialiseClassIndex() {
            if (this.byClass != null) {
                return this.byClass;
            }
            var grouped = new IdentityHashMap<Object, List<AEKey>>();
            for (var craftable : service.getCraftables(ignored -> true)) {
                if (craftable == null) {
                    continue;
                }
                grouped.computeIfAbsent(craftable.getPrimaryKey(), key -> new ArrayList<AEKey>(2))
                        .add(craftable);
            }
            this.byClass = grouped;
            return grouped;
        }
    }

    /**
     * 一个 AE 键的稳定读数：{@code AEKey#toString} 那一族交出的是注册表 id（未注册项退到实现类名），
     * 跨会话稳定；{@code AEItemKey} 带组件时它只报到 {@code "(with patches)"}（{@code AEItemKey.java:258-262}），
     * 所以这里把组件读数续在后面——同一 item 的多个组件变体因此拆得开（台账 #142）。
     */
    private static void appendKey(StringBuilder key, @Nullable AEKey what) {
        if (what == null) {
            key.append("<null>");
            return;
        }
        key.append(what.toString());
        if (what instanceof AEItemKey itemKey) {
            appendComponents(key, itemKey.getReadOnlyStack().getComponents());
        }
    }

    /**
     * 组件清单按<b>组件类型注册名</b>升序逐项拼"注册名=值的规范编码读数"。
     * {@code DataComponentMap#keySet()} 的迭代序是引用哈希序（{@code Reference2ObjectMap}），
     * 不能直接拿来定序，所以先按注册名装进 {@link TreeMap}。
     */
    private static void appendComponents(StringBuilder key, DataComponentMap components) {
        if (components.isEmpty()) {
            return;
        }
        var byName = new TreeMap<String, DataComponentType<?>>();
        for (var type : components.keySet()) {
            byName.put(componentTypeName(type), type);
        }
        for (var entry : byName.entrySet()) {
            var type = entry.getValue();
            key.append('[').append(entry.getKey()).append('=');
            appendComponentValue(key, type, components.get(type));
            key.append(']');
        }
    }

    private static String componentTypeName(DataComponentType<?> type) {
        var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        return id == null ? type.getClass().getName() : id.toString();
    }

    /**
     * 单个组件值的稳定读数：{@code DataComponentType#codec()} 对 {@link NbtOps} 的编码结果
     * （＝原版的落盘编码路径）再走 {@link #appendTag} 的规范渲染。
     *
     * <p>取不到 codec 读数的那两档（没有 codec 的瞬时组件、编码失败的值）除了固定标记，
     * <b>仍续上值自己那份不依赖 codec 的文本</b>（{@link #appendValueReading}）：只写标记时
     * 同一组件上两个取不到读数的不同值在这一档逐字相同 ⇒ 比较返回 0 ⇒ 次序又落回别人家的
     * 哈希序，这一档就白并列了。组件的存在性本来就按注册名逐项列在 {@link #appendComponents}
     * 那趟循环里（在册＝有这一段，缺席＝没有），所以"有没有这一项"与"值取不取得到"是两把
     * 分开的尺，后者拆不开时也不该把前者一起抹掉。
     */
    private static void appendComponentValue(StringBuilder key, DataComponentType<?> type, @Nullable Object value) {
        if (value == null) {
            key.append("<absent>");
            return;
        }
        var codec = type.codec();
        if (codec == null) {
            key.append("<transient ");
            appendValueReading(key, value);
            key.append('>');
            return;
        }
        DataResult<? extends Tag> encoded = encodeWithNbtOps(codec, value);
        var tag = encoded.result();
        if (tag.isPresent()) {
            appendTag(key, tag.get());
            return;
        }
        key.append("<unencodable ");
        appendValueReading(key, value);
        key.append('>');
    }

    /**
     * 一份<b>不依赖 codec</b> 的值读数：类名，外加该类自己覆写过的 {@code toString} 文本。
     *
     * <p>没有覆写时不接文本：{@code Object#toString} 交回的是 {@code 类名@身份哈希十六进制}，
     * 那正是本类一个字都不许读的东西——接进来会把"并列"换成"随会话翻的假拆分"，比原来更坏。
     * 这一档留在并列上（＝改前的形状），而不是留在哈希序上：并列只发生在两值的类名相同、
     * 又都没有可读文本时，此时两值在本串上确实没有任何可区分的稳定读数。
     */
    private static void appendValueReading(StringBuilder key, Object value) {
        key.append(value.getClass().getName());
        var text = value.toString();
        if (!text.equals(defaultToString(value))) {
            key.append('=').append(text);
        }
    }

    /** {@code Object#toString} 那份默认读数的原文（用来认出"这个类根本没覆写 toString"）。 */
    private static String defaultToString(Object value) {
        return value.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(value));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataResult<? extends Tag> encodeWithNbtOps(Codec codec, Object value) {
        return (DataResult<? extends Tag>) codec.encodeStart(NbtOps.INSTANCE, value);
    }

    /**
     * {@link Tag} 的规范渲染：类型名 + 值，CompoundTag 的<b>键名单独升序</b>
     * （{@code CompoundTag#getAllKeys()} 背后是 String 哈希桶序，List 保序——列表本身是声明的一部分）。
     * 与 {@code Tag#toString()} 无关，也不读任何哈希。
     */
    private static void appendTag(StringBuilder key, Tag tag) {
        key.append(tag.getType().getName()).append(':');
        if (tag instanceof CompoundTag compound) {
            key.append('{');
            for (var name : new TreeSet<>(compound.getAllKeys())) {
                key.append(name).append('=');
                var child = compound.get(name);
                if (child == null) {
                    key.append("<null>");
                } else {
                    appendTag(key, child);
                }
                key.append(',');
            }
            key.append('}');
        } else if (tag instanceof ListTag list) {
            key.append('[');
            for (var child : list) {
                appendTag(key, child);
                key.append(',');
            }
            key.append(']');
        } else {
            key.append(tag.getAsString());
        }
    }
}
