package git.chexson.chexsonsaeutils.helpers.mattermassprovider;

import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternDetails;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternItem;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.ProviderTestSupport;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.ProviderTestSupport.BoundedStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import static git.chexson.chexsonsaeutils.helpers.custompatternprovider.ProviderTestSupport.REGISTRIES;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PASS_THROUGH owns its unpaid return balance until the network really accepts it. */
public final class ProvidersScenarios {
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    private ProvidersScenarios() {}

    public static void verify(Path temporaryRoot) {
        assertAll("matter mass pass-through conservation",
                ProvidersScenarios::sharedTypeCapacityRetainsTheSecondInputAcrossReload,
                ProvidersScenarios::partialAcceptancePersistsOnlyTheRemainingAmount,
                ProvidersScenarios::fullRejectionDoesNotCompleteOrDequeue,
                ProvidersScenarios::repeatedInputEntriesShareTheSameAmountCapacity,
                ProvidersScenarios::removalDropsOnlyTheUndeliveredBalance,
                ProvidersScenarios::ampleStorageCompletesExactlyOnce);
    }

    private static void sharedTypeCapacityRetainsTheSecondInputAcrossReload() {
        var storage = new BoundedStorage(1, 64);
        var host = new MassHost(storage);
        var output = push(host, List.of(new GenericStack(DIAMOND, 1), new GenericStack(IRON, 1)));
        int savesBefore = host.saves;
        host.tick();

        assertEquals(1, storage.stored.get(DIAMOND));
        assertEquals(0, storage.stored.get(IRON));
        assertTrue(host.logic.isBusy());
        assertEquals(0, host.grid.crafting.completed.get(output));
        assertTrue(host.saves > savesBefore, "partial progress must mark the remaining balance for persistence");
        var saved = host.save();
        assertEquals(0, pendingAmount(saved, DIAMOND));
        assertEquals(1, pendingAmount(saved, IRON));

        var loaded = new MassHost(storage);
        loaded.logic.readFromNBT(saved, REGISTRIES);
        loaded.tick();
        assertTrue(loaded.logic.isBusy());
        assertEquals(1, storage.stored.get(DIAMOND), "an accepted input must not be replayed after loading");
        assertEquals(0, loaded.grid.crafting.completed.get(output));

        storage.typeCapacity = 2;
        loaded.tick();
        assertFalse(loaded.logic.isBusy());
        assertEquals(1, storage.stored.get(DIAMOND));
        assertEquals(1, storage.stored.get(IRON));
        assertEquals(1, loaded.grid.crafting.completed.get(output));
        loaded.tick();
        assertEquals(1, loaded.grid.crafting.completed.get(output));
    }

    private static void partialAcceptancePersistsOnlyTheRemainingAmount() {
        var storage = new BoundedStorage(1, 4);
        var host = new MassHost(storage);
        var output = push(host, List.of(new GenericStack(DIAMOND, 10)));
        host.tick();

        assertEquals(4, storage.stored.get(DIAMOND));
        assertEquals(6, pendingAmount(host.save(), DIAMOND));
        assertTrue(host.logic.isBusy());
        assertEquals(0, host.grid.crafting.completed.get(output));

        var loaded = new MassHost(storage);
        loaded.logic.readFromNBT(host.save(), REGISTRIES);
        storage.amountCapacity = 10;
        loaded.tick();
        assertEquals(10, storage.stored.get(DIAMOND));
        assertFalse(loaded.logic.isBusy());
        assertEquals(1, loaded.grid.crafting.completed.get(output));
        assertTrue(loaded.save().getList("matterMassPending", Tag.TAG_COMPOUND).isEmpty());
    }

    private static void fullRejectionDoesNotCompleteOrDequeue() {
        var storage = new BoundedStorage(0, 0);
        var host = new MassHost(storage);
        var output = push(host, List.of(new GenericStack(DIAMOND, 4), new GenericStack(IRON, 2)));
        for (int tick = 0; tick < 3; tick++) host.tick();

        assertTrue(host.logic.isBusy());
        assertTrue(storage.stored.isEmpty());
        assertEquals(4, pendingAmount(host.save(), DIAMOND));
        assertEquals(2, pendingAmount(host.save(), IRON));
        assertEquals(0, host.grid.crafting.completed.get(output));
    }

