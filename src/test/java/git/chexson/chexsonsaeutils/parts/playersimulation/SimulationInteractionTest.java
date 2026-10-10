package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.stacks.AEItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SimulationInteractionTest {
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
    @Test void consumesOnlyActualUsedAmountAndReturnsAuxiliaryInventory() {
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
    @Test void exceptionAfterConsumptionStillSettlesWithoutDuplicatingItems() {
        var actor = new Actor();var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.SNOWBALL);
        buffer.set(0,key,new ItemStack(Items.SNOWBALL,6));
        assertThrows(IllegalStateException.class,() -> SimulationInteraction.execute(actor,buffer,0,1,i -> key,() -> {
            actor.inventory.getItem(0).shrink(2);throw new IllegalStateException("物品回调失败");
        },item -> fail("不应生成副产物")));
        assertEquals(4,buffer.get(0).getCount());assertTrue(actor.inventory.isEmpty());assertTrue(actor.suspended);
    }
    @Test void containerAndExtraItemsReturnToNetworkWhileToolDamageStaysBuffered() {
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
    @Test void noSelectedItemUsesEmptyMainHandEvenWithAuxiliaryItems() {
        var actor = new Actor();var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.ARROW);
        buffer.set(0,key,new ItemStack(Items.ARROW,8));
        SimulationInteraction.execute(actor,buffer,-1,1,i -> key,() -> {
            assertTrue(actor.inventory.getItem(0).isEmpty());assertEquals(8,actor.inventory.getItem(1).getCount());return "empty";
        },item -> fail("不应产生退回物品"));
        assertEquals(8,buffer.get(0).getCount());
    }
}
