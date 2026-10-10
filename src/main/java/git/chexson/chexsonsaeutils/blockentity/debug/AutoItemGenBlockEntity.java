package git.chexson.chexsonsaeutils.blockentity.debug;

import java.util.ArrayList;
import java.util.List;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.capabilities.Capabilities;

import appeng.blockentity.AEBaseBlockEntity;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

public class AutoItemGenBlockEntity extends AEBaseBlockEntity {

    private static final Logger LOG = LoggerFactory.getLogger(AutoItemGenBlockEntity.class);

    private static final List<Item> ALL_ITEMS = new ArrayList<>();
    private static int cursor;

    private Item filter;

    public AutoItemGenBlockEntity(BlockPos pos, BlockState blockState) {
        super(ChexsonsaeutilsContent.AUTO_ITEM_GEN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AutoItemGenBlockEntity be) {
        be.tick();
    }

    private void tick() {
        if (level == null || level.isClientSide) return;

        for (var dir : Direction.values()) {
            var targetPos = worldPosition.relative(dir);
            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, dir.getOpposite());
            if (handler == null) continue;

            for (int slot = 0; slot < handler.getSlots(); slot++) {
                var stack = handler.getStackInSlot(slot);
                if (stack.getCount() >= stack.getMaxStackSize()) continue;

                var item = nextItem();
                if (item == Items.AIR) continue;

                var fill = new ItemStack(item, item.getDefaultInstance().getMaxStackSize());
                handler.insertItem(slot, fill, false);
            }
        }
    }

    public void setFilter(Item item) {
        this.filter = item;
    }

    private Item nextItem() {
        if (filter != null) return filter;

        if (ALL_ITEMS.isEmpty()) {
            for (var item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR) ALL_ITEMS.add(item);
            }
        }
        if (ALL_ITEMS.isEmpty()) return Items.AIR;
        var item = ALL_ITEMS.get(cursor);
        cursor = (cursor + 1) % ALL_ITEMS.size();
        return item;
    }
}
