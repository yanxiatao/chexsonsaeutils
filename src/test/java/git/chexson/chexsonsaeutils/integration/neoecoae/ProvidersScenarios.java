package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import java.math.BigInteger;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 供应器与可选模组桥接的行为场景；由类别测试统一调用。 */
public final class ProvidersScenarios {
    private ProvidersScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.integration.neoecoae",
                () -> NeoEcoBatchOutputBufferCases.verify(temporaryRoot),
                () -> NeoEcoExactStorageInsertionCases.verify(temporaryRoot)
        );
    }

    /** NeoEcoBatchOutputBufferCases 的独立场景夹具，按类别统一执行。 */
    public static final class NeoEcoBatchOutputBufferCases {
        private static final AEItemKey OUTPUT = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey REMAINDER = AEItemKey.of(Items.BUCKET);

        void oneDispatchBuffersEveryExecutionAndRemainder() {
            var target = new KeyCounter();

            assertTrue(NeoEcoBatchOutputBuffer.append(
                    target,
                    List.of(new GenericStack(OUTPUT, 2L)),
                    List.of(new GenericStack(REMAINDER, 1L)),
                    1024L
            ));
            assertEquals(2048L, target.get(OUTPUT));
            assertEquals(1024L, target.get(REMAINDER));
        }

        void keepsSiblingComponentOutputsSeparate() {
            AEItemKey first = namedOutput("first");
            AEItemKey second = namedOutput("second");
            var target = new KeyCounter();

            assertTrue(NeoEcoBatchOutputBuffer.append(
                    target,
                    List.of(new GenericStack(first, 1L), new GenericStack(second, 2L)),
                    List.of(),
                    3L
            ));
            assertEquals(3L, target.get(first));
            assertEquals(6L, target.get(second));
            assertEquals(2, target.size());
        }

        void assemblerWideBufferMergesProductsAndRemaindersWithoutClamping() {
            var target = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
            assertTrue(NeoEcoBatchOutputBuffer.append(target,
                    List.of(new GenericStack(OUTPUT, 4L)), List.of(new GenericStack(OUTPUT, 2L)), Long.MAX_VALUE));
            assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(6L)),
                    target.getExact(OUTPUT));
        }

        private static AEItemKey namedOutput(String name) {
            ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            return AEItemKey.of(stack);
        }

        void rejectsNonPositiveExecutionCount() {
            assertFalse(NeoEcoBatchOutputBuffer.append(
                    new KeyCounter(),
                    List.of(new GenericStack(OUTPUT, 1L)),
                    List.of(),
                    0L
            ));
        }

        void accumulatedOutputSaturatesInsteadOfOverflowing() {
            var target = new KeyCounter();
            target.add(OUTPUT, Long.MAX_VALUE - 5L);

            assertTrue(NeoEcoBatchOutputBuffer.append(
                    target,
                    List.of(new GenericStack(OUTPUT, 4L)),
                    List.of(),
                    2L
            ));
            assertEquals(Long.MAX_VALUE, target.get(OUTPUT));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("NeoEcoBatchOutputBufferCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("NeoEcoBatchOutputBufferCases.oneDispatchBuffersEveryExecutionAndRemainder", () -> new NeoEcoBatchOutputBufferCases().oneDispatchBuffersEveryExecutionAndRemainder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NeoEcoBatchOutputBufferCases.keepsSiblingComponentOutputsSeparate", () -> new NeoEcoBatchOutputBufferCases().keepsSiblingComponentOutputsSeparate()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NeoEcoBatchOutputBufferCases.assemblerWideBufferMergesProductsAndRemaindersWithoutClamping", () -> new NeoEcoBatchOutputBufferCases().assemblerWideBufferMergesProductsAndRemaindersWithoutClamping()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NeoEcoBatchOutputBufferCases.rejectsNonPositiveExecutionCount", () -> new NeoEcoBatchOutputBufferCases().rejectsNonPositiveExecutionCount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("NeoEcoBatchOutputBufferCases.accumulatedOutputSaturatesInsteadOfOverflowing", () -> new NeoEcoBatchOutputBufferCases().accumulatedOutputSaturatesInsteadOfOverflowing())
            );
        }
    }

    /** NeoEcoExactStorageInsertionCases 的独立场景夹具，按类别统一执行。 */
    public static final class NeoEcoExactStorageInsertionCases {
        void ownDiskReceivesFullAmountAndSimulationDoesNotWrite() {
            var key = AEItemKey.of(Items.IRON_INGOT);
            var huge = BigInteger.TEN.pow(30);
            var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
            assertEquals(huge, NeoEcoExactStorageInsertion.insert(cell, key, huge, Actionable.SIMULATE, IActionSource.empty()));
            assertEquals(BigInteger.ZERO, cell.exactAmount(key));
            assertEquals(huge, NeoEcoExactStorageInsertion.insert(cell, key, huge, Actionable.MODULATE, IActionSource.empty()));
            assertEquals(huge, cell.exactAmount(key));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("NeoEcoExactStorageInsertionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("NeoEcoExactStorageInsertionCases.ownDiskReceivesFullAmountAndSimulationDoesNotWrite", () -> new NeoEcoExactStorageInsertionCases().ownDiskReceivesFullAmountAndSimulationDoesNotWrite())
            );
        }
    }
}
