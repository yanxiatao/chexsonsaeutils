package git.chexson.chexsonsaeutils.menu.custompatternupgrade;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;

/** 瞬态扩容输入的关闭回收；清空容器后的交付采用原版 clearContainer。 */
public final class UpgradeScenarios {
    private UpgradeScenarios() {}

    public static void verify(Path temporaryRoot) {
        var host = new CustomPatternUpgradeHost(null);
        var callbackCount = new AtomicInteger();
        host.setInventoryChangedHandler((inventory, slot) -> callbackCount.incrementAndGet());
        var remaining = new ItemStack(Items.PAPER, 64);
        remaining.set(DataComponents.CUSTOM_NAME, Component.literal("unconsumed upgrade input"));
        host.getInventory().setItemDirect(0, remaining);
        int beforeClose = callbackCount.get();

        // removed() 传给 clearContainer 的正是这个容器；关闭、死亡和断线都从中取一次。
        var closingInventory = host.detachInventory();
        var returned = closingInventory.removeItemNoUpdate(0);
        assertEquals(64, returned.getCount());
        assertTrue(ItemStack.isSameItemSameComponents(remaining, returned));
        assertTrue(host.getInventory().getStackInSlot(0).isEmpty());
        assertTrue(closingInventory.removeItemNoUpdate(0).isEmpty(), "重复关闭不得再次返还输入");
        assertEquals(beforeClose, callbackCount.get(), "关闭过程中不能再次触发自动扩容");
    }
}
