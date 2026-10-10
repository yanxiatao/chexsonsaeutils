package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接调用装配器实际使用的 holder 批量反推入口。 */
final class ExtremeAssemblerBatchCountTest {
    private static final AEKey MATERIAL = AEItemKey.of(Items.IRON_ORE);
    private static final AEKey OUTPUT = AEItemKey.of(Items.IRON_INGOT);

    @Test
    void longMaxIsARealBatchRatherThanTheUninitializedMarker() {
        var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(OUTPUT, 1L));
        var held = new KeyCounter();
        held.add(MATERIAL, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, AbstractExtremeAssemblerHostBlockEntity.deriveHolderExecutions(
                pattern, new KeyCounter[]{held}));
    }

    @Test
    void finalPartialBatchKeepsItsActualCount() {
        var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 4L), new GenericStack(OUTPUT, 1L));
        var held = new KeyCounter();
        held.add(MATERIAL, 28L);
        assertEquals(7L, AbstractExtremeAssemblerHostBlockEntity.deriveHolderExecutions(
                pattern, new KeyCounter[]{held}));
    }

    @Test
    void insufficientInputCannotBeReportedAsOneSuccessfulFiring() {
        var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 4L), new GenericStack(OUTPUT, 1L));
        var held = new KeyCounter();
        held.add(MATERIAL, 3L);
        assertEquals(0L, AbstractExtremeAssemblerHostBlockEntity.deriveHolderExecutions(
                pattern, new KeyCounter[]{held}));
    }
    @Test
    void wideOutputSurvivesReloadAndPartialSegmentDelivery() {
        var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(OUTPUT, 80L));
        var prototype = new KeyCounter();
        prototype.add(MATERIAL, 1L);
        BigInteger firings = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3L));
        var buffer = AbstractExtremeAssemblerHostBlockEntity.virtualOutputs(pattern,
                new KeyCounter[]{prototype}, firings, true);
        BigInteger total = firings.multiply(BigInteger.valueOf(80L));
        assertEquals(total, buffer.getExact(OUTPUT));
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var saved = AbstractExtremeAssemblerHostBlockEntity.writeVirtualOutputs(buffer, registries);
        var restored = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        AbstractExtremeAssemblerHostBlockEntity.readVirtualOutputs(restored, saved, registries);
        assertEquals(total, restored.getExact(OUTPUT));
        var payload = AbstractExtremeAssemblerHostBlockEntity.virtualOutputPayload(restored);
        assertEquals(List.of(new GenericStack(OUTPUT, Long.MAX_VALUE)), payload);
        AbstractExtremeAssemblerHostBlockEntity.accountVirtualDelivery(restored, payload,
                List.of(new GenericStack(OUTPUT, 7L)));
        assertEquals(total.subtract(BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.valueOf(7L)),
                restored.getExact(OUTPUT));
    }

    @Test
    void remainderAndProductSharingAKeyAreMergedBeyondLong() {
        var input = new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(MATERIAL, 1L)}; }
            @Override public long getMultiplier() { return 1L; }
            @Override public boolean isValid(AEKey key, net.minecraft.world.level.Level level) { return MATERIAL.equals(key); }
            @Override public AEKey getRemainingKey(AEKey template) { return OUTPUT; }
        };
        var pattern = new FakePattern(AEItemKey.of(Items.FURNACE),
                new GenericStack[]{new GenericStack(OUTPUT, 2L)}, new IPatternDetails.IInput[]{input});
        var prototype = new KeyCounter();
        prototype.add(MATERIAL, 1L);
        var firings = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        var buffer = AbstractExtremeAssemblerHostBlockEntity.virtualOutputs(pattern,
                new KeyCounter[]{prototype}, firings, true);
        assertEquals(firings.multiply(BigInteger.valueOf(3L)), buffer.getExact(OUTPUT));
    }

    @Test
    void lastSmallSegmentClearsTheBufferExactly() {
        var buffer = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        buffer.add(OUTPUT, 7L);
        var payload = AbstractExtremeAssemblerHostBlockEntity.virtualOutputPayload(buffer);
        assertEquals(List.of(new GenericStack(OUTPUT, 7L)), payload);
        AbstractExtremeAssemblerHostBlockEntity.accountVirtualDelivery(buffer, payload, List.of());
        assertTrue(buffer.isEmpty());
    }

    /** 一张只有一个候选、原样吃掉给定输入的单槽样板（退料键为 null ⇒ 不是模具槽）。 */
    private static FakePattern pattern(Item definition, GenericStack input, GenericStack output) {
        return new FakePattern(AEItemKey.of(new ItemStack(definition)),
                new GenericStack[] {output},
                new IPatternDetails.IInput[] {new FakeInput(input)});
    }

    private record FakePattern(AEItemKey definition, GenericStack[] outputs,
            IPatternDetails.IInput[] slots) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(outputs);
        }
    }

    private record FakeInput(GenericStack candidate) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {candidate};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return candidate != null && candidate.what().equals(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
