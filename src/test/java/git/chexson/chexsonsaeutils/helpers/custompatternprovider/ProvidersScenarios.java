package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternRegistration;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomProcessingPattern;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import static git.chexson.chexsonsaeutils.helpers.custompatternprovider.ProviderTestSupport.REGISTRIES;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Material conservation across normal provider pushes, persistence, and removal. */
public final class ProvidersScenarios {
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey COBBLESTONE = AEItemKey.of(Items.COBBLESTONE);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    private ProvidersScenarios() {}

    public static void verify(Path temporaryRoot) {
        assertAll("custom provider material ownership",
                ProvidersScenarios::differentInputsCannotShareAnEmptyMappedSlot,
                ProvidersScenarios::sameInputChecksCumulativeCapacityBeforeAnyWrite,
                ProvidersScenarios::sameInputCanFillOneMappedSlotExactly,
                ProvidersScenarios::repeatedMappingCannotOverflowAnIntegerStack,
                ProvidersScenarios::automaticInputCannotOccupyAnExplicitSlotFirst,
                ProvidersScenarios::automaticAndExplicitSameInputRetainTheRemainder,
                ProvidersScenarios::clampedOverflowAndLongAutomaticInputRetainEveryItem,
                ProvidersScenarios::reloadAndRepeatedReadDropEachHeldItemOnce,
                ProvidersScenarios::reloadThenDeliverLeavesNoParentGhostInventory);
    }

    private static void differentInputsCannotShareAnEmptyMappedSlot() {
        var machine = new ItemStackHandler(1);
        var host = host(machine);
        var inputs = List.of(new GenericStack(DIAMOND, 1), new GenericStack(COBBLESTONE, 63));
        var pattern = pattern(host, inputs, new int[]{0, 0}, false);
        var held = holder(inputs);

        assertFalse(host.logic.pushPattern(pattern, held), "one empty slot cannot merge different materials");
        assertTrue(machine.getStackInSlot(0).isEmpty());
        assertEquals(1, held[0].get(DIAMOND));
        assertEquals(63, held[0].get(COBBLESTONE));
        assertFalse(host.logic.isBusy());
    }

    private static void sameInputChecksCumulativeCapacityBeforeAnyWrite() {
        var machine = new ItemStackHandler(2);
        machine.setStackInSlot(1, DIAMOND.toStack(1));
        var host = host(machine);
        var inputs = List.of(new GenericStack(IRON, 1), new GenericStack(DIAMOND, 32),
                new GenericStack(DIAMOND, 32));
        var pattern = pattern(host, inputs, new int[]{0, 1, 1}, false);

        assertFalse(host.logic.pushPattern(pattern, holder(inputs)), "1 + 32 + 32 exceeds the shared slot limit");
        assertTrue(machine.getStackInSlot(0).isEmpty(), "an earlier valid slot must not be written on rejection");
        assertEquals(1, machine.getStackInSlot(1).getCount());
    }

    private static void sameInputCanFillOneMappedSlotExactly() {
        var machine = new ItemStackHandler(1);
        machine.setStackInSlot(0, DIAMOND.toStack(1));
        var host = host(machine);
        var inputs = List.of(new GenericStack(DIAMOND, 31), new GenericStack(DIAMOND, 32));
        var pattern = pattern(host, inputs, new int[]{0, 0}, false);

        assertTrue(host.logic.pushPattern(pattern, holder(inputs)));
        assertTrue(DIAMOND.matches(machine.getStackInSlot(0)));
        assertEquals(64, machine.getStackInSlot(0).getCount());
        assertFalse(host.logic.isBusy());
    }

    private static void repeatedMappingCannotOverflowAnIntegerStack() {
        var machine = new ItemStackHandler(1);
        var host = host(machine);
        var inputs = List.of(new GenericStack(DIAMOND, Integer.MAX_VALUE - 1L), new GenericStack(DIAMOND, 2));
        var pattern = pattern(host, inputs, new int[]{0, 0}, true);

        assertFalse(host.logic.pushPattern(pattern, holder(inputs)));
        assertTrue(machine.getStackInSlot(0).isEmpty());
    }

