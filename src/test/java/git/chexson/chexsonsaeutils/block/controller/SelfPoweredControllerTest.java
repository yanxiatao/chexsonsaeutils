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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 使用加载后的真实 AE2 实体验证供电注入、原类型复用与存档恢复。 */
class SelfPoweredControllerTest {
    @Test
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

    @Test
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

    @Test
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
}
