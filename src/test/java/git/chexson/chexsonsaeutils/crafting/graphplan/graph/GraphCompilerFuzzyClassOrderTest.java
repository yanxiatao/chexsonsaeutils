package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 那份清单不止一个入口：模糊代用清单（{@code getFuzzyCraftable} 那一本）也在读取点定序（台账 #143）。
 *
 * <h3>缺陷形状（vendored AE2 源码为证）</h3>
 * {@code NetworkCraftingProviders.java:138-139} 的 {@code getFuzzyCraftable} 遍历
 * {@code craftableItemsList}（{@code :45} 那只 {@code KeyCounter}），交回<b>哈希遍历序里的第一个</b>
 * 匹配项：不可损伤的 item 走 {@code VariantCounter.UnorderedVariantMap}
 * ＝{@code AEKey2LongMap.OpenHashMap}（{@code object2LongEntrySet()} 的序），可损伤的走
 * {@code FuzzySearch.KeyComparator}（并列按 {@code hashCode} 升序）。两条都取到
 * {@code AEItemKey} 的哈希，再往下是 {@code Item} 的身份哈希 ⇒ 换一次会话就能挑中<b>另一个</b>代用键。
 * 而 {@code GraphCompiler.resolveNodeKey}（旧 :220）把这一份返回值直接当<b>节点身份</b>用
 * ——它进 {@code producers} 与 routes，不是可选读数，所以 #140 只定了提供者那一本不够。
 *
 * <h3>判据</h3>
 * 同 item 的两个组件变体各自有样板（{@code mark=aaa}／{@code mark=bbb}），槽的首选候选是这一 item
 * 的<b>无样板</b>变体。两本样板以<b>相反登记序</b>喂进网格（登记序在这里就是"那份清单的来序"——
 * AE2 那一侧它是哈希序，我们说了不算）⇒ 编译出的节点身份必须都是稳定键更小的那一个
 * （{@code aaa}），且两次逐字相同。未修的树上这条红：节点身份＝喂进来的那一份的第一个，两次相反。
 *
 * <h3>判据（这一改动的是挑选次序，台账 #143 MF-3）</h3>
 * 上面那条只证"同族两档按稳定键谁在前"。还要写明的是：AE2 对<b>可损伤</b> item 的模糊挑选本来就有一套
 * 有意次序（受损重的在前、耐久档并列才按 {@code hashCode}，{@code FuzzySearch.java:97-108}），我们把它
 * 换成了<b>读数字符串的字面字典序</b>——换掉的是挑选次序本身，三条实测码各钉一条：
 * <ul>
 * <li>{@code fuzzyClassPutsComponentPatchedVariantFirst}：满耐久 vs 掉 5 点耐久 ⇒ <b>带组件补丁那一档
 * 在前</b>（{@code " (with patches)"} 的空格 {@code 0x20} 排在无补丁那一档的组件段 {@code [} 之前）。
 * 这一对恰好与 AE2 同向，是巧合不是同一把尺；</li>
 * <li>{@code fuzzyClassOrderIsNotAe2DamagedFirstForSameWidthDamageValues}：掉 8 点 vs 掉 9 点 ⇒
 * 我们给 {@code int:8} 在前（耐久升序），AE2 的受损优先给<b>相反</b>的先手；</li>
 * <li>{@code fuzzyClassOrdersDamageReadingsLexicographicallyNotNumerically}：掉 10 点 vs 掉 9 点 ⇒
 * {@code int:10} 在 {@code int:9} 之前，<b>数值大小不按数值序</b>。</li>
 * </ul>
 * 三条都把"按我们的口径该输的那一档"先登记：撤掉 {@code FuzzyClassView} 的族内排序就退回登记的来序，
 * 三条一起红。
 */
final class GraphCompilerFuzzyClassOrderTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);

    /** 槽的首选候选：这一 item 的无样板变体（＝AE2 要做模糊代用的那一格）。 */
    private static final AEItemKey CANDIDATE = AEItemKey.of(Items.SLIME_BALL);
    private static final AEItemKey SUBSTITUTE_AAA = componentKey(Items.SLIME_BALL, "aaa");
    private static final AEItemKey SUBSTITUTE_BBB = componentKey(Items.SLIME_BALL, "bbb");

    /**
     * MF-3（台账 #143）那一档：可损伤 item 的两个变体——满耐久（补丁表为空，仍带整套默认组件）与
     * {@code DamageValue=5}（补丁表非空）。实测口径是<b>带组件补丁那一档在前</b>，
     * 由 {@code " (with patches)"} 那一段的字典序决定，不是按耐久排的（AE2 那一侧才按耐久档排）。
     */
    private static final AEItemKey PICKAXE_UNDAMAGED = AEItemKey.of(Items.IRON_PICKAXE);
    private static final AEItemKey PICKAXE_DAMAGED = damagedKey(Items.IRON_PICKAXE, 5);
    /** 槽的首选候选：这一 item 的无样板变体（＝要做模糊代用的那一格）。 */
    private static final AEItemKey PICKAXE_CANDIDATE = damagedKey(Items.IRON_PICKAXE, 9);

    /** 副作用那一档：两把铁剑都带 DamageValue，只有数值不同（10 与 9 按字典序排，不按数值序）。 */
    private static final AEItemKey SWORD_TEN = damagedKey(Items.IRON_SWORD, 10);
    private static final AEItemKey SWORD_NINE = damagedKey(Items.IRON_SWORD, 9);

    /** 同宽数字那一档：两把铁斧只差一位数（8 与 9），字典序给的先手与 AE2 的受损优先相反。 */
    private static final AEItemKey AXE_LIGHTLY_DAMAGED = damagedKey(Items.IRON_AXE, 8);
    private static final AEItemKey AXE_MORE_DAMAGED = damagedKey(Items.IRON_AXE, 9);

    /** 两份代用样板都有样板可做、槽都认；节点身份只该由稳定键定，不该由那份清单的来序定。 */
    @Test
    void fuzzySubstituteFollowsStableKeyNotByTheOrderTheListCameIn() {
        var forward = substituteNodeKeys(false);
        var reversed = substituteNodeKeys(true);

        assertEquals(List.of(SUBSTITUTE_AAA), forward,
                () -> "模糊代用的节点身份应按稳定键升序取第一个（mark=aaa 在前）。实际=" + describe(forward));
        assertEquals(forward, reversed,
                () -> "登记序换了就换节点身份＝还在吃 AE2 craftableItemsList 的哈希遍历序，"
                        + "而它定的是 producers/routes 的键。实际=" + describe(reversed));
    }

    /** 红字要认得出是哪一个组件变体（{@code AEItemKey#toString} 只会印 "(with patches)"）。 */
    private static String describe(List<AEKey> nodeKeys) {
        var builder = new StringBuilder("[");
        for (var nodeKey : nodeKeys) {
            builder.append(nodeKey);
            if (nodeKey instanceof AEItemKey itemKey) {
                var customData = itemKey.get(DataComponents.CUSTOM_DATA);
                builder.append('[').append(customData == null ? "<no custom data>"
                        : customData.copyTag().toString()).append(']');
            }
            builder.append(", ");
        }
        return builder.append(']').toString();
    }

    /**
     * 一次编译里真正落到 {@code producers} 上的那两个代用键（按图的登记顺序交出，用于对照来序）。
     *
     * @param bbbRegisteredFirst 两份样板的登记序——在这里就是"那份清单交来的序"
     */
    private static List<AEKey> substituteNodeKeys(boolean bbbRegisteredFirst) {
        var patternAaa = FakePattern.crafting(SUBSTITUTE_AAA, 1, Map.of(REDSTONE, 1L));
        var patternBbb = FakePattern.crafting(SUBSTITUTE_BBB, 1, Map.of(REDSTONE, 1L));
        var topPattern = FakePattern.of(TOP, 1,
                FakeInput.multi(1, CANDIDATE, SUBSTITUTE_AAA, SUBSTITUTE_BBB));
        var builder = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L, SUBSTITUTE_AAA, 5L, SUBSTITUTE_BBB, 5L));
        if (bbbRegisteredFirst) {
            builder.pattern(SUBSTITUTE_BBB, patternBbb).pattern(SUBSTITUTE_AAA, patternAaa);
        } else {
            builder.pattern(SUBSTITUTE_AAA, patternAaa).pattern(SUBSTITUTE_BBB, patternBbb);
        }
        var grid = builder.pattern(TOP, topPattern).build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

        var nodeKeys = new ArrayList<AEKey>();
        for (AEKey nodeKey : graph.producersByOutput().keySet()) {
            if (nodeKey.equals(SUBSTITUTE_AAA) || nodeKey.equals(SUBSTITUTE_BBB)) {
                nodeKeys.add(nodeKey);
            }
        }
        // 夹具前提：只有一条代用路被选中，否则"节点身份"这条读数压根没跑
        assertEquals(1, nodeKeys.size(), () -> "两个代用变体里应当只落进一个节点键。实际=" + nodeKeys);
        return List.copyOf(nodeKeys);
    }

    /** 同 item 的组件变体：自定义数据里一枚字符串标记（AE2 的模糊分族按 primary key，正是要这一形）。 */
    private static AEItemKey componentKey(Item item, String mark) {
        var stack = new ItemStack(item);
        var tag = new CompoundTag();
        tag.putString("mark", mark);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    /**
     * MF-3（台账 #143）：可损伤 item 的两档——满耐久（补丁表为空，但照样带 9 项默认组件，实测）与
     * {@code DamageValue=5}（补丁表非空）。这一条钉住<b>实测的</b>产出次序：<b>带组件补丁那一档在前</b>，
     * 由 {@code "minecraft:iron_pickaxe (with patches)…"} 里那个空格（{@code 0x20}）排在无补丁那一档的
     * 组件段 {@code "["}（{@code 0x5B}）之前决定——不是按耐久算出来的。
     * AE2 那一侧这把尺不存在：它按 {@code FuzzySearch.KeyComparator} 排耐久档
     * （{@code reference-sources/ae2-19.2.17-sources/appeng/api/stacks/FuzzySearch.java:97-108}，
     * 受损重的在前、并列才退到 {@code hashCode}）。这一对恰好同向是巧合，同宽数字那档就反向
     * （见 {@code fuzzyClassOrderIsNotAe2DamagedFirstForSameWidthDamageValues}）。
     * 夹具把<b>该输的那一档</b>（满耐久）先登记：撤掉族内排序 ⇒ 交回 {@code [满耐久, 受损]}，这条红。
     */
    @Test
    void fuzzyClassPutsComponentPatchedVariantFirst() {
        assertEquals(List.of(renderDamage(PICKAXE_DAMAGED), renderDamage(PICKAXE_UNDAMAGED)),
                renderDamageOrder(ProviderPatternOrder.fuzzyClassView(pickaxeGrid(false).crafting)
                        .craftableClass(PICKAXE_CANDIDATE)),
                () -> "模糊档的产出次序＝稳定键那串的字典序：带组件补丁那一档在前（空格 0x20 < 组件段 [），"
                        + "这一档把满耐久那张先登记，撤掉排序就交回 [满耐久, 受损]");
        assertEquals(List.of(PICKAXE_DAMAGED), pickaxeNodeKeys(false),
                () -> "满耐久那张先登记时，节点身份仍必须落在带补丁那一档（按稳定键，不按来序）");
        assertEquals(pickaxeNodeKeys(false), pickaxeNodeKeys(true),
                () -> "登记序换了就换节点身份＝还在吃来序，没有定序");
    }

    /**
     * MF-3 点名的副作用：族内排序走<b>字面字典序</b>，所以组件<b>数值大小不按数值序</b>
     * ——{@code int:10} 排在 {@code int:9} 之前。这一条把该副作用钉成口径（不是数值序），
     * 谁把它换成数值序或撤掉排序（夹具把 9 先登记），这条就红。
     */
    @Test
    void fuzzyClassOrdersDamageReadingsLexicographicallyNotNumerically() {
        assertEquals(List.of(renderDamage(SWORD_TEN), renderDamage(SWORD_NINE)),
                renderDamageOrder(ProviderPatternOrder.fuzzyClassView(swordGrid().crafting)
                        .craftableClass(SWORD_NINE)),
                () -> "字典序副作用：int:10 在 int:9 之前（数值序会给出相反的两档）");
    }

    /**
     * MF-3 的正主："这改的是挑选次序，不只是定序"——两档只差一位数（{@code int:8} 与 {@code int:9}）时
     * 字典序给出<b>耐久升序</b>（轻损在前），而 AE2 的 {@code FuzzySearch} 是<b>受损重的在前</b>
     * （{@code FuzzySearch.java:97-98}）⇒ 两把尺在这一对<b>给出相反的先手</b>。
     * 夹具把受损更重的那张先登记：撤掉排序就交回 {@code [int:9, int:8]}，这条红。
     */
    @Test
    void fuzzyClassOrderIsNotAe2DamagedFirstForSameWidthDamageValues() {
        assertEquals(List.of(renderDamage(AXE_LIGHTLY_DAMAGED), renderDamage(AXE_MORE_DAMAGED)),
                renderDamageOrder(ProviderPatternOrder.fuzzyClassView(axeGrid().crafting)
                        .craftableClass(AXE_LIGHTLY_DAMAGED)),
                () -> "我们的口径是字典序＝耐久升序（int:8 在 int:9 前）；AE2 的受损优先给相反的先手");
    }

    /** 红字要认得出是哪一档在前：{@code AEItemKey#toString} 对带补丁的档只印 "(with patches)"。 */
    private static List<String> renderDamageOrder(List<AEKey> keys) {
        var rendered = new ArrayList<String>(keys.size());
        for (var key : keys) {
            rendered.add(renderDamage(key));
        }
        return List.copyOf(rendered);
    }

    private static String renderDamage(AEKey key) {
        return key + " damage=" + ((AEItemKey) key).getReadOnlyStack().getDamageValue();
    }

    /** 铁镐一族的两档：{@code damagedRegisteredFirst} 控制登记的来序（＝撤掉定序时会读到的那份序）。 */
    private static FakeGrid pickaxeGrid(boolean damagedRegisteredFirst) {
        var undamagedPattern = FakePattern.crafting(PICKAXE_UNDAMAGED, 1, Map.of(REDSTONE, 1L));
        var damagedPattern = FakePattern.crafting(PICKAXE_DAMAGED, 1, Map.of(REDSTONE, 1L));
        var topPattern = FakePattern.of(TOP, 1, FakeInput.anyVariant(PICKAXE_CANDIDATE, 1));
        var builder = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L, PICKAXE_UNDAMAGED, 5L, PICKAXE_DAMAGED, 5L));
        if (damagedRegisteredFirst) {
            builder.pattern(PICKAXE_DAMAGED, damagedPattern).pattern(PICKAXE_UNDAMAGED, undamagedPattern);
        } else {
            builder.pattern(PICKAXE_UNDAMAGED, undamagedPattern).pattern(PICKAXE_DAMAGED, damagedPattern);
        }
        return builder.pattern(TOP, topPattern).build();
    }

    /** 铁剑一族的两档（都带 DamageValue，只有数值不同）：登记序把 {@code int:9} 摆在前面。 */
    private static FakeGrid swordGrid() {
        var nine = FakePattern.crafting(SWORD_NINE, 1, Map.of(REDSTONE, 1L));
        var ten = FakePattern.crafting(SWORD_TEN, 1, Map.of(REDSTONE, 1L));
        return FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L, SWORD_NINE, 5L, SWORD_TEN, 5L))
                .pattern(SWORD_NINE, nine)
                .pattern(SWORD_TEN, ten)
                .build();
    }

    /** 铁斧一族的两档（同宽数字 8 与 9）：登记序把受损更重的 {@code int:9} 摆在前面。 */
    private static FakeGrid axeGrid() {
        var more = FakePattern.crafting(AXE_MORE_DAMAGED, 1, Map.of(REDSTONE, 1L));
        var lighter = FakePattern.crafting(AXE_LIGHTLY_DAMAGED, 1, Map.of(REDSTONE, 1L));
        return FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L, AXE_MORE_DAMAGED, 5L, AXE_LIGHTLY_DAMAGED, 5L))
                .pattern(AXE_MORE_DAMAGED, more)
                .pattern(AXE_LIGHTLY_DAMAGED, lighter)
                .build();
    }

    /** 一次编译里 {@code TOP} 那格真正落进 {@code producers} 的铁镐变体（应为带补丁那一档）。 */
    private static List<AEKey> pickaxeNodeKeys(boolean damagedRegisteredFirst) {
        var grid = pickaxeGrid(damagedRegisteredFirst);
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

        var nodeKeys = new ArrayList<AEKey>();
        for (AEKey nodeKey : graph.producersByOutput().keySet()) {
            if (nodeKey.equals(PICKAXE_UNDAMAGED) || nodeKey.equals(PICKAXE_DAMAGED)) {
                nodeKeys.add(nodeKey);
            }
        }
        // 夹具前提：只有一档被选中，否则"节点身份"这条读数压根没跑
        assertEquals(1, nodeKeys.size(), () -> "两档铁镐里应当只落进一个节点键。实际=" + nodeKeys);
        return List.copyOf(nodeKeys);
    }

    /** 可损伤 item 的一档：只带 DamageValue（走 AE2 的 {@code FuzzySearch} 那一族形状）。 */
    private static AEItemKey damagedKey(Item item, int damage) {
        var stack = new ItemStack(item);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }
}
