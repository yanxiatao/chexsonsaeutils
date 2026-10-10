package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.RouteOrdering;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提供者清单里<b>并列机器优先级</b>的那一截，次序在我们侧的收集点就定死（台账 #140，MF-1）。
 *
 * <h3>缺陷形状（vendored AE2 源码为证）</h3>
 * {@code NetworkCraftingProviders.java:237} 的 {@code PatternsForKey.patterns} 是一只
 * {@code HashSet<PatternInfo>}；{@code :241-247} 只按 {@code state.priority} 稳定排序，
 * <b>并列项留 {@code HashSet} 遍历序</b>，而 {@code PatternInfo} 的哈希混入
 * {@code IPatternDetails#hashCode}（AE2 四条样板实现一律 {@code definition.hashCode()}
 * ＝ {@code ItemStack.hashItemAndComponents} ＝含 {@code Item} 身份哈希）。
 * 这份清单序＝{@code GraphCompiler} 建 {@code producers} 的序＝求解器首触序＝
 * {@code WideCraftCounter.registerSlot} 的注册序 ⇒ {@code d2b53c8} 交出的 {@code registered[]}
 * 只钉住了"本会话的哈希序"，跨会话照翻。
 *
 * <h3>判据（并列臂）</h3>
 * 同一个键上<b>六张同优先级</b>样板，两次以相反插入顺序喂进收集点
 * （{@code GraphCompiler.Compilation#craftingFor}）⇒ 编译期产出的 routes 次序与随后
 * {@code patternTimes()} 交出的注册序<b>逐字相同</b>，且等于独立手算表
 * （definition 注册表 id 升序：{@code coal, diamond, emerald, feather, gold_ingot, iron_nugget}）。
 * 未修的树上这条红：那份序就是喂进去的序，两次构造相反。
 * 取六张而不取两张：清单原样交回时第二次交出的就是第一次的<b>反序</b>，"两次次序逐字相同"
 * 这一条因此是结构确定的红，不依赖哈希碰巧——本用例不需要"HashMap 遍历序≠期望序"那种
 * 概率前置（台账 #141 那条牙加在#136/#137 那两条只能靠钉值说话的用例上）。
 *
 * <h3>判据（非并列臂）</h3>
 * 机器优先级是玩家在样板供应器上配的意图。两张<b>不同优先级</b>的样板按相反顺序喂进来，
 * 交出的序必须是"高优先级在前"，<b>不是</b> definition 字典序——这一臂挡住"把定序装成第三把尺"的改法。
 *
 * <h3>判据（稳定键自己拆不开并列，台账 #142）</h3>
 * 上面那两条只证明"definition 不同"时定序有效。还有两类样板在旧稳定键上<b>逐字相同</b>，
 * 比较返回 0，次序又被交回到来序（＝别人家 {@code HashSet} 的遍历序），#140 对那些整刀无效：
 * <ul>
 * <li><b>只差退料键</b>的两张样板：definition、输出、候选输入、倍率、实现类名全同，
 * 只有 {@code getRemainingKey} 不同（旧键根本不含这一项）；</li>
 * <li><b>同 item 不同组件</b>的两张样板：{@code AEItemKey#toString} 对带组件的键只报到
 * {@code "(with patches)"} 为止（{@code AEItemKey.java:258-262}）。</li>
 * </ul>
 * 这两臂按<b>样板实例</b>钉次序（definition 串在这一档分不开两张样板，只有实例身份能），
 * 判据同样是"两次相反插入喂进收集点 ⇒ 路线序逐字相同且等于钉值"；只读编译期的路线序，
 * 这两臂的缺陷在收集点本身，不参与份数账。未修的树上这条红：路线序＝喂进去的序，两次构造相反。
 *
 * <h3>判据（退料段按候选逐个求，台账 #143 MF-1）</h3>
 * #142 那道补的退料段只对候选<b>首位</b>求一次 {@code getRemainingKey}，而 AE2 的退料挂在
 * <b>实际用的那个 template</b> 上（{@code AECraftingPattern.java:629-633} 走
 * {@code getRecipeRemainder(slot, itemKey)}）⇒ 两张样板<b>首位候选相同、非首位候选的退料不同</b>时
 * 比较仍返回 0，次序又落回来序。{@code patternsDifferingByRemainderOfANonFirstCandidateKeepOneOrder}
 * 打的就是这一档：候选表逐字相同，退料只在第二个候选上分家。
 */
final class GraphCompilerProviderTieOrderTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);

    /** 六张并列样板的 definition（字典序升序＝独立手算的期望序）。 */
    private static final AEItemKey D_COAL = AEItemKey.of(Items.COAL);
    private static final AEItemKey D_DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey D_EMERALD = AEItemKey.of(Items.EMERALD);
    private static final AEItemKey D_FEATHER = AEItemKey.of(Items.FEATHER);
    private static final AEItemKey D_GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey D_IRON_NUGGET = AEItemKey.of(Items.IRON_NUGGET);
    private static final List<AEItemKey> DEFINITIONS = List.of(
            D_COAL, D_DIAMOND, D_EMERALD, D_FEATHER, D_GOLD, D_IRON_NUGGET);

    /** 六张样板各吃一枚的输入（一一对应，每张只发得出 1 发）。 */
    private static final List<AEItemKey> INPUTS = List.of(
            AEItemKey.of(Items.STRING), AEItemKey.of(Items.LEATHER), AEItemKey.of(Items.BRICK),
            AEItemKey.of(Items.PAPER), AEItemKey.of(Items.FLINT), AEItemKey.of(Items.GUNPOWDER));

    /** 非并列臂：高优先级那张的 definition 字典序在后，低优先级那张在前——两把尺给的序相反。 */
    private static final AEItemKey HIGH_PRIORITY_DEFINITION = AEItemKey.of(Items.SNOWBALL);
    private static final AEItemKey LOW_PRIORITY_DEFINITION = AEItemKey.of(Items.APPLE);

    /**
     * 退料臂：两张样板除退料键外一字不差（同一 definition、同一候选输入、同一输出、同一实现类）。
     * 钉值是注册表 id 的字典序：{@code minecraft:bucket} 在 {@code minecraft:glass_bottle} 之前。
     */
    private static final AEItemKey REMAINDER_DEFINITION = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey REMAINDER_INPUT = AEItemKey.of(Items.WATER_BUCKET);
    private static final AEItemKey REMAINDER_BUCKET = AEItemKey.of(Items.BUCKET);
    private static final AEItemKey REMAINDER_GLASS_BOTTLE = AEItemKey.of(Items.GLASS_BOTTLE);

    /**
     * MF-1 那一档的候选表：首位候选两张样板<b>逐字相同</b>（退料都为空），只有<b>非首位候选</b>的退料不同。
     * 现场只喂首位候选那份料，所以两张样板都按首位候选发射——退料差别只活在稳定键这一串上。
     */
    private static final AEItemKey MULTI_FIRST_CANDIDATE = AEItemKey.of(Items.WATER_BUCKET);
    private static final AEItemKey MULTI_SECOND_CANDIDATE = AEItemKey.of(Items.LAVA_BUCKET);
    private static final GenericStack[] MULTI_CANDIDATES = {
            new GenericStack(MULTI_FIRST_CANDIDATE, 1L),
            new GenericStack(MULTI_SECOND_CANDIDATE, 1L),
    };

    /**
     * 组件臂：同一 item 的两个组件变体作 definition（旧稳定键上两者都印成
     * {@code "minecraft:xxx (with patches)"}）。钉值按组件读数的字典序：
     * {@code mark=aaa} 在 {@code mark=bbb} 之前。
     */
    private static final AEItemKey COMPONENT_DEFINITION_AAA = componentKey(Items.CLOCK, "aaa");
    private static final AEItemKey COMPONENT_DEFINITION_BBB = componentKey(Items.CLOCK, "bbb");
    private static final AEItemKey COMPONENT_INPUT = AEItemKey.of(Items.STRING);

    /**
     * 退料臂（MF-1，台账 #143）：AE2 的退料挂在<b>实际用的那个 template</b> 上
     * （{@code reference-sources/ae2-19.2.17-sources/appeng/crafting/pattern/AECraftingPattern.java:629-633}
     * 的 {@code getRemainingKey(template)} 走 {@code getRecipeRemainder(slot, itemKey)}），
     * 而 #142 那道修复只对候选<b>首位</b>求一次退料键。这一档的形状：两张样板<b>首位候选逐字相同</b>、
     * 只有<b>非首位候选</b>的退料不同（{@code bucket}／{@code glass_bottle}）⇒ 旧键上两串相同、
     * 比较返回 0，次序又落回 {@code List.sort} 的稳定序＝来序＝AE2 那只 {@code HashSet} 的遍历序。
     * 判据与上面那条退料臂同形：两次相反插入喂进收集点 ⇒ 路线序逐字相同，钉在 {@code bucket} 那张在前。
     */
    @Test
    void patternsDifferingByRemainderOfANonFirstCandidateKeepOneOrder() {
        var withBucket = new PerCandidateRemainderPattern(
                perCandidateSlot(MULTI_CANDIDATES, Map.of(MULTI_SECOND_CANDIDATE, REMAINDER_BUCKET)));
        var withGlassBottle = new PerCandidateRemainderPattern(perCandidateSlot(MULTI_CANDIDATES,
                Map.of(MULTI_SECOND_CANDIDATE, REMAINDER_GLASS_BOTTLE)));

        var forward = routeSources(List.of(withBucket, withGlassBottle), MULTI_FIRST_CANDIDATE);
        var reversed = routeSources(List.of(withGlassBottle, withBucket), MULTI_FIRST_CANDIDATE);

        assertEquals(List.of(withBucket, withGlassBottle), forward,
                () -> "首位候选相同、只有非首位候选的退料不同 ⇒ 旧键（只按首位候选求退料）两串逐字相同，"
                        + "比较返回 0，次序又是喂进来的序：" + describe(forward));
        assertEquals(forward, reversed,
                () -> "反着喂就换了先后＝退料段只按候选首位求，非首位候选的退料没进稳定键："
                        + describe(reversed));
    }

    /** 退料键不同而其余声明相同的两张样板：反着喂必须给出同一份钉住的路线序。 */
    @Test
    void patternsDifferingOnlyByRemainderKeyKeepOneOrder() {
        var withBucket = new TiedPattern(REMAINDER_DEFINITION, REMAINDER_INPUT, 0, REMAINDER_BUCKET);
        var withGlassBottle = new TiedPattern(REMAINDER_DEFINITION, REMAINDER_INPUT,
                0, REMAINDER_GLASS_BOTTLE);

        var forward = routeSources(List.of(withBucket, withGlassBottle), REMAINDER_INPUT);
        var reversed = routeSources(List.of(withGlassBottle, withBucket), REMAINDER_INPUT);

        assertEquals(List.of(withBucket, withGlassBottle), forward,
                () -> "只差退料键的两张样板在旧稳定键上逐字相同，比较返回 0 ⇒ 次序又是喂进来的序："
                        + describe(forward));
        assertEquals(forward, reversed,
                () -> "反着喂就换了先后＝退料键没进稳定键，并列项又落回 AE2 的 HashSet 遍历序："
                        + describe(reversed));
    }

    /** 同 item 不同组件的两张样板：反着喂必须给出同一份钉住的路线序。 */
    @Test
    void patternsDifferingOnlyByComponentsKeepOneOrder() {
        var aaa = new TiedPattern(COMPONENT_DEFINITION_AAA, COMPONENT_INPUT, 0, null);
        var bbb = new TiedPattern(COMPONENT_DEFINITION_BBB, COMPONENT_INPUT, 0, null);

        var forward = routeSources(List.of(aaa, bbb), COMPONENT_INPUT);
        var reversed = routeSources(List.of(bbb, aaa), COMPONENT_INPUT);

        assertEquals(List.of(aaa, bbb), forward,
                () -> "带组件的 definition 只印 \"(with patches)\"，两张样板并列 ⇒ 次序又是喂进来的序："
                        + describe(forward));
        assertEquals(forward, reversed,
                () -> "反着喂就换了先后＝组件差异没进稳定键，并列项又落回 AE2 的 HashSet 遍历序："
                        + describe(reversed));
    }

    /**
     * "取不到 codec 读数"那一型的<b>编码失败</b>臂（台账 #143 T3）：两张样板的 definition 只差
     * {@code max_stack_size}，而该组件的 codec 是 {@code ExtraCodecs.intRange(1, 99)}
     * （{@code net/minecraft/core/component/DataComponents.java}）⇒ 100 与 101 都编码失败。
     * 改前两条臂都只印固定标记 {@code <unencodable java.lang.Integer>}，这一档逐字相同 ⇒
     * 比较返回 0 ⇒ 次序又交回喂进来的序。补上不依赖 codec 的值文本之后，两串由
     * {@code =100}／{@code =101} 拆开，钉值是文本字典序（100 在前）。
     */
    @Test
    void definitionsWhoseComponentValueCannotBeEncodedKeepOneOrder() {
        var hundred = new TiedPattern(maxStackSizeKey(Items.CLOCK, 100), COMPONENT_INPUT, 0);
        var hundredAndOne = new TiedPattern(maxStackSizeKey(Items.CLOCK, 101), COMPONENT_INPUT, 0);
        // 夹具前提：这两张的定义值必须真的走"编码失败"那条臂，否则这条读数压根没跑在档上
        assertTrue(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE.codec()
                        .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, 100).result().isEmpty(),
                "夹具前提：max_stack_size=100 要被 codec 判编码失败，否则本用例测的不是那一档");

        var forward = routeSources(List.of(hundred, hundredAndOne), COMPONENT_INPUT);
        var reversed = routeSources(List.of(hundredAndOne, hundred), COMPONENT_INPUT);

        assertEquals(List.of(hundred, hundredAndOne), forward,
                () -> "两个都取不到 codec 读数的值在旧键上印成同一个标记 ⇒ 并列 ⇒ 次序又是喂进来的序："
                        + describe(forward));
        assertEquals(forward, reversed,
                () -> "反着喂就换了先后＝编码失败那一档没接不依赖 codec 的值文本，并列项又落回哈希遍历序："
                        + describe(reversed));
    }

    /**
     * "取不到 codec 读数"那一型的<b>瞬时组件</b>臂（台账 #143 T3）：一个 {@code codec()==null}
     * 的组件类型（＝原版口径的 transient 组件），两张样板的 definition 只差它身上的值。
     * 改前这一档只印 {@code <transient>}，两串逐字相同；现在标记后面仍续上值自己那份文本，
     * 于是 {@code aaa} 在 {@code bbb} 之前。
     */
    @Test
    void definitionsDifferingOnlyByATransientComponentKeepOneOrder() {
        assertTrue(TRANSIENT_MARK.codec() == null,
                "夹具前提：这个组件类型必须真的没有 codec，否则测的不是瞬时那一档");
        var aaa = new TiedPattern(transientMarkKey("aaa"), COMPONENT_INPUT, 0);
        var bbb = new TiedPattern(transientMarkKey("bbb"), COMPONENT_INPUT, 0);

        var forward = routeSources(List.of(aaa, bbb), COMPONENT_INPUT);
        var reversed = routeSources(List.of(bbb, aaa), COMPONENT_INPUT);

        assertEquals(List.of(aaa, bbb), forward,
                () -> "瞬时组件的两个值在旧键上都印成 {@code <transient>} ⇒ 并列 ⇒ 次序又是喂进来的序："
                        + describe(forward));
        assertEquals(forward, reversed,
                () -> "反着喂就换了先后＝瞬时组件那一档没接不依赖 codec 的值文本：" + describe(reversed));
    }

    /**
     * 一个没有 codec 的组件类型（＝原版口径的 transient 组件）：本仓的组件值读数走
     * {@code codec().encodeStart(NbtOps)}，这一档取不到任何持久化读数，正是稳定键里
     * "编码之外还剩下什么"的那一型。没注册进 {@code BuiltInRegistries} ⇒ 注册名读不到 ⇒
     * 两张样板在这一档的<b>键名也相同</b>，能拆开它们的只剩值自己那份文本。
     */
    private static final net.minecraft.core.component.DataComponentType<String> TRANSIENT_MARK =
            new net.minecraft.core.component.DataComponentType<>() {
                @Override
                public @Nullable com.mojang.serialization.Codec<String> codec() {
                    return null;
                }

                @Override
                public net.minecraft.network.codec.StreamCodec<? super
                        net.minecraft.network.RegistryFriendlyByteBuf, String> streamCodec() {
                    return net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8;
                }

                @Override
                public String toString() {
                    return "test:transient_mark";
                }
            };

    /** 只在 {@code max_stack_size} 上分家的 definition（该值超出 codec 允许区间 ⇒ 编码失败）。 */
    private static AEItemKey maxStackSizeKey(Item item, int maxStackSize) {
        var stack = new net.minecraft.world.item.ItemStack(item);
        stack.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, maxStackSize);
        return AEItemKey.of(stack);
    }

    /** 只在瞬时组件上分家的 definition。 */
    private static AEItemKey transientMarkKey(String mark) {
        var stack = new net.minecraft.world.item.ItemStack(Items.CLOCK);
        stack.set(TRANSIENT_MARK, mark);
        return AEItemKey.of(stack);
    }

    /**
     * 只读编译期收集点交出的路线序（按<b>样板实例</b>认出次序——这两臂的 definition 串本就分不开）。
     *
     * @param providerOrder 喂进收集点的那份清单（＝AE2 {@code getCraftingFor} 交来的原样序）
     */
    private static List<IPatternDetails> routeSources(List<? extends IPatternDetails> providerOrder,
            AEKey stockedInput) {
        var storage = new java.util.LinkedHashMap<AEKey, Long>();
        storage.put(stockedInput, 8L);
        var grid = FakeGrid.builder()
                .storage(storage)
                .pattern(TOP, providerOrder.toArray(new IPatternDetails[0]))
                .build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

        var sources = new ArrayList<IPatternDetails>();
        for (var route : RouteOrdering.routesFor(graph, TOP, true, false).routes()) {
            if (route instanceof RouteOrdering.Route.Pattern patternRoute) {
                sources.add(patternRoute.pattern().source());
            }
        }
        assertEquals(providerOrder.size(), sources.size(),
                () -> "夹具前提：两张样板都要真被编成路线，否则路线序这条读数压根没跑。实际=" + sources);
        return List.copyOf(sources);
    }

    /** 红字里把每张样板<b>逐个候选</b>的退料键/组件读数如实印出来，免得只看到"次序反了"。 */
    private static String describe(List<IPatternDetails> sources) {
        var builder = new StringBuilder();
        for (var source : sources) {
            builder.append('[').append(source.getDefinition());
            for (var input : source.getInputs()) {
                for (var candidate : input.getPossibleInputs()) {
                    builder.append(" remaining[").append(candidate.what()).append("]=")
                            .append(input.getRemainingKey(candidate.what()));
                }
            }
            builder.append(']');
        }
        return builder.toString();
    }

    /** 与 {@code GraphPlanningEquivalenceTest} 同形的组件夹具：自定义数据里一枚字符串标记。 */
    private static AEItemKey componentKey(Item item, String mark) {
        var stack = new net.minecraft.world.item.ItemStack(item);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("mark", mark);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    /** 并列项：两次相反插入必须给出同一份 routes 序与注册序，且等于字典序手算表。 */
    @Test
    void tiedProviderPatternsKeepOneOrderAcrossReversedInsertion() {
        var forward = build(sixPatterns(false));
        var reversed = build(sixPatterns(true));

        assertEquals(DEFINITIONS, forward.routeOrder(),
                () -> "并列优先级按稳定键定死（这批夹具的稳定键就是 definition 的注册表 id），"
                        + "喂进来的序不算数。实际=" + forward.routeOrder());
        assertEquals(forward.routeOrder(), reversed.routeOrder(),
                () -> "routes 次序随提供者清单的插入序变＝还在吃 AE2 那只 HashSet 的遍历序："
                        + reversed.routeOrder());
        assertEquals(forward.registeredOrder(), reversed.registeredOrder(),
                () -> "注册序（plan.patternTimes 的键序）随插入序变＝下游顺位跟着翻："
                        + reversed.registeredOrder());
        assertEquals(DEFINITIONS, forward.registeredOrder(),
                () -> "求解首触序＝定过序的 routes 序（手算表：六张全被记上，按 definition 升序）："
                        + forward.registeredOrder());
    }

    /** 非并列项：优先级高的在前，稳定键那把尺不许把它们换过来。 */
    @Test
    void differentPrioritiesStayBehindTheProviderPriorityRuler() {
        var forward = build(List.of(
                new TiedPattern(HIGH_PRIORITY_DEFINITION, INPUTS.get(0), 5),
                new TiedPattern(LOW_PRIORITY_DEFINITION, INPUTS.get(1), 1)));
        var reversed = build(List.of(
                new TiedPattern(LOW_PRIORITY_DEFINITION, INPUTS.get(1), 1),
                new TiedPattern(HIGH_PRIORITY_DEFINITION, INPUTS.get(0), 5)));

        assertEquals(List.of(HIGH_PRIORITY_DEFINITION, LOW_PRIORITY_DEFINITION), forward.routeOrder(),
                () -> "优先级不同的两项仍按提供者优先级在前，稳定键只排并列那一档：" + forward.routeOrder());
        assertEquals(forward.routeOrder(), reversed.routeOrder(),
                () -> "反着喂就换了先后＝把机器优先级那把尺抹平了：" + reversed.routeOrder());
        assertEquals(forward.registeredOrder(), reversed.registeredOrder(),
                () -> "注册序跟着优先级序走：" + reversed.registeredOrder());
    }

    /** 六张并列样板：正序＝definition 升序摆，反序＝倒着摆（同优先级 0）。 */
    private static List<TiedPattern> sixPatterns(boolean reversedInsertion) {
        var patterns = new ArrayList<TiedPattern>(DEFINITIONS.size());
        for (int index = 0; index < DEFINITIONS.size(); index++) {
            patterns.add(new TiedPattern(DEFINITIONS.get(index), INPUTS.get(index), 0));
        }
        if (reversedInsertion) {
            Collections.reverse(patterns);
        }
        return List.copyOf(patterns);
    }

    /**
     * 一次构造的全部读数：{@code TOP} 这个键上的 routes 次序与计划交出的注册序。
     *
     * @param providerOrder 喂进收集点的那份清单（＝AE2 {@code getCraftingFor} 交来的原样序）
     */
    private static Build build(List<TiedPattern> providerOrder) {
        var storage = new java.util.LinkedHashMap<AEKey, Long>();
        for (var input : INPUTS) {
            storage.put(input, 1L);
        }
        var grid = FakeGrid.builder()
                .storage(storage)
                .pattern(TOP, providerOrder.toArray(new IPatternDetails[0]))
                .build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

        var routeOrder = new ArrayList<AEItemKey>();
        for (var route : RouteOrdering.routesFor(graph, TOP, true, false).routes()) {
            if (route instanceof RouteOrdering.Route.Pattern patternRoute) {
                routeOrder.add(patternRoute.pattern().source().getDefinition());
            }
        }

        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS,
                providerOrder.size());
        assertFalse(result.hasDeclined(), () -> "unexpected decline: " + result.declineReason());
        var plan = Ae2PlanConverter.toPlan(result, graph);
        var registeredOrder = plan.patternTimes().keySet().stream()
                .map(IPatternDetails::getDefinition)
                .toList();
        // 夹具前提：每张都得真被记上，否则"注册序"这条读数压根没跑
        assertEquals(routeOrder, registeredOrder,
                () -> "夹具前提：求解按 routes 序逐张首触，注册序与 routes 序才可对照。plan="
                        + registeredOrder + " routes=" + routeOrder);
        return new Build(List.copyOf(routeOrder), List.copyOf(registeredOrder));
    }

    /** 一次构造的读数。 */
    private record Build(List<AEItemKey> routeOrder, List<AEItemKey> registeredOrder) {
    }

    /** 单输入单输出的并列样板；definition 决定稳定键，priority 模拟提供者上的机器优先级。 */
    private static final class TiedPattern implements IPatternDetails, FakePlanGrid.PrioritizedPattern {

        private final AEItemKey definition;
        private final AEKey input;
        private final int priority;
        @Nullable
        private final AEKey remainder;

        private TiedPattern(AEItemKey definition, AEKey input, int priority) {
            this(definition, input, priority, null);
        }

        private TiedPattern(AEItemKey definition, AEKey input, int priority, @Nullable AEKey remainder) {
            this.definition = definition;
            this.input = input;
            this.priority = priority;
            this.remainder = remainder;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] {new SingleSlot(input, remainder)};
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(TOP, 1L));
        }
    }

    /** 单候选槽：一发一枚那一号输入；{@code remainder} 模拟这张配方的退料（台账 #142 的退料臂）。 */
    private record SingleSlot(AEKey candidate, @Nullable AEKey remainder) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(candidate, 1L)};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
            return candidate.equals(input);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return candidate.equals(template) ? remainder : null;
        }
    }

    /**
     * MF-1 那两张样板：definition / 候选表 / 输出 / 实现类全同，退料<b>按喂进来的 template 逐候选给</b>
     * （＝AE2 {@code AECraftingPattern.CraftingInputSlot#getRemainingKey} 的口径）。
     */
    private static final class PerCandidateRemainderPattern
            implements IPatternDetails, FakePlanGrid.PrioritizedPattern {

        private final IInput[] inputs;

        private PerCandidateRemainderPattern(IInput... inputs) {
            this.inputs = inputs;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public AEItemKey getDefinition() {
            return REMAINDER_DEFINITION;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(TOP, 1L));
        }
    }

    /** 多候选槽：候选表按数组序交出，退料只在 {@code remainders} 点名的那个候选上非空。 */
    private static IPatternDetails.IInput perCandidateSlot(GenericStack[] candidates,
            java.util.Map<AEKey, AEKey> remainders) {
        return new PerCandidateSlot(candidates.clone(), java.util.Map.copyOf(remainders));
    }

    private record PerCandidateSlot(GenericStack[] candidates,
            java.util.Map<AEKey, AEKey> remainders) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
            for (var candidate : candidates) {
                if (candidate.what().equals(input)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return template == null ? null : remainders.get(template);
        }
    }
}