    private static void automaticInputCannotOccupyAnExplicitSlotFirst() {
        var inputs = List.of(new GenericStack(COBBLESTONE, 63), new GenericStack(DIAMOND, 1));
        for (int slots : new int[]{1, 2}) {
            var machine = new ItemStackHandler(slots);
            var host = host(machine);
            var pattern = pattern(host, inputs, new int[]{-1, 0}, false);

            assertTrue(host.logic.pushPattern(pattern, holder(inputs)));
            assertTrue(DIAMOND.matches(machine.getStackInSlot(0)));
            assertEquals(1, machine.getStackInSlot(0).getCount());
            if (slots == 1) {
                assertEquals(63, queued(host.save(), COBBLESTONE));
                machine.setStackInSlot(0, ItemStack.EMPTY);
                host.tick();
                assertTrue(COBBLESTONE.matches(machine.getStackInSlot(0)));
                assertEquals(63, machine.getStackInSlot(0).getCount());
            } else {
                assertTrue(COBBLESTONE.matches(machine.getStackInSlot(1)));
                assertEquals(63, machine.getStackInSlot(1).getCount());
            }
            assertFalse(host.logic.isBusy());
        }
    }

    private static void automaticAndExplicitSameInputRetainTheRemainder() {
        var machine = new ItemStackHandler(1);
        var host = host(machine);
        var inputs = List.of(new GenericStack(DIAMOND, 32), new GenericStack(DIAMOND, 33));
        var pattern = pattern(host, inputs, new int[]{-1, 0}, false);

        assertTrue(host.logic.pushPattern(pattern, holder(inputs)));
        assertEquals(64, machine.getStackInSlot(0).getCount());
        assertEquals(1, queued(host.save(), DIAMOND));
    }

    private static void clampedOverflowAndLongAutomaticInputRetainEveryItem() {
        var clampingMachine = new ItemStackHandler(1) {
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                super.setStackInSlot(slot, stack.copyWithCount(Math.min(64, stack.getCount())));
            }
        };
        var host = host(clampingMachine);
        var inputs = List.of(new GenericStack(DIAMOND, 40), new GenericStack(DIAMOND, 40));
        var pattern = pattern(host, inputs, new int[]{0, 0}, true);
        assertTrue(host.logic.pushPattern(pattern, holder(inputs)));
        assertEquals(64, clampingMachine.getStackInSlot(0).getCount());
        assertEquals(16, queued(host.save(), DIAMOND));

