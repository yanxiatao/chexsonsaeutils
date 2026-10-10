package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2d B4 —— 守恒闸门自测：账目与 crafts 不一致的失衡形态必须被拦下
 * （闭式符号错误的后果是「缺料计划判可行」），一致账目必须放行。测试直接
 * 构造 state 与 graph，不经过 solve（solve 侧口径由等价对拍锁）。
 */
class MaterialClosureGateTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    /** IRON×1 ← GOLD×2 的单样板图。 */
    private static CraftGraph graph(GraphPattern pattern) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(pattern.source(), pattern),
                List.of(pattern.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GOLD, 100L),
                java.util.Set.of(),
                Map.of(), false, null, 1, 1);
    }

    private static GraphPattern ironFromGoldPattern() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, 2L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    @Test
    void balancedLedgerPasses() {
        var pattern = ironFromGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(pattern.source(), 10);
        state.extract(GOLD, 20, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
        assertNull(violation, () -> "平衡账目不应被拦：" + violation);
    }

    @Test
    void underExtractedInputIsCaught() {
        // 输入消耗漏记形态：crafts 全量计 demand，used 只有一半——
        // 「缺料计划判可行」正是这个形态。
        var pattern = ironFromGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(pattern.source(), 10);
        state.extract(GOLD, 10, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
        assertNotNull(violation, "输入漏记必须被闸门拦下");
        assertTrue(violation.contains("gold_ingot"), violation);
    }

    @Test
    void outputShortfallAgainstFinalIsCaught() {
        // 产出凭空缩水形态：crafts 只有 5 次，final 却要 10——
        // supply(IRON)=5 < demand(IRON)=10。
        var pattern = ironFromGoldPattern();
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(pattern.source(), 5);
        state.extract(GOLD, 10, false);

        String violation = MaterialClosureGate.validate(state, graph(pattern), 10);
        assertNotNull(violation, "产出缩水必须被闸门拦下");
        assertTrue(violation.contains("iron_ingot"), violation);
    }

    @Test
    void siblingComponentSupplyCannotSatisfyExactDemand() {
        var firstStack = new ItemStack(Items.AMETHYST_SHARD);
        firstStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("version-1"));
        var first = AEItemKey.of(firstStack);
        var secondStack = new ItemStack(Items.AMETHYST_SHARD);
        secondStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("version-2"));
        var second = AEItemKey.of(secondStack);
        Map<appeng.api.stacks.AEKey, List<appeng.api.stacks.AEKey>> variants =
                Map.of(first, List.of(first, second), second, List.of(first, second));
        var graph = new CraftGraph(second,
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(first, 1L), java.util.Set.of(), variants,
                false, null, 0, 0);
        var state = new GraphPlanningState(Map.of(first, 1L), variants);
        state.extract(first, 1L, false);

        String violation = MaterialClosureGate.validate(state, graph, 1L);

        assertNotNull(violation, "同一物品的其它组件版本不能满足精确需求");
        assertTrue(violation.contains("supply=0"), violation);
        assertTrue(violation.contains("demand=1"), violation);
    }

    /** 轻量样板替身：definition 只需要唯一身份。 */
    private static final class FakeDetails implements IPatternDetails {
        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return List.of();
        }

        @Override
        public AEItemKey getDefinition() {
            return key(Items.PAPER);
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
