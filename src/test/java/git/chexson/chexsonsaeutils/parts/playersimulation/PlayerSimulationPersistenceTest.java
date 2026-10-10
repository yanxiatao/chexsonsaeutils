package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSimulationPersistenceTest {
    private PlayerSimulationPart create() {
        var part = new PlayerSimulationPart((IPartItem<?>) ChexsonsaeutilsContent.PLAYER_SIMULATION_PLANE_ITEM.get(),
                (player,opened) -> { throw new AssertionError("此测试不打开 GUI"); });
        var host = new appeng.blockentity.networking.CableBusBlockEntity(
                appeng.core.definitions.AEBlockEntities.CABLE_BUS.get(),net.minecraft.core.BlockPos.ZERO,
                appeng.core.definitions.AEBlocks.CABLE_BUS.block().defaultBlockState()) {
            @Override public boolean hasRedstone() { return false; }
        };
        part.setPartHostInfo(net.minecraft.core.Direction.NORTH,host,host);
        return part;
    }
    @Test void identityAndQuantitySurviveNbtAndIndependentPartsHaveDifferentIdentities() {
        var original = create();
        var independent = create();
        assertNotEquals(original.identity(),independent.identity());
        original.getConfig().setStack(2,new GenericStack(AEItemKey.of(Items.COBBLESTONE),32));
        var tag = new CompoundTag();
        original.writeToNBT(tag,net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var loaded = create();
        loaded.readFromNBT(tag,net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        assertEquals(original.identity(),loaded.identity());
        assertEquals(32,loaded.getConfig().getAmount(2));
        assertEquals(AEItemKey.of(Items.COBBLESTONE),loaded.getConfig().getKey(2));
    }
    @Test void fuzzyModePersistsWithPanelConfiguration() {
        var original = create();
        original.getConfigManager().putSetting(appeng.api.config.Settings.FUZZY_MODE,appeng.api.config.FuzzyMode.PERCENT_50);
        var tag = new CompoundTag();
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        original.writeToNBT(tag,registries);
        var loaded = create();loaded.readFromNBT(tag,registries);
        assertEquals(appeng.api.config.FuzzyMode.PERCENT_50,loaded.getConfigManager().getSetting(appeng.api.config.Settings.FUZZY_MODE));
    }
    @Test void dismantleCarriesIdentityButMemoryCardDoesNotCopyIt() {
        var original = create();
        var dismantle = original.exportSettings(appeng.util.SettingsFrom.DISMANTLE_ITEM);
        assertEquals(original.identity(),dismantle.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getUUID("identity"));
        var memory = original.exportSettings(appeng.util.SettingsFrom.MEMORY_CARD);
        assertNull(memory.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA));
    }
    @Test void mouseButtonsKeepIndependentPressModesAndDurationsAcrossNbt() {
        var part = create();
        assertEquals(SimulationSettings.Click.RIGHT,part.click());
        assertEquals(SimulationSettings.Press.TAP,part.press());
        part.cycle("press");part.cycle("hold");
        assertEquals(SimulationSettings.Press.HOLD,part.press());assertEquals(SimulationSettings.Hold.LONG,part.hold());
        part.cycle("click");part.cycle("press");
        assertEquals(SimulationSettings.Press.TAP,part.press());assertEquals(SimulationSettings.Hold.CONTINUOUS,part.hold());
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var tag = new CompoundTag();part.writeToNBT(tag,registries);
        var restored = create();restored.readFromNBT(tag,registries);
        assertEquals(SimulationSettings.Press.TAP,restored.press());assertEquals(SimulationSettings.Hold.CONTINUOUS,restored.hold());
        restored.cycle("click");
        assertEquals(SimulationSettings.Press.HOLD,restored.press());assertEquals(SimulationSettings.Hold.LONG,restored.hold());
    }
    @Test void clickFacePersistsAndDismantleCarriesIt() {
        var part = create();
        assertEquals(SimulationSettings.ClickFace.AUTO,part.clickFace());
        for (int i = 0; i < 5; i++) part.cycle("click_face");
        assertEquals(SimulationSettings.ClickFace.UP,part.clickFace());
        var tag = new CompoundTag();var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        part.writeToNBT(tag,registries);var restored = create();restored.readFromNBT(tag,registries);
        assertEquals(SimulationSettings.ClickFace.UP,restored.clickFace());
        var itemData = part.exportSettings(appeng.util.SettingsFrom.DISMANTLE_ITEM);
        assertEquals("UP",itemData.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getString("clickFace"));
        tag.putString("clickFace","INVALID");
        assertThrows(IllegalArgumentException.class,() -> create().readFromNBT(tag,registries));
    }
    @Test void optionsPersistAndInvalidSavedEnumFailsClearly() {
        var tag = new CompoundTag();
        tag.putString("click","LEFT");tag.putString("target","ENTITY");
        tag.putString("selection","SEQUENTIAL");tag.putString("cooldown","IGNORE");
        tag.putString("leftHold","LONG");tag.putBoolean("sneaking",true);
        var part = create();
        part.readFromNBT(tag,net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        assertEquals(SimulationSettings.Click.LEFT,part.click());
        assertEquals(SimulationSettings.Target.ENTITY,part.target());
        assertEquals(SimulationSettings.Selection.SEQUENTIAL,part.selection());
        assertTrue(part.sneaking());
        tag.putString("click","INVALID");
        assertThrows(IllegalArgumentException.class,() -> create().readFromNBT(tag,net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }
}
