package git.chexson.chexsonsaeutils.block.controller;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.core.definitions.AEBlocks;
import git.chexson.chexsonsaeutils.bootstrap.controller.SelfPoweredControllerRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import static org.junit.jupiter.api.Assertions.*;

/** 注册与配置的行为场景；由类别测试统一调用。 */
public final class RegistrationScenarios {
    private RegistrationScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.block.controller",
                () -> SelfPoweredControllerCases.verify(temporaryRoot)
        );
    }

    /** SelfPoweredControllerCases 的独立场景夹具，按类别统一执行。 */
    public static final class SelfPoweredControllerCases {
        void infiniteSupplyUsesOriginalControllerEntityAndDoesNotDrain() {
            var block = SelfPoweredControllerRegistration.BLOCK.get();
            var entity = assertInstanceOf(ControllerBlockEntity.class,
                    block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState()));
            assertEquals(ControllerBlockEntity.class, entity.getClass());
            assertSame(AEBlocks.CONTROLLER.block().getBlockEntityType(), entity.getType());
            assertTrue(entity.getType().isValid(block.defaultBlockState()));
            assertTrue(entity.isAEPublicPowerStorage());
            assertEquals(Integer.MAX_VALUE, entity.getPriority());
            double capacity = entity.getAECurrentPower();
            assertEquals((double) Long.MAX_VALUE, capacity);
            for (int i = 0; i < 10; i++) {
                assertEquals(1e18, entity.extractAEPower(1e18, Actionable.MODULATE, PowerMultiplier.CONFIG));
                assertEquals(1e18, entity.extractAEPower(1e18, Actionable.SIMULATE, PowerMultiplier.ONE));
                assertEquals(0, entity.injectAEPower(1e18, Actionable.MODULATE));
                assertEquals(0, entity.injectAEPower(1e18, Actionable.SIMULATE));
                assertEquals(capacity, entity.getAECurrentPower());
                assertEquals(capacity, entity.getAEMaxPower());
            }
        }

        void savedOriginalTypeRestoresSelfPoweredBlock() {
            var block = SelfPoweredControllerRegistration.BLOCK.get();
            var state = block.defaultBlockState();
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var entity = block.newBlockEntity(BlockPos.ZERO, state);
            var loaded = assertInstanceOf(ControllerBlockEntity.class,
                    BlockEntity.loadStatic(BlockPos.ZERO, state, entity.saveWithFullMetadata(registries), registries));
            assertSame(entity.getType(), loaded.getType());
            assertTrue(loaded.getType().isValid(state));
            assertEquals(1e18, loaded.extractAEPower(1e18, Actionable.MODULATE, PowerMultiplier.ONE));
        }

        void ordinaryControllerRetainsFiniteBuffer() {
            var block = AEBlocks.CONTROLLER.block();
            var entity = assertInstanceOf(ControllerBlockEntity.class,
                    block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState()));
            assertEquals(8000, entity.getAEMaxPower());
            assertEquals(0, entity.getPriority());
            assertEquals(0, entity.injectAEPower(100, Actionable.MODULATE));
            assertEquals(100, entity.extractAEPower(1000, Actionable.MODULATE, PowerMultiplier.ONE));
            assertEquals(0, entity.getAECurrentPower());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SelfPoweredControllerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SelfPoweredControllerCases.infiniteSupplyUsesOriginalControllerEntityAndDoesNotDrain", () -> new SelfPoweredControllerCases().infiniteSupplyUsesOriginalControllerEntityAndDoesNotDrain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SelfPoweredControllerCases.savedOriginalTypeRestoresSelfPoweredBlock", () -> new SelfPoweredControllerCases().savedOriginalTypeRestoresSelfPoweredBlock()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SelfPoweredControllerCases.ordinaryControllerRetainsFiniteBuffer", () -> new SelfPoweredControllerCases().ordinaryControllerRetainsFiniteBuffer())
            );
        }
    }
}
