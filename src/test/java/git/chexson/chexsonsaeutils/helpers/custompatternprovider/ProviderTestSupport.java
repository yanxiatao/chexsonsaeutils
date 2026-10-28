package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeService;
import appeng.api.networking.IGridService;
import appeng.api.networking.IGridVisitor;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.ITickManager;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.util.AEColor;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.me.service.CraftingService;
import appeng.menu.AEBaseMenu;
import git.chexson.chexsonsaeutils.integration.CustomPatternEnergyInjector;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

/** Runs the real provider lifecycle against item handlers and a bounded ME inventory. */
public final class ProviderTestSupport {
    private ProviderTestSupport() {}

    public static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    public static final CustomPatternProviderIntegrationFactory NO_INTEGRATION = (host, node, source) ->
            new CustomPatternProviderIntegration() {
                @Override public CustomPatternEnergyInjector energyInjector() { return null; }
                @Override public void handleDelayedInit(PatternProviderLogic logic) {}
                @Override public boolean updateWirelessLink(PatternProviderLogic logic) { return false; }
            };

    public static class Host implements CustomPatternProviderLogicHost {
        public final ManagedNode node;
        public final Grid grid;
        public CustomPatternProviderLogic logic;
        public int saves;
        private final IItemHandler machine;
        private final BlockEntity blockEntity = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());

        public Host(IItemHandler machine, MEStorage storage) {
            this.machine = machine;
            this.grid = new Grid(storage);
            this.node = new ManagedNode(grid);
        }

        public static Host custom(IItemHandler machine, MEStorage storage) {
            var host = new Host(machine, storage);
            host.logic = new CustomPatternProviderLogic(host.node, host, 1, NO_INTEGRATION);
            return host;
        }

        public CompoundTag save() {
            var tag = new CompoundTag();
            logic.writeToNBT(tag, REGISTRIES);
            return tag;
        }

        public TickRateModulation tick() {
            return node.getService(IGridTickable.class).tickingRequest(node.node, 1);
        }

