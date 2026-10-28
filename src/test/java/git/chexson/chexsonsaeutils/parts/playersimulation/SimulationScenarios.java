package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import java.util.ArrayList;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import static git.chexson.chexsonsaeutils.parts.playersimulation.SimulationSettings.Selection.*;
import static org.junit.jupiter.api.Assertions.*;

/** 玩家模拟的行为场景；由类别测试统一调用。 */
public final class SimulationScenarios {
    private SimulationScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.parts.playersimulation",
                () -> PlayerSimulationPersistenceCases.verify(temporaryRoot),
                () -> SimulationBufferCases.verify(temporaryRoot),
                () -> SimulationClickFacesCases.verify(temporaryRoot),
                () -> SimulationCooldownsCases.verify(temporaryRoot),
                () -> SimulationInteractionCases.verify(temporaryRoot),
                () -> SimulationScheduleCases.verify(temporaryRoot)
        );
    }

    /** PlayerSimulationPersistenceCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlayerSimulationPersistenceCases {
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
        void identityAndQuantitySurviveNbtAndIndependentPartsHaveDifferentIdentities() {
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
        void fuzzyModePersistsWithPanelConfiguration() {
            var original = create();
            original.getConfigManager().putSetting(appeng.api.config.Settings.FUZZY_MODE,appeng.api.config.FuzzyMode.PERCENT_50);
            var tag = new CompoundTag();
            var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            original.writeToNBT(tag,registries);
            var loaded = create();loaded.readFromNBT(tag,registries);
            assertEquals(appeng.api.config.FuzzyMode.PERCENT_50,loaded.getConfigManager().getSetting(appeng.api.config.Settings.FUZZY_MODE));
        }
        void dismantleCarriesIdentityButMemoryCardDoesNotCopyIt() {
            var original = create();
            var dismantle = original.exportSettings(appeng.util.SettingsFrom.DISMANTLE_ITEM);
            assertEquals(original.identity(),dismantle.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getUUID("identity"));
            var memory = original.exportSettings(appeng.util.SettingsFrom.MEMORY_CARD);
            assertNull(memory.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA));
        }
        void mouseButtonsKeepIndependentPressModesAndDurationsAcrossNbt() {
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
        void clickFacePersistsAndDismantleCarriesIt() {
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
        void optionsPersistAndInvalidSavedEnumFailsClearly() {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlayerSimulationPersistenceCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlayerSimulationPersistenceCases.identityAndQuantitySurviveNbtAndIndependentPartsHaveDifferentIdentities", () -> new PlayerSimulationPersistenceCases().identityAndQuantitySurviveNbtAndIndependentPartsHaveDifferentIdentities()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlayerSimulationPersistenceCases.fuzzyModePersistsWithPanelConfiguration", () -> new PlayerSimulationPersistenceCases().fuzzyModePersistsWithPanelConfiguration()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlayerSimulationPersistenceCases.dismantleCarriesIdentityButMemoryCardDoesNotCopyIt", () -> new PlayerSimulationPersistenceCases().dismantleCarriesIdentityButMemoryCardDoesNotCopyIt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlayerSimulationPersistenceCases.mouseButtonsKeepIndependentPressModesAndDurationsAcrossNbt", () -> new PlayerSimulationPersistenceCases().mouseButtonsKeepIndependentPressModesAndDurationsAcrossNbt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlayerSimulationPersistenceCases.clickFacePersistsAndDismantleCarriesIt", () -> new PlayerSimulationPersistenceCases().clickFacePersistsAndDismantleCarriesIt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlayerSimulationPersistenceCases.optionsPersistAndInvalidSavedEnumFailsClearly", () -> new PlayerSimulationPersistenceCases().optionsPersistAndInvalidSavedEnumFailsClearly())
            );
        }
    }

    /** SimulationBufferCases 的独立场景夹具，按类别统一执行。 */
    public static final class SimulationBufferCases {
        void takeTransfersOwnershipAndDurabilityKeepsOrigin() {
            var buffer = new SimulationBuffer();
            var original = new ItemStack(Items.IRON_PICKAXE);
            var key = AEItemKey.of(original);
            buffer.set(0,key,original);
            var held = buffer.take(0);
            assertTrue(buffer.get(0).isEmpty());
            assertSame(original,held);
            held.setDamageValue(12);
            buffer.set(0,key,held);
            assertTrue(buffer.belongs(0,key));
            assertFalse(key.matches(buffer.get(0)));
            var returns = new ArrayList<ItemStack>();
            buffer.reconcile(0,key,1,returns::add);
            assertTrue(returns.isEmpty());
            assertEquals(12,buffer.get(0).getDamageValue());
        }
        void changeFilterAndReduceQuantityReturnExactlyExcess() {
            var buffer = new SimulationBuffer();
            var key = AEItemKey.of(Items.COBBLESTONE);
            buffer.set(1,key,new ItemStack(Items.COBBLESTONE,32));
            var returned = new ArrayList<ItemStack>();
            buffer.reconcile(1,key,10,returned::add);
            assertEquals(10,buffer.get(1).getCount());
            assertEquals(22,returned.getFirst().getCount());
            buffer.reconcile(1,AEItemKey.of(Items.DIRT),10,returned::add);
            assertTrue(buffer.get(1).isEmpty());
            assertEquals(10,returned.getLast().getCount());
        }
        void saveReloadRetainsModifiedToolAndAllStacks() {
            var buffer = new SimulationBuffer();
            var key = AEItemKey.of(Items.IRON_PICKAXE);
            var tool = new ItemStack(Items.IRON_PICKAXE);
            tool.setDamageValue(27);
            buffer.set(0,key,tool);
            buffer.set(62,AEItemKey.of(Items.DIRT),new ItemStack(Items.DIRT,64));
            var tag = new CompoundTag();
            buffer.write(tag,net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            var loaded = new SimulationBuffer();
            loaded.read(tag,net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            assertTrue(loaded.belongs(0,key));
            assertEquals(27,loaded.get(0).getDamageValue());
            assertEquals(64,loaded.get(62).getCount());
            var returned = new ArrayList<ItemStack>();
            loaded.drain(returned::add);
            assertEquals(2,returned.size());
            loaded.drain(returned::add);
            assertEquals(2,returned.size());
        }
        void removedCapacityReturnsDisabledSlotAndClearDoesNotDuplicateDrops() {
            var buffer = new SimulationBuffer();
            var key = AEItemKey.of(Items.DIRT);
            buffer.set(62,key,new ItemStack(Items.DIRT,12));
            var returned = new ArrayList<ItemStack>();
            buffer.reconcile(62,key,0,returned::add);
            assertEquals(12,returned.getFirst().getCount());
            buffer.set(0,key,new ItemStack(Items.DIRT,7));
            var drops = new ArrayList<ItemStack>();
            buffer.addDrops(drops);
            assertEquals(7,drops.getFirst().getCount());
            buffer.clear();
            buffer.addDrops(drops);
            assertEquals(1,drops.size());
        }

        void craftedDeliveryRespectsSimulationTargetAmountAndWornTools() {
            var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.COBBLESTONE);
            assertEquals(12,buffer.accept(0,key,12,64,appeng.api.config.Actionable.SIMULATE));
            assertTrue(buffer.get(0).isEmpty());
            assertEquals(12,buffer.accept(0,key,12,64,appeng.api.config.Actionable.MODULATE));
            assertEquals(12,buffer.get(0).getCount());
            assertEquals(0,buffer.accept(0,key,12,64,appeng.api.config.Actionable.MODULATE));
            var tool = new ItemStack(Items.IRON_PICKAXE);var toolKey = AEItemKey.of(tool);tool.setDamageValue(20);
            buffer.set(1,toolKey,tool);
            assertEquals(0,buffer.accept(1,toolKey,1,1,appeng.api.config.Actionable.MODULATE));
            assertEquals(20,buffer.get(1).getDamageValue());
        }
        void fuzzyDeliveryKeepsActualComponentsAndDoesNotMixVariants() {
            var buffer = new SimulationBuffer();var marker = AEItemKey.of(Items.COBBLESTONE);
            var variant = new ItemStack(Items.COBBLESTONE);
            variant.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("真实变体"));
            var actual = AEItemKey.of(variant);
            assertEquals(9,buffer.accept(0,marker,actual,9,64,appeng.api.config.Actionable.SIMULATE));
            assertTrue(buffer.get(0).isEmpty());
            assertEquals(9,buffer.accept(0,marker,actual,9,64,appeng.api.config.Actionable.MODULATE));
            assertTrue(buffer.belongs(0,marker));assertTrue(actual.matches(buffer.get(0)));
            assertEquals(0,buffer.accept(0,marker,marker,12,64,appeng.api.config.Actionable.MODULATE));
            assertEquals(3,buffer.accept(0,marker,actual,12,64,appeng.api.config.Actionable.MODULATE));
            assertEquals(12,buffer.get(0).getCount());assertTrue(actual.matches(buffer.get(0)));
        }
        void rereadingNbtClearsSlotsAbsentFromSave() {
            var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.DIRT);
            buffer.set(0,key,new ItemStack(Items.DIRT,8));
            buffer.read(new CompoundTag(),net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            assertTrue(buffer.get(0).isEmpty());assertFalse(buffer.belongs(0,key));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases.takeTransfersOwnershipAndDurabilityKeepsOrigin", () -> new SimulationBufferCases().takeTransfersOwnershipAndDurabilityKeepsOrigin()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases.changeFilterAndReduceQuantityReturnExactlyExcess", () -> new SimulationBufferCases().changeFilterAndReduceQuantityReturnExactlyExcess()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases.saveReloadRetainsModifiedToolAndAllStacks", () -> new SimulationBufferCases().saveReloadRetainsModifiedToolAndAllStacks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases.removedCapacityReturnsDisabledSlotAndClearDoesNotDuplicateDrops", () -> new SimulationBufferCases().removedCapacityReturnsDisabledSlotAndClearDoesNotDuplicateDrops()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases.craftedDeliveryRespectsSimulationTargetAmountAndWornTools", () -> new SimulationBufferCases().craftedDeliveryRespectsSimulationTargetAmountAndWornTools()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases.fuzzyDeliveryKeepsActualComponentsAndDoesNotMixVariants", () -> new SimulationBufferCases().fuzzyDeliveryKeepsActualComponentsAndDoesNotMixVariants()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationBufferCases.rereadingNbtClearsSlotsAbsentFromSave", () -> new SimulationBufferCases().rereadingNbtClearsSlotsAbsentFromSave())
            );
        }
    }

    /** SimulationClickFacesCases 的独立场景夹具，按类别统一执行。 */
    public static final class SimulationClickFacesCases {
        private final BlockPos target = new BlockPos(12,34,56);
        private final BlockHitResult detected = new BlockHitResult(new Vec3(12.5,34.5,56),Direction.NORTH,target,false);
        void automaticKeepsOriginalHitAndAllSixFacesKeepTarget() {
            assertSame(detected,SimulationClickFaces.hit(detected,Shapes.block(),SimulationSettings.ClickFace.AUTO));
            for (var option : SimulationSettings.ClickFace.values()) {
                if (option == SimulationSettings.ClickFace.AUTO) continue;
                var direction = option.resolve(Direction.NORTH);
                var hit = SimulationClickFaces.hit(detected,Shapes.block(),option);
                assertEquals(direction,hit.getDirection());assertEquals(target,hit.getBlockPos());
                assertEquals(Vec3.atCenterOf(target).add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.5)),hit.getLocation());
            }
        }
        void slabAndSeparatedVoxelShapesUseActualExteriorFace() {
            var slab = Shapes.box(0,0,0,1,0.5,1);
            assertEquals(new Vec3(12.5,34.5,56.5),SimulationClickFaces.hit(detected,slab,SimulationSettings.ClickFace.UP).getLocation());
            assertEquals(new Vec3(12.5,34,56.5),SimulationClickFaces.hit(detected,slab,SimulationSettings.ClickFace.DOWN).getLocation());
            var pieces = Shapes.or(Shapes.box(0,0,0,0.25,0.5,0.25),Shapes.box(0.75,0,0.75,1,1,1));
            assertEquals(new Vec3(12.875,35,56.875),SimulationClickFaces.hit(detected,pieces,SimulationSettings.ClickFace.UP).getLocation());
            assertEquals(new Vec3(12,34.25,56.125),SimulationClickFaces.hit(detected,pieces,SimulationSettings.ClickFace.WEST).getLocation());
        }
        void fixedFaceWithEmptyShapeFailsExplicitly() {
            assertThrows(IllegalArgumentException.class,() -> SimulationClickFaces.hit(detected,Shapes.empty(),SimulationSettings.ClickFace.UP));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SimulationClickFacesCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationClickFacesCases.automaticKeepsOriginalHitAndAllSixFacesKeepTarget", () -> new SimulationClickFacesCases().automaticKeepsOriginalHitAndAllSixFacesKeepTarget()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationClickFacesCases.slabAndSeparatedVoxelShapesUseActualExteriorFace", () -> new SimulationClickFacesCases().slabAndSeparatedVoxelShapesUseActualExteriorFace()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationClickFacesCases.fixedFaceWithEmptyShapeFailsExplicitly", () -> new SimulationClickFacesCases().fixedFaceWithEmptyShapeFailsExplicitly())
            );
        }
    }

    /** SimulationCooldownsCases 的独立场景夹具，按类别统一执行。 */
    public static final class SimulationCooldownsCases {
        void cooldownSurvivesReloadAndExpiresAtCorrectTick() {
            var cooldowns = new SimulationCooldowns();
            cooldowns.addCooldown(Items.ENDER_PEARL,20);
            for (int i = 0; i < 7; i++) cooldowns.tick();
            var loaded = new SimulationCooldowns();
            loaded.load(cooldowns.save());
            for (int i = 0; i < 12; i++) { assertTrue(loaded.isOnCooldown(Items.ENDER_PEARL)); loaded.tick(); }
            assertTrue(loaded.isOnCooldown(Items.ENDER_PEARL));
            loaded.tick();
            assertFalse(loaded.isOnCooldown(Items.ENDER_PEARL));
            assertTrue(loaded.save().isEmpty());
        }
        void ignoringSelectedItemDoesNotClearOtherCooldowns() {
            var cooldowns = new SimulationCooldowns();
            cooldowns.addCooldown(Items.ENDER_PEARL,20);
            cooldowns.addCooldown(Items.SNOWBALL,8);
            cooldowns.removeCooldown(Items.ENDER_PEARL);
            assertFalse(cooldowns.isOnCooldown(Items.ENDER_PEARL));
            assertTrue(cooldowns.isOnCooldown(Items.SNOWBALL));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SimulationCooldownsCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationCooldownsCases.cooldownSurvivesReloadAndExpiresAtCorrectTick", () -> new SimulationCooldownsCases().cooldownSurvivesReloadAndExpiresAtCorrectTick()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationCooldownsCases.ignoringSelectedItemDoesNotClearOtherCooldowns", () -> new SimulationCooldownsCases().ignoringSelectedItemDoesNotClearOtherCooldowns())
            );
        }
    }

    /** SimulationInteractionCases 的独立场景夹具，按类别统一执行。 */
    public static final class SimulationInteractionCases {
        private static final class Actor implements SimulationActor {
            final Inventory inventory = new Inventory(null);
            boolean suspended;
            @Override public Inventory inventory() { return inventory; }
            @Override public void hold(ItemStack stack) { assertSame(stack,inventory.getItem(0)); }
            @Override public void suspendHand() { suspended = true; }
            @Override public void position(BlockPos host,Direction face,SimulationSettings.ClickFace clickFace,boolean sneaking) { throw new AssertionError("本测试不操作世界"); }
            @Override public void tickClock() { throw new AssertionError("本测试不推进世界"); }
            @Override public boolean busy() { return false; }
            @Override public String act(SimulationSettings.Click click,SimulationSettings.Target target,SimulationSettings.Cooldown cooldown,SimulationSettings.Press press,int holdTicks,boolean repeatAllowed) { throw new AssertionError("通过行为回调执行"); }
            @Override public void cancel() { throw new AssertionError("本测试不取消动作"); }
            @Override public CompoundTag save() { throw new AssertionError("本测试不保存玩家"); }
        }
        void consumesOnlyActualUsedAmountAndReturnsAuxiliaryInventory() {
            var actor = new Actor();var buffer = new SimulationBuffer();
            var key = AEItemKey.of(Items.COBBLESTONE);var arrow = AEItemKey.of(Items.ARROW);
            buffer.set(0,key,new ItemStack(Items.COBBLESTONE,8));
            buffer.set(1,arrow,new ItemStack(Items.ARROW,4));
            var returned = new ArrayList<ItemStack>();
            String state = SimulationInteraction.execute(actor,buffer,0,2,i -> i == 0 ? key : arrow,() -> {
                assertTrue(buffer.get(0).isEmpty());assertTrue(buffer.get(1).isEmpty());
                actor.inventory.getItem(0).shrink(1);actor.inventory.getItem(1).shrink(1);return "success";
            },returned::add);
            assertEquals("success",state);assertEquals(7,buffer.get(0).getCount());assertEquals(3,buffer.get(1).getCount());
            assertTrue(returned.isEmpty());assertTrue(actor.inventory.isEmpty());assertTrue(actor.suspended);
        }
        void exceptionAfterConsumptionStillSettlesWithoutDuplicatingItems() {
            var actor = new Actor();var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.SNOWBALL);
            buffer.set(0,key,new ItemStack(Items.SNOWBALL,6));
            assertThrows(IllegalStateException.class,() -> SimulationInteraction.execute(actor,buffer,0,1,i -> key,() -> {
                actor.inventory.getItem(0).shrink(2);throw new IllegalStateException("物品回调失败");
            },item -> fail("不应生成副产物")));
            assertEquals(4,buffer.get(0).getCount());assertTrue(actor.inventory.isEmpty());assertTrue(actor.suspended);
        }
        void containerAndExtraItemsReturnToNetworkWhileToolDamageStaysBuffered() {
            var actor = new Actor();var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.WATER_BUCKET);
            buffer.set(0,key,new ItemStack(Items.WATER_BUCKET));var returned = new ArrayList<ItemStack>();
            SimulationInteraction.execute(actor,buffer,0,1,i -> key,() -> {
                actor.inventory.setItem(0,new ItemStack(Items.BUCKET));
                actor.inventory.setItem(40,new ItemStack(Items.DIAMOND,2));return "success";
            },returned::add);
            assertTrue(buffer.get(0).isEmpty());assertEquals(2,returned.size());
            assertTrue(returned.getFirst().is(Items.BUCKET));assertEquals(2,returned.getLast().getCount());
            var toolKey = AEItemKey.of(Items.IRON_PICKAXE);buffer.set(0,toolKey,new ItemStack(Items.IRON_PICKAXE));
            SimulationInteraction.execute(actor,buffer,0,1,i -> toolKey,() -> {
                actor.inventory.getItem(0).setDamageValue(30);return "mining";
            },returned::add);
            assertEquals(30,buffer.get(0).getDamageValue());assertTrue(buffer.belongs(0,toolKey));
        }
        void noSelectedItemUsesEmptyMainHandEvenWithAuxiliaryItems() {
            var actor = new Actor();var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.ARROW);
            buffer.set(0,key,new ItemStack(Items.ARROW,8));
            SimulationInteraction.execute(actor,buffer,-1,1,i -> key,() -> {
                assertTrue(actor.inventory.getItem(0).isEmpty());assertEquals(8,actor.inventory.getItem(1).getCount());return "empty";
            },item -> fail("不应产生退回物品"));
            assertEquals(8,buffer.get(0).getCount());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SimulationInteractionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationInteractionCases.consumesOnlyActualUsedAmountAndReturnsAuxiliaryInventory", () -> new SimulationInteractionCases().consumesOnlyActualUsedAmountAndReturnsAuxiliaryInventory()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationInteractionCases.exceptionAfterConsumptionStillSettlesWithoutDuplicatingItems", () -> new SimulationInteractionCases().exceptionAfterConsumptionStillSettlesWithoutDuplicatingItems()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationInteractionCases.containerAndExtraItemsReturnToNetworkWhileToolDamageStaysBuffered", () -> new SimulationInteractionCases().containerAndExtraItemsReturnToNetworkWhileToolDamageStaysBuffered()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationInteractionCases.noSelectedItemUsesEmptyMainHandEvenWithAuxiliaryItems", () -> new SimulationInteractionCases().noSelectedItemUsesEmptyMainHandEvenWithAuxiliaryItems())
            );
        }
    }

    /** SimulationScheduleCases 的独立场景夹具，按类别统一执行。 */
    public static final class SimulationScheduleCases {
        void emptyStockMeansEmptyHand() {
            for (var mode : SimulationSettings.Selection.values())
                assertEquals(-1, SimulationSchedule.select(mode, 63, -1, i -> false, new Random(1)));
        }
        void sequentialKeepsSlotUntilExhaustedThenWraps() {
            assertEquals(2, SimulationSchedule.select(SEQUENTIAL, 5, 2, i -> i == 2 || i == 4, new Random(1)));
            assertEquals(4, SimulationSchedule.select(SEQUENTIAL, 5, 2, i -> i == 4, new Random(1)));
            assertEquals(0, SimulationSchedule.select(SEQUENTIAL, 5, 4, i -> i == 0, new Random(1)));
        }
        void roundRobinSkipsUnavailableAndWraps() {
            assertEquals(4, SimulationSchedule.select(ROUND_ROBIN, 5, 2, i -> i == 2 || i == 4, new Random(1)));
            assertEquals(2, SimulationSchedule.select(ROUND_ROBIN, 5, 4, i -> i == 2 || i == 4, new Random(1)));
            assertEquals(0, SimulationSchedule.select(ROUND_ROBIN, 5, -1, i -> true, new Random(1)));
        }
        void randomOnlyChoosesStockedSlotsAndVisitsAll() {
            var random = new Random(42);
            var seen = new java.util.HashSet<Integer>();
            for (int i = 0; i < 1000; i++) {
                int slot = SimulationSchedule.select(RANDOM, 63, 10, n -> n == 2 || n == 12 || n == 62, random);
                assertTrue(slot == 2 || slot == 12 || slot == 62);
                seen.add(slot);
            }
            assertEquals(java.util.Set.of(2, 12, 62), seen);
        }
        void accelerationHasHardOneTickFloor() {
            assertArrayEquals(new int[]{20,10,5,2,1}, java.util.stream.IntStream.rangeClosed(0,4).map(SimulationSchedule::interval).toArray());
            assertThrows(IllegalArgumentException.class, () -> SimulationSchedule.interval(5));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("SimulationScheduleCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationScheduleCases.emptyStockMeansEmptyHand", () -> new SimulationScheduleCases().emptyStockMeansEmptyHand()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationScheduleCases.sequentialKeepsSlotUntilExhaustedThenWraps", () -> new SimulationScheduleCases().sequentialKeepsSlotUntilExhaustedThenWraps()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationScheduleCases.roundRobinSkipsUnavailableAndWraps", () -> new SimulationScheduleCases().roundRobinSkipsUnavailableAndWraps()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationScheduleCases.randomOnlyChoosesStockedSlotsAndVisitsAll", () -> new SimulationScheduleCases().randomOnlyChoosesStockedSlotsAndVisitsAll()),
                () -> org.junit.jupiter.api.Assertions.assertAll("SimulationScheduleCases.accelerationHasHardOneTickFloor", () -> new SimulationScheduleCases().accelerationHasHardOneTickFloor())
            );
        }
    }
}
