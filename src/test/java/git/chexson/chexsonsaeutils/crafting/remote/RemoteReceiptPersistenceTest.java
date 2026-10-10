package git.chexson.chexsonsaeutils.crafting.remote;
import appeng.api.config.Actionable;
import appeng.api.networking.*;
import appeng.api.stacks.*;
import appeng.api.util.AEColor;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.helpers.remote.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteReceiptPersistenceTest {
    private static final HolderLookup.Provider REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final AEKey KEY = AEItemKey.of(Items.IRON_INGOT);
    @Test void requesterIsRegisteredOnlyOnSourceAndReceiptRestoresWithoutResubmitOrManufacturedItems() {
        var host = new Host(); var runtime = new RemoteTransmitterImpl(host);
        assertTrue(host.source.services.containsKey(appeng.api.networking.crafting.ICraftingRequester.class));
        assertFalse(host.receiver.services.containsKey(appeng.api.networking.crafting.ICraftingRequester.class));
        assertTrue(host.receiver.services.containsKey(appeng.api.networking.crafting.ICraftingProvider.class));
        assertSame(host.source.getNode(), runtime.getActionableNode());
        var id = UUID.randomUUID(); var tag = stored(id, 70);
        runtime.read(tag, REGISTRIES);
        var link = runtime.getRequestedJobs().iterator().next();
        assertEquals(id, link.getCraftingID());
        assertEquals(70, runtime.insertCraftedItems(link, KEY, 100, Actionable.SIMULATE));
        assertTrue(runtime.orderLines().stream().noneMatch(s -> s.startsWith("待交付")));
        assertEquals(25, runtime.insertCraftedItems(link, KEY, 25, Actionable.MODULATE));
        var saved = new CompoundTag(); runtime.write(saved, REGISTRIES);
        var restored = new RemoteTransmitterImpl(new Host()); restored.read(saved, REGISTRIES);
        var restoredLink = restored.getRequestedJobs().iterator().next();
        assertEquals(id, restoredLink.getCraftingID());
        assertEquals(45, restored.insertCraftedItems(restoredLink, KEY, 100, Actionable.MODULATE));
        restored.jobStateChange(restoredLink);
        assertTrue(restored.getRequestedJobs().isEmpty());
        assertTrue(restored.orderLines().stream().anyMatch(s -> s.startsWith("待交付") && s.endsWith("70")));
        var complete = new CompoundTag(); restored.write(complete, REGISTRIES);
        assertTrue(complete.getList("tasks", Tag.TAG_COMPOUND).isEmpty());
        assertTrue(complete.getList("pending", Tag.TAG_COMPOUND).isEmpty());
        restored.cancel();
        assertTrue(restored.orderLines().stream().anyMatch(s -> s.startsWith("待交付") && s.endsWith("70")));
        assertTrue(host.saves > 0);
    }
    @Test void internalDefinitionIncludesGenericTypeAndReconstructsSamePatternIdentity() {
        var stack = new ItemStack(Items.PAPER); var data = new CompoundTag();
        data.putInt("version", 1); data.put("output", KEY.toTagGeneric(REGISTRIES));
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        var definition = AEItemKey.of(stack);
        var decoded = RemotePatternItem.decodeDefinition(definition, REGISTRIES);
        assertNotNull(decoded); assertEquals(KEY, decoded.output());
        assertEquals(new RemotePatternImpl(definition, KEY), decoded);
        assertEquals(0, decoded.getInputs().length);
        assertEquals(1, decoded.getOutputs().size());
    }
    private static CompoundTag stored(UUID id, long remaining) {
        var tag = new CompoundTag(); tag.putUUID("identity", UUID.randomUUID()); tag.putString("status", "来源执行中");
        var link = new CompoundTag(); link.putUUID("craftId", id); link.putBoolean("req", true); link.putBoolean("standalone", false);
        var task = new CompoundTag(); task.putUUID("id", id); task.put("key", KEY.toTagGeneric(REGISTRIES)); task.putLong("remaining", remaining); task.put("link", link);
        var tasks = new ListTag(); tasks.add(task); tag.put("tasks", tasks);
        return tag;
    }
    private static final class Host implements RemoteTransmitterHost {
        final Node source = new Node(), receiver = new Node(); int saves;
        @Override public IManagedGridNode sourceNode() { return source; }
        @Override public IManagedGridNode receiverNode() { return receiver; }
        @Override public Level world() { return null; }
        @Override public String remoteLabel() { return "测试传递器"; }
        @Override public void saveRemoteChanges() { saves++; }
    }
    private static final class Node implements IManagedGridNode {
        final FakeGrid grid = FakeGrid.builder().build(); final Map<Class<?>, Object> services = new HashMap<>();
        @Override public <T extends IGridNodeService> IManagedGridNode addService(Class<T> type, T service) { services.put(type, service); return this; }
        @Override public IGridNode getNode() { return grid.node; }
        @Override public boolean isReady() { return true; }
        @Override public boolean isActive() { return true; }
        @Override public boolean isOnline() { return true; }
        @Override public boolean isPowered() { return true; }
        @Override public boolean hasGridBooted() { return true; }
        @Override public void destroy() { services.clear(); }
        @Override public void create(Level level, BlockPos pos) { throw new UnsupportedOperationException("测试节点由 fixture 构造"); }
        @Override public void loadFromNBT(CompoundTag tag) { throw new UnsupportedOperationException("本用例只验证 requester 存档"); }
        @Override public void saveToNBT(CompoundTag tag) { throw new UnsupportedOperationException("本用例只验证 requester 存档"); }
        @Override public IManagedGridNode setFlags(GridFlags... flags) { return this; }
        @Override public IManagedGridNode setExposedOnSides(Set<Direction> sides) { return this; }
        @Override public IManagedGridNode setIdlePowerUsage(double amount) { return this; }
        @Override public IManagedGridNode setVisualRepresentation(AEItemKey item) { return this; }
        @Override public IManagedGridNode setInWorldNode(boolean value) { return this; }
        @Override public IManagedGridNode setTagName(String name) { return this; }
        @Override public IManagedGridNode setGridColor(AEColor color) { return this; }
        @Override public void setOwningPlayerId(int id) { throw new UnsupportedOperationException("本用例不创建玩家"); }
        @Override public void setOwningPlayer(Player player) { throw new UnsupportedOperationException("本用例不创建玩家"); }
    }
}
