package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.config.*;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.*;
import appeng.api.parts.*;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.StorageHelper;
import appeng.api.util.*;
import appeng.core.definitions.AEItems;
import appeng.items.parts.PartModels;
import appeng.me.helpers.MachineSource;
import appeng.parts.automation.PlaneModels;
import appeng.parts.automation.UpgradeablePart;
import appeng.util.ConfigInventory;
import appeng.util.SettingsFrom;
import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;
import java.util.Random;

/** 玩家模拟面板运行部件：真实物品缓存、服务端调度与网络装配。 */
public final class PlayerSimulationPart extends UpgradeablePart
        implements PlayerSimulation, IGridTickable, ICraftingRequester {
    private static final PlaneModels MODELS = new PlaneModels("part/formation_plane", "part/formation_plane_on");
    private final SimulationMenuOpener menuOpener;
    private final ConfigInventory config = ConfigInventory.configStacks(SimulationBuffer.SIZE)
            .supportedType(AEKeyType.items()).changeListener(this::configurationChanged).build();
    private final SimulationBuffer buffer = new SimulationBuffer();
    private final SimulationCrafting crafting = new SimulationCrafting(this);
    private final IActionSource source = new MachineSource(this);
    private final Random random = new Random();
    private UUID identity = UUID.randomUUID();
    private SimulationSettings.Click click = SimulationSettings.Click.RIGHT;
    private SimulationSettings.ClickFace clickFace = SimulationSettings.ClickFace.AUTO;
    private SimulationSettings.Target target = SimulationSettings.Target.BLOCK;
    private SimulationSettings.Selection selection = SimulationSettings.Selection.ROUND_ROBIN;
    private SimulationSettings.Cooldown cooldown = SimulationSettings.Cooldown.MATCH;
    private SimulationSettings.Press leftPress = SimulationSettings.Press.HOLD;
    private SimulationSettings.Press rightPress = SimulationSettings.Press.TAP;
    private SimulationSettings.Hold leftHold = SimulationSettings.Hold.CONTINUOUS;
    private SimulationSettings.Hold rightHold = SimulationSettings.Hold.NORMAL;
    private boolean sneaking;
    private boolean loading;
    private boolean pulsePending;
    private boolean lastRedstone;
    private boolean faulted;
    private int cursor = -1;
    private int activeSlot = -1;
    private long nextAction;
    private String status = "idle";
    private CompoundTag actorData = new CompoundTag();
    private SimulationActor actor;

    public PlayerSimulationPart(IPartItem<?> item, SimulationMenuOpener opener) {
        super(item);
        this.menuOpener = opener;
        getMainNode().addService(IGridTickable.class, this).addService(ICraftingRequester.class, this);
        getMainNode().setIdlePowerUsage(2);
    }
    @Override protected int getUpgradeSlots() { return 5; }
    @Override protected void registerSettings(IConfigManagerBuilder builder) {
        super.registerSettings(builder);
        builder.registerSetting(Settings.REDSTONE_CONTROLLED, RedstoneMode.IGNORE);
        builder.registerSetting(Settings.FUZZY_MODE, FuzzyMode.IGNORE_ALL);
    }
    @Override public RedstoneMode getRSMode() { return getConfigManager().getSetting(Settings.REDSTONE_CONTROLLED); }
    @Override public ConfigInventory getConfig() { return config; }
    @Override public SimulationSettings.Click click() { return click; }
    @Override public SimulationSettings.ClickFace clickFace() { return clickFace; }
    @Override public SimulationSettings.Target target() { return target; }
    @Override public SimulationSettings.Selection selection() { return selection; }
    @Override public SimulationSettings.Cooldown cooldown() { return cooldown; }
    @Override public SimulationSettings.Press press() { return click == SimulationSettings.Click.LEFT ? leftPress : rightPress; }
    @Override public SimulationSettings.Hold hold() { return click == SimulationSettings.Click.LEFT ? leftHold : rightHold; }
    @Override public boolean sneaking() { return sneaking; }
    @Override public UUID identity() { return identity; }
    @Override public String status() { return status; }
    @Override public int bufferedAmount(int slot) { return buffer.get(slot).getCount(); }
    @Override public ItemStack getMainMenuIcon() { return new ItemStack(getPartItem()); }
    @Override public void returnToMainMenu(Player player, appeng.menu.ISubMenu subMenu) {
        if (player instanceof ServerPlayer serverPlayer) menuOpener.open(serverPlayer, this);
    }
    @Override public boolean onUseWithoutItem(Player player, Vec3 hit) {
        if (player instanceof ServerPlayer serverPlayer) menuOpener.open(serverPlayer, this);
        return true;
    }
    @Override public void cycle(String option) {
        switch (option) {
            case "click" -> click = SimulationSettings.next(click, SimulationSettings.Click.values());
            case "click_face" -> clickFace = SimulationSettings.next(clickFace,SimulationSettings.ClickFace.values());
            case "target" -> target = SimulationSettings.next(target, SimulationSettings.Target.values());
            case "selection" -> selection = SimulationSettings.next(selection, SimulationSettings.Selection.values());
            case "cooldown" -> cooldown = SimulationSettings.next(cooldown, SimulationSettings.Cooldown.values());
            case "press" -> {
                if (click == SimulationSettings.Click.LEFT) leftPress = SimulationSettings.next(leftPress,SimulationSettings.Press.values());
                else rightPress = SimulationSettings.next(rightPress,SimulationSettings.Press.values());
            }
            case "hold" -> {
                if (click == SimulationSettings.Click.LEFT) leftHold = SimulationSettings.next(leftHold,SimulationSettings.Hold.values());
                else rightHold = SimulationSettings.next(rightHold,SimulationSettings.Hold.values());
            }
            case "sneaking" -> sneaking = !sneaking;
            default -> throw new IllegalArgumentException("未知模拟面板选项: " + option);
        }
        configurationChanged();
    }
    private void configurationChanged() {
        if (loading || getHost() == null) return;
        cancelAction();
        faulted = false;
        nextAction = 0;
        pulsePending = false;
        lastRedstone = getHost().hasRedstone();
        cursor = -1;
        getHost().markForSave();
    }
    @Override public void upgradesChanged() { configurationChanged(); }
    @Override protected void onSettingChanged(IConfigManager manager, Setting<?> setting) { configurationChanged(); }
    @Override protected void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        if (!getMainNode().isActive()) cancelAction();
        getHost().markForUpdate();
    }
    @Override public int availableSlots() { return 18 + 9 * getUpgrades().getInstalledUpgrades(AEItems.CAPACITY_CARD); }
    private AEItemKey key(int slot) { return (AEItemKey) config.getKey(slot); }
    private int desired(int slot) {
        var key = key(slot);
        return key == null || slot >= availableSlots() ? 0 : (int) Math.min(config.getAmount(slot), key.toStack().getMaxStackSize());
    }
    @Override public TickingRequest getTickingRequest(IGridNode node) { return new TickingRequest(1,1,false); }
    @Override public TickRateModulation tickingRequest(IGridNode node, int elapsed) {
        if (!(getLevel() instanceof ServerLevel level)) return TickRateModulation.SAME;
        boolean redstone = getHost().hasRedstone();
        if (redstone && !lastRedstone) pulsePending = true;
        lastRedstone = redstone;
        if (actor != null) actor.tickClock();
        if (!getMainNode().isActive() || faulted) {
            cancelAction();
            status = faulted ? "error" : "offline";
            return TickRateModulation.SAME;
        }
        IGrid grid = node.getGrid();
        try {
            long now = level.getGameTime();
            // 补货与开始操作都受同一加速间隔约束，持续动作每 tick 推进。
            boolean due = now >= nextAction;
            if (due) {
                nextAction = now + SimulationSchedule.interval(getUpgrades().getInstalledUpgrades(AEItems.SPEED_CARD));
                refill(grid);
            }
            boolean allowed = !isUpgradedWith(AEItems.REDSTONE_CARD) || switch (getRSMode()) {
                case IGNORE -> true;
                case HIGH_SIGNAL -> redstone;
                case LOW_SIGNAL -> !redstone;
                case SIGNAL_PULSE -> pulsePending || actor != null && actor.busy();
            };
            if (!allowed) { cancelAction(); status = "redstone"; return TickRateModulation.SAME; }
            if (actor != null && actor.busy()) {
                boolean unavailable = activeSlot >= 0 && (buffer.get(activeSlot).isEmpty() || !buffer.belongs(activeSlot,key(activeSlot)));
                boolean stockedWhileEmpty = activeSlot < 0 && due && java.util.stream.IntStream.range(0,availableSlots()).anyMatch(i -> !buffer.get(i).isEmpty());
                if (unavailable || stockedWhileEmpty) {
                    cancelAction();
                    if (due) beginAction(level);
                } else execute(level, activeSlot, due);
            } else if (due) {
                beginAction(level);
            } else if (actor != null) {
                // 加速间隔只限制新交互；手持物品和模组附魔 tick 每个世界 tick 正常推进。
                SimulationInteraction.execute(actor,buffer,cursor,availableSlots(),this::key,() -> status,this::returnItem);
                getHost().markForSave();
            }
        } catch (RuntimeException e) {
            faulted = true;
            cancelAction();
            status = "error";
            LogUtils.getLogger().error("玩家模拟面板 {} 在 {} 交互失败，已停止；修改配置后可重试", identity, getBlockEntity().getBlockPos(), e);
        }
        return TickRateModulation.SAME;
    }
    private void refill(IGrid grid) {
        for (int i = 0; i < SimulationBuffer.SIZE; i++) {
            var key = key(i);
            int desired = desired(i);
            buffer.reconcile(i,key,desired,this::returnItem);
            crafting.reconcile(i,key,isUpgradedWith(AEItems.CRAFTING_CARD) && desired > 0);
            int missing = desired - buffer.get(i).getCount();
            if (missing <= 0 || key == null) continue;
            // 已有缓存只补入同组件物品；空槽先精确提取，再按 AE2 模糊规则选一个实际变体。
            var actual = buffer.get(i).isEmpty() ? key : AEItemKey.of(buffer.get(i));
            long extracted = acquire(grid,i,key,actual,missing);
            if (extracted == 0 && buffer.get(i).isEmpty() && isUpgradedWith(AEItems.FUZZY_CARD)) {
                for (var entry : grid.getStorageService().getCachedInventory().findFuzzy(key,getConfigManager().getSetting(Settings.FUZZY_MODE))) {
                    if (entry.getKey() instanceof AEItemKey variant && !variant.equals(key)) {
                        extracted = acquire(grid,i,key,variant,missing);
                        if (extracted > 0) break;
                    }
                }
            }
            missing -= (int)extracted;
            if (missing > 0 && (buffer.get(i).isEmpty() || key.matches(buffer.get(i))) && isUpgradedWith(AEItems.CRAFTING_CARD)) {
                crafting.request(i,key,missing,getLevel(),grid,source);
                getHost().markForSave();
            }
        }
    }
    private long acquire(IGrid grid, int slot, AEItemKey origin, AEItemKey actual, int amount) {
        int limit = Math.min(amount,actual.toStack().getMaxStackSize()-buffer.get(slot).getCount());
        long extracted = StorageHelper.poweredExtraction(grid.getEnergyService(),grid.getStorageService().getInventory(),actual,limit,source);
        if (extracted > 0) {
            var stack = buffer.get(slot);
            if (stack.isEmpty()) buffer.set(slot,origin,actual.toStack((int)extracted));
            else stack.grow((int)extracted);
            getHost().markForSave();
        }
        return extracted;
    }
    private void beginAction(ServerLevel level) {
        pulsePending = false;
        int slot = SimulationSchedule.select(selection,availableSlots(),cursor >= availableSlots() ? -1 : cursor,
                i -> !buffer.get(i).isEmpty(),random);
        if (slot >= 0) cursor = slot;
        execute(level,slot,true);
    }
    private void execute(ServerLevel level, int slot, boolean repeatAllowed) {
        if (actor == null) actor = new SimulationActorImpl(level,identity,actorData);
        actor.position(getBlockEntity().getBlockPos(),getSide(),clickFace,sneaking);
        activeSlot = slot;
        try {
            status = SimulationInteraction.execute(actor,buffer,slot,availableSlots(),this::key,
                    () -> actor.act(click,target,cooldown,press(),hold().ticks,repeatAllowed),this::returnItem);
        } finally {
            getHost().markForSave();
        }

        if (!actor.busy()) activeSlot = -1;
    }
    private void cancelAction() {
        if (actor != null) actor.cancel();
        activeSlot = -1;
    }
    private void returnItem(ItemStack stack) {
        var grid = getMainNode().getGrid();
        var key = AEItemKey.of(stack);
        long inserted = grid == null ? 0 : grid.getStorageService().getInventory().insert(key,stack.getCount(),Actionable.MODULATE,source);
        stack.shrink((int)inserted);
        if (!stack.isEmpty() && getLevel() instanceof ServerLevel level) {
            Vec3 pos = Vec3.atCenterOf(getBlockEntity().getBlockPos().relative(getSide()));
            level.addFreshEntity(new ItemEntity(level,pos.x,pos.y,pos.z,stack));
            LogUtils.getLogger().warn("玩家模拟面板 {} 的网络拒绝回收 {}，已掉落到 {}",identity,stack,pos);
        }
    }
    @Override public void returnBuffers() {
        cancelAction();
        buffer.drain(this::returnItem);
        crafting.cancelAll();
        nextAction = getLevel().getGameTime() + 20;
        getHost().markForSave();
    }
    @Override public ImmutableSet<ICraftingLink> getRequestedJobs() { return crafting.jobs(); }
    @Override public void jobStateChange(ICraftingLink link) { crafting.changed(link); getHost().markForSave(); }
    @Override public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        if (!getMainNode().isActive()) return 0;
        // 结果优先直接交付对应缓存，不要求网络另有空闲物品类型；配置改变后的剩余量退回网络。
        int slot = crafting.slot(link);
        long inserted = 0;
        if (slot >= 0 && what instanceof AEItemKey itemKey && key(slot) != null
                && (itemKey.equals(key(slot)) || isUpgradedWith(AEItems.FUZZY_CARD)
                && key(slot).fuzzyEquals(itemKey,getConfigManager().getSetting(Settings.FUZZY_MODE))))
            inserted = buffer.accept(slot,key(slot),itemKey,desired(slot),amount,mode);
        if (inserted < amount)
            inserted += getMainNode().getGrid().getStorageService().getInventory().insert(what,amount-inserted,mode,source);
        if (mode == Actionable.MODULATE && inserted > 0) getHost().markForSave();
        return inserted;
    }
    @Override public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        loading = true;
        try {
            super.readFromNBT(tag,registries);
            config.readFromChildTag(tag,"config",registries);
            buffer.read(tag,registries);
            crafting.read(tag,registries);
            readOptions(tag);
            actorData = tag.getCompound("actor").copy();
            cursor = tag.contains("cursor") ? tag.getInt("cursor") : -1;
            if (cursor < -1 || cursor >= SimulationBuffer.SIZE) throw new IllegalArgumentException("模拟面板游标越界");
            nextAction = tag.getLong("nextAction");
            faulted = tag.getBoolean("faulted");
        } finally { loading = false; }
    }
    @Override public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag,registries);
        config.writeToChildTag(tag,"config",registries);
        buffer.write(tag,registries);
        crafting.write(tag,registries);
        writeOptions(tag);
        tag.put("actor",actor == null ? actorData.copy() : actor.save());
        tag.putInt("cursor",cursor);
        tag.putLong("nextAction",nextAction);
        tag.putBoolean("faulted",faulted);
    }
    private void writeOptions(CompoundTag tag) {
        tag.putUUID("identity",identity);
        tag.putString("clickFace",clickFace.name());
        tag.putString("click",click.name()); tag.putString("target",target.name());
        tag.putString("selection",selection.name()); tag.putString("cooldown",cooldown.name());
        tag.putString("leftPress",leftPress.name()); tag.putString("rightPress",rightPress.name());
        tag.putString("leftHold",leftHold.name()); tag.putString("rightHold",rightHold.name()); tag.putBoolean("sneaking",sneaking);
    }
    private void readOptions(CompoundTag tag) {
        if (tag.hasUUID("identity")) identity = tag.getUUID("identity");
        if (tag.contains("clickFace")) clickFace = SimulationSettings.ClickFace.valueOf(tag.getString("clickFace"));
        if (tag.contains("click")) click = SimulationSettings.Click.valueOf(tag.getString("click"));
        if (tag.contains("target")) target = SimulationSettings.Target.valueOf(tag.getString("target"));
        if (tag.contains("selection")) selection = SimulationSettings.Selection.valueOf(tag.getString("selection"));
        if (tag.contains("cooldown")) cooldown = SimulationSettings.Cooldown.valueOf(tag.getString("cooldown"));
        if (tag.contains("leftPress")) leftPress = SimulationSettings.Press.valueOf(tag.getString("leftPress"));
        if (tag.contains("rightPress")) rightPress = SimulationSettings.Press.valueOf(tag.getString("rightPress"));
        if (tag.contains("leftHold")) leftHold = SimulationSettings.Hold.valueOf(tag.getString("leftHold"));
        if (tag.contains("rightHold")) rightHold = SimulationSettings.Hold.valueOf(tag.getString("rightHold"));
        sneaking = tag.getBoolean("sneaking");
    }
    @Override public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder) {
        super.exportSettings(mode,builder);
        if (mode == SettingsFrom.DISMANTLE_ITEM) {
            var data = new CompoundTag(); writeOptions(data);
            data.put("actor",actor == null ? actorData.copy() : actor.save());
            builder.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        }
    }
    @Override public void importSettings(SettingsFrom mode, DataComponentMap data, Player player) {
        super.importSettings(mode,data,player);
        if (mode == SettingsFrom.DISMANTLE_ITEM && data.has(DataComponents.CUSTOM_DATA)) {
            var tag = data.get(DataComponents.CUSTOM_DATA).copyTag();
            readOptions(tag); actorData = tag.getCompound("actor").copy();
        }
    }
    @Override public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops,wrenched); buffer.addDrops(drops);
    }
    @Override public void clearContent() {
        super.clearContent(); buffer.clear(); crafting.cancelAll(); cancelAction();
    }
    @Override public void removeFromWorld() {
        cancelAction();
        crafting.cancelCalculations();
        // 卸载可恢复链接，计算任务不能继续引用旧世界。
        if (actor != null) { actorData = actor.save(); actor = null; }
        super.removeFromWorld();
    }
    @Override public float getCableConnectionLength(AECableType cable) { return 1; }
    @Override public void getBoxes(IPartCollisionHelper helper) {
        helper.addBox(0,0,15,16,16,16); helper.addBox(5,5,14,11,11,15);
    }
    @PartModels public static List<IPartModel> getModels() { return MODELS.getModels(); }
    @Override public IPartModel getStaticModels() { return MODELS.getModel(isPowered(),isActive()); }
}