    private static void repeatedInputEntriesShareTheSameAmountCapacity() {
        var storage = new BoundedStorage(1, 5);
        var host = new MassHost(storage);
        var output = push(host, List.of(new GenericStack(DIAMOND, 4), new GenericStack(DIAMOND, 3)));
        host.tick();

        assertEquals(5, storage.stored.get(DIAMOND));
        assertEquals(2, pendingAmount(host.save(), DIAMOND));
        assertEquals(0, host.grid.crafting.completed.get(output));
        storage.amountCapacity = 7;
        host.tick();
        assertEquals(7, storage.stored.get(DIAMOND));
        assertFalse(host.logic.isBusy());
        assertEquals(1, host.grid.crafting.completed.get(output));
    }

    private static void removalDropsOnlyTheUndeliveredBalance() {
        var storage = new BoundedStorage(1, 64);
        var host = new MassHost(storage);
        push(host, List.of(new GenericStack(DIAMOND, 1), new GenericStack(IRON, 2)));
        host.tick();
        var loaded = new MassHost(storage);
        loaded.logic.readFromNBT(host.save(), REGISTRIES);
        var drops = new ArrayList<ItemStack>();
        loaded.logic.addDrops(drops);

        assertEquals(1, storage.stored.get(DIAMOND));
        assertEquals(0, drops.stream().filter(DIAMOND::matches).mapToLong(ItemStack::getCount).sum());
        assertEquals(2, drops.stream().filter(IRON::matches).mapToLong(ItemStack::getCount).sum());
        assertFalse(loaded.logic.isBusy());
    }

    private static void ampleStorageCompletesExactlyOnce() {
        var storage = new BoundedStorage(2, 64);
        var host = new MassHost(storage);
        var output = push(host, List.of(new GenericStack(DIAMOND, 2), new GenericStack(IRON, 3)));
        host.tick();
        host.tick();

        assertEquals(2, storage.stored.get(DIAMOND));
        assertEquals(3, storage.stored.get(IRON));
        assertFalse(host.logic.isBusy());
        assertEquals(1, host.grid.crafting.completed.get(output));
    }

    private static AEKey push(MassHost host, List<GenericStack> inputs) {
        var stack = MatterMassPatternItem.createPatternStack(inputs,
                List.of(new GenericStack(IRON, 1)), UUID.randomUUID());
        var pattern = new MatterMassPatternDetails(AEItemKey.of(stack));
        var holders = new KeyCounter[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            holders[i] = new KeyCounter();
            holders[i].add(inputs.get(i).what(), inputs.get(i).amount());
        }
        assertTrue(host.logic.pushPattern(pattern, holders));
        return pattern.getPrimaryOutput().what();
    }

    private static long pendingAmount(CompoundTag tag, AEKey key) {
        long amount = 0;
        var pending = tag.getList("matterMassPending", Tag.TAG_COMPOUND);
        var ops = REGISTRIES.createSerializationContext(NbtOps.INSTANCE);
        for (int i = 0; i < pending.size(); i++) {
            var inputs = pending.getCompound(i).getList("returnInputs", Tag.TAG_COMPOUND);
            for (int j = 0; j < inputs.size(); j++) {
                var stack = GenericStack.CODEC.parse(ops, inputs.getCompound(j)).getOrThrow();
                if (key.equals(stack.what())) amount += stack.amount();
            }
        }
        return amount;
    }

    private static final class MassHost extends ProviderTestSupport.Host implements MatterMassPatternProviderHost {
        private ReturnMode mode = ReturnMode.PASS_THROUGH;

        MassHost(BoundedStorage storage) {
            super(new ItemStackHandler(0), storage);
            logic = new ServerLogic(node, this);
        }

        @Override public MatterMassPatternProviderLogic getLogic() { return (MatterMassPatternProviderLogic) logic; }
        @Override public ReturnMode getReturnMode() { return mode; }
        @Override public void setReturnMode(ReturnMode mode) { this.mode = mode; }
        @Override public UUID getOwnerUuid() { return null; }
        @Override public EnumSet<Direction> getTargets() { return EnumSet.noneOf(Direction.class); }
    }

    /** Only the environmental side query is supplied; all inventory and tick code is production code. */
    private static final class ServerLogic extends MatterMassPatternProviderLogic {
        ServerLogic(IManagedGridNode node, MatterMassPatternProviderHost host) {
            super(node, host, 1, ProviderTestSupport.NO_INTEGRATION);
        }
        @Override public boolean isClientSide() { return false; }
    }
}
