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
import git.chexson.chexsonsaeutils.crafting.CraftingOutputRouter;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 供应器与可选模组桥接的行为场景；由类别测试统一调用。 */
public final class ProvidersScenarios {
    private ProvidersScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.blockentity.crafting",
                () -> ExtremeAssemblerBatchCountCases.verify(temporaryRoot),
                () -> LocalPatternProviderFacadeCases.verify(temporaryRoot)
        );
    }

    /** ExtremeAssemblerBatchCountCases 的独立场景夹具，按类别统一执行。 */
    public static final class ExtremeAssemblerBatchCountCases {
        private static final AEKey MATERIAL = AEItemKey.of(Items.IRON_ORE);
        private static final AEKey OUTPUT = AEItemKey.of(Items.IRON_INGOT);

        void stalledLogAmountRetainsAllDigitsAndAllKeys() {
            var buffer = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            BigInteger huge = BigInteger.TEN.pow(100);
            buffer.add(MATERIAL, huge);
            buffer.add(OUTPUT, huge.add(BigInteger.ONE));
            assertEquals(huge.multiply(BigInteger.TWO).add(BigInteger.ONE),
                    AbstractExtremeAssemblerHostBlockEntity.pendingVirtualOutputAmount(buffer));
            assertEquals(huge, buffer.getExact(MATERIAL));
        }

        void longMaxIsARealBatchRatherThanTheUninitializedMarker() {
            var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(OUTPUT, 1L));
            var held = new KeyCounter();
            held.add(MATERIAL, Long.MAX_VALUE);
            assertEquals(Long.MAX_VALUE, AbstractExtremeAssemblerHostBlockEntity.deriveHolderExecutions(
                    pattern, new KeyCounter[]{held}));
        }

        void finalPartialBatchKeepsItsActualCount() {
            var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 4L), new GenericStack(OUTPUT, 1L));
            var held = new KeyCounter();
            held.add(MATERIAL, 28L);
            assertEquals(7L, AbstractExtremeAssemblerHostBlockEntity.deriveHolderExecutions(
                    pattern, new KeyCounter[]{held}));
        }

        void insufficientInputCannotBeReportedAsOneSuccessfulFiring() {
            var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 4L), new GenericStack(OUTPUT, 1L));
            var held = new KeyCounter();
            held.add(MATERIAL, 3L);
            assertEquals(0L, AbstractExtremeAssemblerHostBlockEntity.deriveHolderExecutions(
                    pattern, new KeyCounter[]{held}));
        }
        void dismantledMachineCarriesExactOutputsThroughItemSerialization() {
            var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                    net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var outputs = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            var exact = BigInteger.TEN.pow(100).add(BigInteger.valueOf(17));
            outputs.add(OUTPUT, exact);
            outputs.add(MATERIAL, BigInteger.valueOf(9));
            var metadata = new net.minecraft.nbt.CompoundTag();
            metadata.putString("unrelated", "preserved");
            var components = net.minecraft.core.component.DataComponentMap.builder();
            components.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(metadata));
            AbstractExtremeAssemblerHostBlockEntity.exportDismantleOutputs(appeng.util.SettingsFrom.DISMANTLE_ITEM,
                    components, outputs, registries);
            var carrier = new ItemStack(Items.CHEST);
            carrier.applyComponents(components.build());
            var savedItem = carrier.saveOptional(registries);
            outputs.clear();
            var restoredItem = ItemStack.parse(registries, savedItem).orElseThrow();
            assertEquals(1, restoredItem.getCount());
            assertTrue(savedItem.toString().length() < 4096, "物品载体大小只随种类与数字位数增长");
            assertEquals("preserved", restoredItem.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
                    .copyTag().getString("unrelated"));
            var restored = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            assertTrue(AbstractExtremeAssemblerHostBlockEntity.importDismantleOutputs(appeng.util.SettingsFrom.DISMANTLE_ITEM,
                    restoredItem.getComponents(), restored, registries));
            assertEquals(exact, restored.getExact(OUTPUT));
            assertEquals(BigInteger.valueOf(9), restored.getExact(MATERIAL));
            var payload = AbstractExtremeAssemblerHostBlockEntity.virtualOutputPayload(restored);
            AbstractExtremeAssemblerHostBlockEntity.accountVirtualDelivery(restored, payload, List.of());
            assertEquals(exact.subtract(BigInteger.valueOf(Long.MAX_VALUE)), restored.getExact(OUTPUT));
            assertEquals(BigInteger.ZERO, restored.getExact(MATERIAL));
        }

        void memoryCardCannotExportOrImportBufferedResources() {
            var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                    net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var outputs = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            outputs.add(OUTPUT, BigInteger.TEN);
            var settings = net.minecraft.core.component.DataComponentMap.builder();
            AbstractExtremeAssemblerHostBlockEntity.exportDismantleOutputs(appeng.util.SettingsFrom.MEMORY_CARD,
                    settings, outputs, registries);
            assertFalse(settings.build().has(net.minecraft.core.component.DataComponents.CUSTOM_DATA));
            var recovery = net.minecraft.core.component.DataComponentMap.builder();
            AbstractExtremeAssemblerHostBlockEntity.exportDismantleOutputs(appeng.util.SettingsFrom.DISMANTLE_ITEM,
                    recovery, outputs, registries);
            var target = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            target.add(MATERIAL, BigInteger.ONE);
            assertFalse(AbstractExtremeAssemblerHostBlockEntity.importDismantleOutputs(appeng.util.SettingsFrom.MEMORY_CARD,
                    recovery.build(), target, registries));
            assertEquals(BigInteger.ZERO, target.getExact(OUTPUT));
            assertEquals(BigInteger.ONE, target.getExact(MATERIAL));
            assertEquals(BigInteger.TEN, outputs.getExact(OUTPUT));
        }

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

        void restoredAssemblerOutputCanBeClaimedWithoutLongSegmentation() {
            var pattern = pattern(Items.FURNACE, new GenericStack(MATERIAL, 1L), new GenericStack(OUTPUT, 80L));
            var prototype = new KeyCounter();
            prototype.add(MATERIAL, 1L);
            var firings = BigInteger.TEN.pow(30).add(BigInteger.valueOf(17));
            var total = firings.multiply(BigInteger.valueOf(80));
            var buffer = AbstractExtremeAssemblerHostBlockEntity.virtualOutputs(pattern,
                    new KeyCounter[]{prototype}, firings, true);
            var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                    net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var saved = AbstractExtremeAssemblerHostBlockEntity.writeVirtualOutputs(buffer, registries);
            var restored = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            AbstractExtremeAssemblerHostBlockEntity.readVirtualOutputs(restored, saved, registries);
            var grid = git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder().build();
            var delivered = new WideCraftingInventory(ignored -> {});
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            CraftingOutputRouter.claimBufferedOutputs(restored, grid, (sourceGrid, what, available) -> {
                assertEquals(grid, sourceGrid);
                assertEquals(OUTPUT, what);
                assertEquals(total, available);
                calls.incrementAndGet();
                var accepted = available.subtract(BigInteger.valueOf(7));
                delivered.insertExact(what, accepted, Actionable.MODULATE);
                return accepted;
            });
            assertEquals(1, calls.get(), "回流操作次数只由产物键数决定，不随总量增加");
            assertEquals(total.subtract(BigInteger.valueOf(7)), delivered.amount(OUTPUT));
            assertEquals(BigInteger.valueOf(7), restored.getExact(OUTPUT));
            var payload = AbstractExtremeAssemblerHostBlockEntity.virtualOutputPayload(restored);
            assertEquals(List.of(new GenericStack(OUTPUT, 7L)), payload);
            AbstractExtremeAssemblerHostBlockEntity.accountVirtualDelivery(restored, payload, List.of());
            assertTrue(restored.isEmpty());
        }

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases",
                () -> new ExtremeAssemblerBatchCountCases().dismantledMachineCarriesExactOutputsThroughItemSerialization(),
                () -> new ExtremeAssemblerBatchCountCases().memoryCardCannotExportOrImportBufferedResources(),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.stalledLogAmountRetainsAllDigitsAndAllKeys", () -> new ExtremeAssemblerBatchCountCases().stalledLogAmountRetainsAllDigitsAndAllKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.longMaxIsARealBatchRatherThanTheUninitializedMarker", () -> new ExtremeAssemblerBatchCountCases().longMaxIsARealBatchRatherThanTheUninitializedMarker()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.finalPartialBatchKeepsItsActualCount", () -> new ExtremeAssemblerBatchCountCases().finalPartialBatchKeepsItsActualCount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.insufficientInputCannotBeReportedAsOneSuccessfulFiring", () -> new ExtremeAssemblerBatchCountCases().insufficientInputCannotBeReportedAsOneSuccessfulFiring()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.wideOutputSurvivesReloadAndPartialSegmentDelivery", () -> new ExtremeAssemblerBatchCountCases().wideOutputSurvivesReloadAndPartialSegmentDelivery()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.restoredAssemblerOutputCanBeClaimedWithoutLongSegmentation", () -> new ExtremeAssemblerBatchCountCases().restoredAssemblerOutputCanBeClaimedWithoutLongSegmentation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.remainderAndProductSharingAKeyAreMergedBeyondLong", () -> new ExtremeAssemblerBatchCountCases().remainderAndProductSharingAKeyAreMergedBeyondLong()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExtremeAssemblerBatchCountCases.lastSmallSegmentClearsTheBufferExactly", () -> new ExtremeAssemblerBatchCountCases().lastSmallSegmentClearsTheBufferExactly())
            );
        }
    }

    /** LocalPatternProviderFacadeCases 的独立场景夹具，按类别统一执行。 */
    public static final class LocalPatternProviderFacadeCases {
        void largePatternRosterDoesNotReinterpretRegisteredDefinitionsOnEveryAdmission() {
            var facade = new LocalPatternProviderFacade(null, null, null, null);
            var patterns = new java.util.ArrayList<appeng.api.crafting.IPatternDetails>();
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            for (int index = 0; index < 1468; index++) {
                var definition = definition(index, 0x123456);
                patterns.add(countingPattern(definition, reads));
            }
            facade.replaceAvailablePatterns(patterns);
            reads.set(0);
            for (int round = 0; round < 4; round++) {
                for (var pattern : patterns) assertTrue(facade.contains(pattern));
            }
            assertEquals(0, reads.get(), "已登记对象的准入不应复制或规范化任何定义");
            var equivalent = countingPattern(definition(1467, 0x654321), reads);
            assertTrue(facade.contains(equivalent), "去色后完整定义相同的壳仍可用");
            assertEquals(1, reads.get(), "等价壳只读取自身定义，不遍历登记表");
            assertFalse(facade.contains(countingPattern(definition(2000, 0), reads)));
            facade.replaceAvailablePatterns(List.of(patterns.getFirst()));
            assertFalse(facade.contains(equivalent), "删样板后身份索引不能留旧条目");
            assertFalse(facade.contains(patterns.getLast()));
            facade.clear();
            assertFalse(facade.contains(patterns.getFirst()));
        }

        private static AEItemKey definition(int tier, int color) {
            var stack = new net.minecraft.world.item.ItemStack(Items.PAPER);
            var data = new net.minecraft.nbt.CompoundTag();
            data.putInt("compressionTier", tier);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(data));
            stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                    new net.minecraft.world.item.component.DyedItemColor(color, false));
            return AEItemKey.of(stack);
        }

        private static appeng.api.crafting.IPatternDetails countingPattern(AEItemKey definition,
                java.util.concurrent.atomic.AtomicInteger reads) {
            return new appeng.api.crafting.IPatternDetails() {
                @Override public AEItemKey getDefinition() { reads.incrementAndGet(); return definition; }
                @Override public IInput[] getInputs() { return new IInput[0]; }
                @Override public List<GenericStack> getOutputs() {
                    return List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1));
                }
            };
        }

        void colorDoesNotChangeUniqueProducerSetOrOrder() {
            var output = AEItemKey.of(Items.IRON_INGOT);
            var uncolored = FakePattern.colored(
                    -1,
                    AEItemKey.of(Items.PAPER),
                    List.of(new GenericStack(output, 1)));
            var red = FakePattern.colored(
                    0xFFFF0000,
                    FakePlanGrid.dyedKey(Items.PAPER, 0xFFFF0000),
                    List.of(new GenericStack(output, 1)));
            var blue = FakePattern.colored(
                    0xFF0000FF,
                    FakePlanGrid.dyedKey(Items.PAPER, 0xFF0000FF),
                    List.of(new GenericStack(output, 1)));
            var distinct = FakePattern.colored(
                    -1,
                    AEItemKey.of(Items.COBBLESTONE),
                    List.of(new GenericStack(output, 1)));

            var unique = LocalPatternProviderFacade.uniquePatternsInOrder(
                    List.of(red, uncolored, distinct, blue));

            assertEquals(2, unique.size());
            assertSame(red, unique.get(0));
            assertSame(distinct, unique.get(1));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("LocalPatternProviderFacadeCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("LocalPatternProviderFacadeCases.largePatternRosterDoesNotReinterpretRegisteredDefinitionsOnEveryAdmission", () -> new LocalPatternProviderFacadeCases().largePatternRosterDoesNotReinterpretRegisteredDefinitionsOnEveryAdmission()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LocalPatternProviderFacadeCases.colorDoesNotChangeUniqueProducerSetOrOrder", () -> new LocalPatternProviderFacadeCases().colorDoesNotChangeUniqueProducerSetOrOrder())
            );
        }
    }
}