        @Override public CustomPatternProviderLogic getLogic() { return logic; }
        @Override public BlockEntity getBlockEntity() { return blockEntity; }
        @Override public EnumSet<Direction> getTargets() { return EnumSet.of(Direction.NORTH); }
        @Override public void saveChanges() { saves++; }
        @Override public IItemHandler getMachineItemHandler() { return machine; }
        @Override public IEnergyStorage getMachineEnergyHandler() { return null; }
        @Override public MenuType<? extends AEBaseMenu> getMainMenuType() { return null; }
        @Override public ItemStack getMainMenuIcon() { return new ItemStack(Items.CHEST); }
        @Override public AEItemKey getTerminalIcon() { return AEItemKey.of(Items.CHEST); }
    }

    public static final class ManagedNode implements IManagedGridNode {
        private final Map<Class<?>, IGridNodeService> services = new HashMap<>();
        private final GridNode node;

        ManagedNode(Grid grid) { node = new GridNode(this, grid); }

        public <T extends IGridNodeService> T getService(Class<T> type) { return type.cast(services.get(type)); }
        @Override public <T extends IGridNodeService> IManagedGridNode addService(Class<T> type, T service) {
            services.put(type, service);
            return this;
        }
        @Override public void destroy() { throw unused(); }
        @Override public void create(Level level, BlockPos pos) { throw unused(); }
        @Override public void loadFromNBT(CompoundTag tag) { throw unused(); }
        @Override public void saveToNBT(CompoundTag tag) { throw unused(); }
        @Override public IManagedGridNode setFlags(GridFlags... flags) { return this; }
        @Override public IManagedGridNode setExposedOnSides(Set<Direction> sides) { return this; }
        @Override public IManagedGridNode setIdlePowerUsage(double usage) { return this; }
        @Override public IManagedGridNode setVisualRepresentation(AEItemKey key) { return this; }
        @Override public IManagedGridNode setInWorldNode(boolean inWorld) { return this; }
        @Override public IManagedGridNode setTagName(String name) { return this; }
        @Override public IManagedGridNode setGridColor(AEColor color) { return this; }
        @Override public boolean isReady() { return true; }
        @Override public boolean isActive() { return true; }
        @Override public boolean isOnline() { return true; }
        @Override public boolean isPowered() { return true; }
        @Override public boolean hasGridBooted() { return true; }
        @Override public void setOwningPlayerId(int id) { throw unused(); }
        @Override public void setOwningPlayer(Player player) { throw unused(); }
        @Override public IGridNode getNode() { return node; }
    }

    private record GridNode(ManagedNode managed, IGrid grid) implements IGridNode {
        @Override public IGrid getGrid() { return grid; }
        @Override public <T extends IGridNodeService> T getService(Class<T> type) { return managed.getService(type); }
        @Override public Object getOwner() { return managed; }
        @Override public void beginVisit(IGridVisitor visitor) { throw unused(); }
        @Override public ServerLevel getLevel() { return null; }
        @Override public Set<Direction> getConnectedSides() { return Set.of(); }
        @Override public Map<Direction, IGridConnection> getInWorldConnections() { return Map.of(); }
        @Override public List<IGridConnection> getConnections() { return List.of(); }
        @Override public boolean hasGridBooted() { return true; }
        @Override public boolean isPowered() { return true; }
        @Override public boolean meetsChannelRequirements() { return true; }
        @Override public boolean hasFlag(GridFlags flag) { return false; }
        @Override public int getOwningPlayerId() { return -1; }
        @Override public UUID getOwningPlayerProfileId() { return null; }
        @Override public double getIdlePowerUsage() { return 0; }
        @Override public AEItemKey getVisualRepresentation() { return AEItemKey.of(Items.CHEST); }
        @Override public AEColor getGridColor() { return AEColor.TRANSPARENT; }
        @Override public void fillCrashReportCategory(CrashReportCategory category) {}
        @Override public int getMaxChannels() { return 8; }
        @Override public int getUsedChannels() { return 1; }
    }

    public static final class Grid implements IGrid {
        private final StorageService storage;
        public final CompletionRecorder crafting;
        private final ITickManager ticks = new ITickManager() {
            @Override public boolean alertDevice(IGridNode node) { return true; }
            @Override public boolean sleepDevice(IGridNode node) { return true; }
            @Override public boolean wakeDevice(IGridNode node) { return true; }
        };

        Grid(MEStorage inventory) {
            storage = new StorageService(inventory);
            crafting = new CompletionRecorder(this, storage);
        }

        @Override public <C extends IGridService> C getService(Class<C> type) {
            if (type == IStorageService.class) return type.cast(storage);
            if (type == ICraftingService.class) return type.cast(crafting);
            if (type == ITickManager.class) return type.cast(ticks);
            throw unused();
        }
        @Override public <T extends appeng.api.networking.events.GridEvent> T postEvent(T event) { return event; }
        @Override public Iterable<Class<?>> getMachineClasses() { return List.of(); }
        @Override public Iterable<IGridNode> getMachineNodes(Class<?> type) { return List.of(); }
        @Override public <T> Set<T> getMachines(Class<T> type) { return Set.of(); }
        @Override public <T> Set<T> getActiveMachines(Class<T> type) { return Set.of(); }
        @Override public Iterable<IGridNode> getNodes() { return List.of(); }
        @Override public boolean isEmpty() { return false; }
        @Override public IGridNode getPivot() { throw unused(); }
        @Override public int size() { return 1; }
        @Override public void export(com.google.gson.stream.JsonWriter writer) { throw unused(); }
    }

    public static final class CompletionRecorder extends CraftingService {
        public final KeyCounter completed = new KeyCounter();
        CompletionRecorder(Grid grid, StorageService storage) { super(grid, storage, null); }
        @Override public long insertIntoCpus(AEKey key, long amount, Actionable mode) {
            if (mode == Actionable.MODULATE) completed.add(key, amount);
            return amount;
        }
        @Override public void refreshNodeCraftingProvider(IGridNode node) {}
    }

    private record StorageService(MEStorage inventory) implements IStorageService {
        @Override public MEStorage getInventory() { return inventory; }
        @Override public KeyCounter getCachedInventory() { return inventory.getAvailableStacks(); }
        @Override public void addGlobalStorageProvider(IStorageProvider provider) {}
        @Override public void removeGlobalStorageProvider(IStorageProvider provider) {}
        @Override public void refreshNodeStorageProvider(IGridNode node) {}
        @Override public void refreshGlobalStorageProvider(IStorageProvider provider) {}
        @Override public void invalidateCache() {}
    }

    /** Type slots and amount share capacity, just as they do in a finite network cell. */
    public static final class BoundedStorage implements MEStorage {
        public final KeyCounter stored = new KeyCounter();
        public int typeCapacity;
        public long amountCapacity;

        public BoundedStorage(int typeCapacity, long amountCapacity) {
            this.typeCapacity = typeCapacity;
            this.amountCapacity = amountCapacity;
        }

        @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (stored.get(key) == 0 && stored.size() >= typeCapacity) return 0;
            long used = 0;
            for (var entry : stored) used += entry.getLongValue();
            long accepted = Math.max(0, Math.min(amount, amountCapacity - used));
            if (mode == Actionable.MODULATE && accepted > 0) stored.add(key, accepted);
            return accepted;
        }
        @Override public void getAvailableStacks(KeyCounter counter) { counter.addAll(stored); }
        @Override public Component getDescription() { return Component.literal("Bounded provider test storage"); }
    }

    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException("Provider test does not use this operation");
    }
}
