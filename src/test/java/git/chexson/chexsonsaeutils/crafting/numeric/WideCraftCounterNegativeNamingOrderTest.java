package git.chexson.chexsonsaeutils.crafting.numeric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;

/**
 * 份数账点名的"第一处负发数"＝<b>注册序</b>那一头，不是主表的哈希序（台账 #135 的收尾一条）。
 *
 * <p>缺陷形状：{@link WideCraftCounter#describeNegative()} 原先直接扫 {@code fast} 的
 * {@code object2LongEntrySet()}，那张表摆哪张在前由 {@code IPatternDetails#hashCode} 决定，
 * 而它一路取到 {@code AEItemKey → ItemStack.hashItemAndComponents → Item} 的<b>身份哈希</b>——
 * 同一份负发数账换一次 JVM 会话就点另一张样板的名。判据本体（{@link WideCraftCounter#hasNegative()}
 * 与它唯一的调用方 {@code GraphPlanningState#hasNegativeExitValues()}）读的也是同一只主表，
 * 判决方向不受影响，这一刀只把"先点哪一张"落回注册序。
 *
 * <p>期望值是独立算式：注册序的第 0 号样板就是第一处负发数（8 张全负、每张同一笔增量），
 * 与 {@link WideCraftCounter#orderedLongView()} 那份视图的键序同一条尺。
 */
final class WideCraftCounterNegativeNamingOrderTest {

    /** 故意不按字符串序摆的注册序：负发数点名应当落在头一号（{@code minecraft:dirt}）。 */
    private static final List<AEItemKey> CONTENT_ORDER = List.of(
            AEItemKey.of(Items.DIRT),
            AEItemKey.of(Items.NETHERRACK),
            AEItemKey.of(Items.SOUL_SOIL),
            AEItemKey.of(Items.PRISMARINE_SHARD),
            AEItemKey.of(Items.CLAY_BALL),
            AEItemKey.of(Items.RED_SAND),
            AEItemKey.of(Items.GUNPOWDER),
            AEItemKey.of(Items.FLINT));

    private static final long NEGATIVE_TIMES = -5L;

    /** 点名＝注册序头一号；实例的创建顺序反过来摆（身份哈希换一批）读数不许跟着换人。 */
    @Test
    void negativePatternIsNamedInTheOrderItWasFirstBilled() {
        var forward = readNaming(false);
        var backward = readNaming(true);

        assertEquals(CONTENT_ORDER.get(0) + " times=" + NEGATIVE_TIMES, forward.named(),
                "第一处负发数应当是账上第一个在册的那张样板，不是主表哈希摆到最前的那一张");
        assertEquals(forward.named(), backward.named(),
                "同一份账只换实例创建顺序（身份哈希不同）就换点名＝还在读哈希序");
        assertEquals(forward.orderedViewFirstNegative(), backward.orderedViewFirstNegative(),
                "注册序视图的头一号负发数与点名同一条尺");
        assertEquals(forward.orderedViewFirstNegative(), forward.named(),
                "点名与 orderedLongView() 的序分叉＝两处两套尺");
    }

    /** 一次构造：建账本 → 记下点名与注册序视图的头一号负发数。 */
    private static Naming readNaming(boolean reverseCreationOrder) {
        var instances = new ArrayList<NegativePattern>(CONTENT_ORDER.size());
        var creationOrder = new ArrayList<>(CONTENT_ORDER);
        if (reverseCreationOrder) {
            Collections.reverse(creationOrder);
        }
        var byKey = new java.util.LinkedHashMap<AEItemKey, NegativePattern>();
        for (var definition : creationOrder) {
            byKey.put(definition, new NegativePattern(definition));
        }
        for (var definition : CONTENT_ORDER) {
            instances.add(byKey.get(definition));
        }

        var counter = new WideCraftCounter();
        for (var pattern : instances) {
            counter.add(pattern, NEGATIVE_TIMES);
        }
        assertTrue(counter.hasNegative(), "夹具前提：这一跑必须真有负发数");
        var named = counter.describeNegative();
        assertNotNull(named, "有负发数却点不出名＝读数自己掉了");

        // 注册序视图的头一号负发数：与点名应当是同一条尺（两份读数各自独立算，不互为断言）
        var viewFirstNegative = "<none>";
        for (var entry : counter.orderedLongView().entrySet()) {
            if (entry.getValue() < 0L) {
                viewFirstNegative = entry.getKey().getDefinition() + " times=" + entry.getValue();
                break;
            }
        }
        return new Naming(named, viewFirstNegative);
    }

    /** 一次构造的两处读数：点名串与注册序视图的头一号负发数串。 */
    private record Naming(String named, String orderedViewFirstNegative) {
    }

    /** 只有 definition 有内容的样板替身（{@code describeNegative} 只读它）。 */
    private static final class NegativePattern implements IPatternDetails {

        private final AEItemKey definition;

        private NegativePattern(AEItemKey definition) {
            this.definition = definition;
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(definition, 1L));
        }

        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass() == getClass()
                    && ((NegativePattern) obj).definition.equals(definition);
        }

        @Override
        public int hashCode() {
            return definition.hashCode();
        }
    }
}