        var automaticMachine = new ItemStackHandler(1);
        var automaticHost = host(automaticMachine);
        long amount = Integer.MAX_VALUE + 5L;
        var automaticInputs = List.of(new GenericStack(IRON, amount));
        var automaticPattern = pattern(automaticHost, automaticInputs, new int[]{-1}, false);
        assertTrue(automaticHost.logic.pushPattern(automaticPattern, holder(automaticInputs)));
        assertEquals(amount, automaticMachine.getStackInSlot(0).getCount() + queued(automaticHost.save(), IRON));
    }

    private static void reloadAndRepeatedReadDropEachHeldItemOnce() {
        var original = pendingProvider();
        original.logic.getConfigManager().putSetting(Settings.PATTERN_ACCESS_TERMINAL, YesNo.NO);
        original.logic.setPriority(7);
        var saved = original.save();
        var loaded = host(new ItemStackHandler(1));
        loaded.logic.readFromNBT(saved, REGISTRIES);
        loaded.logic.readFromNBT(saved, REGISTRIES);

        assertEquals(YesNo.NO, loaded.logic.getConfigManager().getSetting(Settings.PATTERN_ACCESS_TERMINAL));
        assertEquals(7, loaded.logic.getPriority());
        assertFalse(loaded.logic.getPatternInv().getStackInSlot(0).isEmpty());
        assertEquals(4, queued(saved, DIAMOND), "reading must not mutate the original tag");
        var drops = new ArrayList<ItemStack>();
        loaded.logic.addDrops(drops);
        assertEquals(4, count(drops, DIAMOND));
        assertEquals(3, count(drops, IRON));
        assertEquals(1, drops.stream().filter(stack -> stack.is(CustomPatternRegistration.CUSTOM_PATTERN_ITEM.get())).count());

        loaded.logic.clearContent();
        var afterClear = new ArrayList<ItemStack>();
        loaded.logic.addDrops(afterClear);
        assertTrue(afterClear.stream().allMatch(ItemStack::isEmpty));
    }

    private static void reloadThenDeliverLeavesNoParentGhostInventory() {
        var saved = pendingProvider().save();
        var network = new ProviderTestSupport.BoundedStorage(10, Long.MAX_VALUE);
        var machine = new ItemStackHandler(1);
        var loaded = ProviderTestSupport.Host.custom(machine, network);
        loaded.logic.readFromNBT(saved, REGISTRIES);
        loaded.tick();

        assertEquals(4, machine.getStackInSlot(0).getCount());
        assertTrue(DIAMOND.matches(machine.getStackInSlot(0)));
        assertEquals(3, network.stored.get(IRON));
        assertFalse(loaded.logic.isBusy());
        assertTrue(loaded.logic.getReturnInv().isEmpty());
        var drops = new ArrayList<ItemStack>();
        loaded.logic.addDrops(drops);
        assertEquals(0, count(drops, DIAMOND));
        assertEquals(0, count(drops, IRON));
    }

    private static ProviderTestSupport.Host pendingProvider() {
        var machine = new ItemStackHandler(1);
        machine.setStackInSlot(0, new ItemStack(Items.DIRT, 64));
        var host = host(machine);
        var inputs = List.of(new GenericStack(DIAMOND, 4));
        var pattern = pattern(host, inputs, new int[]{-1}, false);
        assertTrue(host.logic.pushPattern(pattern, holder(inputs)));
        assertEquals(3, host.logic.getReturnInv().insert(IRON, 3, Actionable.MODULATE, IActionSource.empty()));
        return host;
    }

    private static ProviderTestSupport.Host host(ItemStackHandler machine) {
        return ProviderTestSupport.Host.custom(machine, new ProviderTestSupport.BoundedStorage(10, Long.MAX_VALUE));
    }

    private static CustomProcessingPattern pattern(ProviderTestSupport.Host host, List<GenericStack> inputs,
            int[] mapping, boolean overflow) {
        var stack = new ItemStack(CustomPatternRegistration.CUSTOM_PATTERN_ITEM.get());
        CustomProcessingPattern.encode(stack, inputs, List.of(new GenericStack(IRON, 1)), mapping, new int[0], overflow);
        host.logic.getPatternInv().setItemDirect(0, stack);
        host.logic.updatePatterns();
        // AE2's global decoder requires a live Level. This fixture has no world, so register
        // the real decoded pattern explicitly while retaining the real pattern slot and push gate.
        var decoded = new CustomProcessingPattern(AEItemKey.of(stack));
        host.logic.getAvailablePatterns().add(decoded);
        assertTrue(host.node.isActive());
        assertTrue(host.logic.getAvailablePatterns().contains(decoded));
        return decoded;
    }

    private static KeyCounter[] holder(List<GenericStack> inputs) {
        var counter = new KeyCounter();
        for (var input : inputs) counter.add(input.what(), input.amount());
        return new KeyCounter[]{counter};
    }

    private static long queued(CompoundTag tag, AEKey key) {
        long amount = 0;
        var entries = tag.getList(CustomPatternProviderLogic.NBT_SEND_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            var entry = GenericStack.readTag(REGISTRIES, entries.getCompound(i));
            if (entry != null && key.equals(entry.what())) amount += entry.amount();
        }
        return amount;
    }

    private static long count(List<ItemStack> stacks, AEItemKey key) {
        return stacks.stream().filter(key::matches).mapToLong(ItemStack::getCount).sum();
    }
}
