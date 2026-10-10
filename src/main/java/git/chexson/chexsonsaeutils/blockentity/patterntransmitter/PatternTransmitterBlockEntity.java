package git.chexson.chexsonsaeutils.blockentity.patterntransmitter;

import appeng.api.networking.*;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;
import appeng.block.AEBaseEntityBlock;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.util.SettingsFrom;
import git.chexson.chexsonsaeutils.helpers.remote.*;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Set;
import java.util.UUID;

/** 正面接 B、背面接 A；两个节点永不互连，来源请求器和接收供应器分别注册。 */
public final class PatternTransmitterBlockEntity extends AENetworkedBlockEntity implements RemoteTransmitterHost {
    private final IManagedGridNode sourceNode;
    private final RemoteTransmitter transmitter;
    private UUID owner;
    public PatternTransmitterBlockEntity(BlockPos pos, BlockState state) {
        super(((AEBaseEntityBlock<?>) state.getBlock()).getBlockEntityType(), pos, state);
        getMainNode().setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(1);
        sourceNode = GridHelper.createManagedNode(this, new IGridNodeListener<PatternTransmitterBlockEntity>() {
            @Override public void onSaveChanges(PatternTransmitterBlockEntity host, IGridNode node) { host.saveChanges(); }
        }).setTagName("sourceNode").setInWorldNode(true).setVisualRepresentation(getItemFromBlockEntity())
                .setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(1)
                .setExposedOnSides(Set.of(getOrientation().getSide(RelativeSide.BACK)));
        transmitter = new RemoteTransmitterImpl(this);
    }
    public RemoteTransmitter transmitter() { return transmitter; }
    @Override public IManagedGridNode sourceNode() { return sourceNode; }
    @Override public IManagedGridNode receiverNode() { return getMainNode(); }
    @Override public Level world() { return getLevel(); }
    @Override public String remoteLabel() {
        return getName().getString() + " @ " + getBlockPos().toShortString() + " / " + getLevel().dimension().location();
    }
    @Override public void saveRemoteChanges() { saveChanges(); }
    @Override public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        return Set.of(orientation.getSide(RelativeSide.FRONT));
    }
    @Override public IGridNode getGridNode(Direction side) {
        return side == getOrientation().getSide(RelativeSide.BACK) ? sourceNode.getNode() : super.getGridNode(side);
    }
    @Override protected void onOrientationChanged(BlockOrientation orientation) {
        super.onOrientationChanged(orientation);
        if (sourceNode != null) sourceNode.setExposedOnSides(Set.of(orientation.getSide(RelativeSide.BACK)));
    }
    @Override public void setOwner(Player player) {
        super.setOwner(player); sourceNode.setOwningPlayer(player); owner = player.getUUID(); saveChanges();
    }
    /** AE2 此版本无网络安全服务；设备配置按放置者或管理员授权，暴露能力即授权 B 读取预览。 */
    public boolean canConfigure(Player player) { return owner == null || owner.equals(player.getUUID()) || player.hasPermissions(2); }
    @Override public void onReady() { super.onReady(); sourceNode.create(getLevel(), getBlockPos()); }
    @Override public void onChunkUnloaded() { transmitter.unload(false); sourceNode.destroy(); super.onChunkUnloaded(); }
    @Override public void setRemoved() { transmitter.unload(false); sourceNode.destroy(); super.setRemoved(); }
    @Override public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries); sourceNode.saveToNBT(tag);
        var remote = new CompoundTag(); transmitter.write(remote, registries); tag.put("remote", remote);
        if (owner != null) tag.putUUID("remoteOwner", owner);
    }
    @Override public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries); sourceNode.loadFromNBT(tag); transmitter.read(tag.getCompound("remote"), registries);
        owner = tag.hasUUID("remoteOwner") ? tag.getUUID("remoteOwner") : null;
    }
    @Override public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder, Player player) {
        super.exportSettings(mode, builder, player);
        if (mode == SettingsFrom.DISMANTLE_ITEM) {
            transmitter.unload(true);
            var tag = new CompoundTag(); transmitter.write(tag, getLevel().registryAccess());
            if (owner != null) tag.putUUID("owner", owner);
            builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
    @Override public void importSettings(SettingsFrom mode, DataComponentMap input, Player player) {
        super.importSettings(mode, input, player);
        if (mode == SettingsFrom.DISMANTLE_ITEM && input.has(DataComponents.CUSTOM_DATA)) {
            var tag = input.get(DataComponents.CUSTOM_DATA).copyTag();
            transmitter.read(tag, getLevel().registryAccess());
            owner = tag.hasUUID("owner") ? tag.getUUID("owner") : owner;
        }
    }
    /** creative 拆除不会有掉落，因此保留运行日志并取消来源任务，防止孤儿 requester。 */
    public void dismantle() { transmitter.unload(true); }
}
