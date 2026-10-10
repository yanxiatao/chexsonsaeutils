package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.stacks.AEItemKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SimulationBufferTest {
    @Test void takeTransfersOwnershipAndDurabilityKeepsOrigin() {
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
    @Test void changeFilterAndReduceQuantityReturnExactlyExcess() {
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
    @Test void saveReloadRetainsModifiedToolAndAllStacks() {
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
    @Test void removedCapacityReturnsDisabledSlotAndClearDoesNotDuplicateDrops() {
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

    @Test void craftedDeliveryRespectsSimulationTargetAmountAndWornTools() {
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
    @Test void fuzzyDeliveryKeepsActualComponentsAndDoesNotMixVariants() {
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
    @Test void rereadingNbtClearsSlotsAbsentFromSave() {
        var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.DIRT);
        buffer.set(0,key,new ItemStack(Items.DIRT,8));
        buffer.read(new CompoundTag(),net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        assertTrue(buffer.get(0).isEmpty());assertFalse(buffer.belongs(0,key));
    }
}
