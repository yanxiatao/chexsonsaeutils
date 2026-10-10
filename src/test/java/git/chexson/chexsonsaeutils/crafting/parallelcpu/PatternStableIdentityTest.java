package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-3 pattern 稳定身份：{@link ParallelCraftingCpuLogic#patternIdentityTag} 今天交两段
 * {@code #i<hex>}（实例哈希，同会话内区分两张同名实例）＋ {@code #s<hex>}（<b>跨会话稳定</b>的
 * 配方摘要）。这里量的是那把新尺——同一个 JVM 造两张同配方实例，{@code #s} 必须逐字相同；
 * 换一张不同配方的，{@code #s} 必须不同。摘要只当指纹，不参与任何判据。
 *
 * <p>但 {@code #s} 的"稳定"只对<b>同一份配方</b>成立：useless 的
 * {@code ScaledProcessingPattern} 折叠壳会把输出枚数与槽倍率一起 ×k（连 definition 也换成带
 * {@code SMART_DOUBLING_OPERATIONS} 组件的那一枚），所以<b>壳前壳后不是同一个 {@code #s}</b>——
 * 见 {@link #scalingFoldingShellDoesNotPreserveStableDigest}。跨那道壳对号只能认名单 token 里的
 * {@code definition} ＋份数。
 */
final class PatternStableIdentityTest {

    private static final AEItemKey OUT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey IN_A = AEItemKey.of(Items.IRON_NUGGET);
    private static final AEItemKey IN_B = AEItemKey.of(Items.GOLD_NUGGET);

    /** 同定义、同输出、同输入槽的两张实例：{@code #s} 摘要必须一致（跨会话可对上号）。 */
    @Test
    void sameRecipeDifferentInstancesShareStableDigest() {
        var one = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_A);
        var two = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_A);

        assertEquals(stablePart(ParallelCraftingCpuLogic.patternIdentityTag(one)),
                stablePart(ParallelCraftingCpuLogic.patternIdentityTag(two)),
                "同配方的两张实例，#s 摘要必须逐字相同（这才是跨会话可比的身份）");
    }

    /** 换输入槽的键：配方变了，{@code #s} 摘要必须跟着变。 */
    @Test
    void differentRecipeYieldsDifferentStableDigest() {
        var a = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_A);
        var b = pattern(OUT, List.of(new GenericStack(OUT, 2L)), 3L, IN_B);

        assertNotEquals(stablePart(ParallelCraftingCpuLogic.patternIdentityTag(a)),
                stablePart(ParallelCraftingCpuLogic.patternIdentityTag(b)),
                "配方不同就换 #s：否则把两张不同的样板当成同一张");
    }

    /** 身份标形状：一格一 token、零空白，且同时带 {@code #i} 与 {@code #s} 两段。 */
    @Test
    void identityTagCarriesBothPartsAndNoWhitespace() {
        var tag = ParallelCraftingCpuLogic.patternIdentityTag(
                pattern(OUT, List.of(new GenericStack(OUT, 1L)), 1L, IN_A));
        assertTrue(tag.startsWith("#i"), "身份标要以 #i 段开头：" + tag);
        assertTrue(tag.contains("#s"), "身份标要带跨会话 #s 段：" + tag);
        assertTrue(tag.indexOf(' ') < 0, "身份标一格一 token，值内不许有空白：" + tag);
    }

    /** {@code null} 身份标退回可读的 {@code #i?#s?}，不抛、不留空段冒充有身份。 */
    @Test
    void nullPatternYieldsReadablePlaceholder() {
        var tag = ParallelCraftingCpuLogic.patternIdentityTag(null);
        assertTrue(tag.startsWith("#i?"), tag);
        assertTrue(tag.contains("#s"), tag);
    }

    /**
     * useless 折叠壳<b>不改写</b> {@code #s} 这句话不许留——本用例按读到的壳算式做一个等价壳，
     * 只折两条腿（{@code getOutputs()} 每枚 {@code amount × k}、
     * {@code getInputs()[i].getMultiplier() × k}），连 {@code definition} 都<b>原样留着</b>，
     * {@code #s} 照样变。真壳还有第三条腿更狠：
     * {@code ScaledProcessingPattern} 的 definition 是
     * {@code SmartDoublingPatterns.executionDefinition(inner, k)}——同一件物品上把
     * {@code SMART_DOUBLING_OPERATIONS} 组件先 remove 再 set 成 {@code k}。
     *
     * <p>依据（不是推断）：{@code useless_mod-1.21.1-2.3.9.jar} 里
     * {@code ScaledProcessingPattern} 与 {@code ScaledProcessingPattern$ScaledInput} 的字节码
     * （构造器对每个输出走 {@code Math.multiplyExact(GenericStack.amount, operationsPerPush)}，
     * {@code ScaledInput.getMultiplier} 对倍率走同一句乘法；留档见
     * {@code .scratch/javap-221R2-scaled-processing.txt}）。
     * 所以两档名单（{@code stage=exit} 对 {@code stage=build}）跨这道壳时，对号只认
     * token 里的 {@code definition} ＋份数，同名并列才退化到 {@code #i}/{@code #s} 分实例。
     */
    @Test
    void scalingFoldingShellDoesNotPreserveStableDigest() {
        var inner = pattern(AEItemKey.of(Items.IRON_INGOT), List.of(new GenericStack(
                AEItemKey.of(Items.IRON_NUGGET), 2L)), 3L, AEItemKey.of(Items.RAW_IRON));
        var shell = new ScalingShell(inner, 4L);

        assertEquals(inner.getDefinition(), shell.getDefinition(),
                () -> "夹具前提：这一臂只折份数两条腿，definition 逐字不动（真壳连它也要改）");
        assertNotEquals(stablePart(ParallelCraftingCpuLogic.patternIdentityTag(inner)),
                stablePart(ParallelCraftingCpuLogic.patternIdentityTag(shell)),
                () -> "折叠壳内外 #s 必须不同：摘要吃的是 outputs 与槽倍率，壳把两条都 ×k。"
                        + "壳内=" + ParallelCraftingCpuLogic.patternIdentityTag(shell)
                        + " 壳外=" + ParallelCraftingCpuLogic.patternIdentityTag(inner));
        assertEquals("#i" + Integer.toHexString(System.identityHashCode(shell))
                        + stablePart(ParallelCraftingCpuLogic.patternIdentityTag(shell)),
                ParallelCraftingCpuLogic.patternIdentityTag(shell),
                "身份标还是 #i+#s 两段（跨壳对不上号这件事由 #s 变、#i 也变共同决定）");
    }

    // ---- fixtures ----

    /**
     * 按 useless {@code ScaledProcessingPattern} 读到的算式做的等价壳：输出每枚 ×k、
     * 槽倍率 ×k、候选表与 definition 原样（真壳的 definition 那一条腿另由 javadoc 记着）。
     */
    private static final class ScalingShell implements IPatternDetails {
        private final IPatternDetails inner;
        private final long k;

        ScalingShell(IPatternDetails inner, long k) {
            this.inner = inner;
            this.k = k;
        }

        @Override
        public IInput[] getInputs() {
            var raw = inner.getInputs();
            var scaled = new IInput[raw.length];
            for (int index = 0; index < raw.length; index++) {
                scaled[index] = new StubInput(raw[index].getPossibleInputs(),
                        Math.multiplyExact(raw[index].getMultiplier(), k));
            }
            return scaled;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return inner.getOutputs().stream()
                    .map(stack -> new GenericStack(stack.what(),
                            Math.multiplyExact(stack.amount(), k)))
                    .toList();
        }

        @Override
        public AEItemKey getDefinition() {
            return inner.getDefinition();
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return inner.supportsPushInputsToExternalInventory();
        }
    }

    private static String stablePart(String tag) {
        int at = tag.indexOf("#s");
        assertTrue(at >= 0, "身份标缺 #s 段：" + tag);
        return tag.substring(at);
    }

    /** 一张只读身份的样板替身：definition/输出/单输入槽由参数定死，摘要就吃这几样。 */
    private static IPatternDetails pattern(AEItemKey definition, List<GenericStack> outputs,
            long multiplier, AEItemKey inputKey) {
        return new IPatternDetails() {
            @Override
            public IInput[] getInputs() {
                return new IInput[] { new StubInput(new GenericStack[] { new GenericStack(inputKey, 1L) },
                        multiplier) };
            }

            @Override
            public List<GenericStack> getOutputs() {
                return outputs;
            }

            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return false;
            }
        };
    }

    private static final class StubInput implements IPatternDetails.IInput {
        private final GenericStack[] possible;
        private final long multiplier;

        StubInput(GenericStack[] possible, long multiplier) {
            this.possible = possible;
            this.multiplier = multiplier;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(appeng.api.stacks.AEKey input, net.minecraft.world.level.Level level) {
            return possible.length > 0 && possible[0].what().equals(input);
        }

        @Override
        public appeng.api.stacks.AEKey getRemainingKey(appeng.api.stacks.AEKey template) {
            return null;
        }
    }
}
