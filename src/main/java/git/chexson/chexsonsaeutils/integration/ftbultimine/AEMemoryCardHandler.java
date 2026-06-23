package git.chexson.chexsonsaeutils.integration.ftbultimine;

import appeng.api.implementations.items.IMemoryCard;
import appeng.api.implementations.items.MemoryCardMessages;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.util.SettingsFrom;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Objects;

public final class AEMemoryCardHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Direction[] PART_SIDES = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    private AEMemoryCardHandler() {
    }

    public static int applySettings(
            ServerPlayer player,
            InteractionHand hand,
            @Nullable Collection<BlockPos> positions
    ) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof IMemoryCard memoryCard)) {
            return 0;
        }
        if (isEmptySelection(positions)) {
            return 0;
        }

        CompoundTag settingsTag = stack.getTag();
        if (settingsTag == null || settingsTag.isEmpty()) {
            return 0;
        }

        String storedName = settingsTag.getString("moniker");
        if (storedName.isEmpty()) {
            return 0;
        }
        Component storedNameComponent = Component.Serializer.fromJson(storedName);
        if (storedNameComponent == null) {
            return 0;
        }

        int appliedTargets = applySettings(player.level(), positions, settingsTag, storedNameComponent, player);
        if (appliedTargets > 0) {
            memoryCard.notifyUser(player, MemoryCardMessages.SETTINGS_LOADED);
        }
        return appliedTargets;
    }

    static int applySettings(
            Level level,
            @Nullable Collection<BlockPos> positions,
            CompoundTag settingsTag,
            Component storedName,
            ServerPlayer player
    ) {
        return applySettings(positions, level::getBlockEntity, settingsTag, storedName, player);
    }

    static int applySettings(
            @Nullable Collection<BlockPos> positions,
            TargetLookup targetLookup,
            CompoundTag settingsTag,
            Component storedName,
            @Nullable ServerPlayer player
    ) {
        if (isEmptySelection(positions)) return 0;

        int appliedTargets = 0;
        for (BlockPos pos : positions) {
            Object target = lookupTarget(targetLookup, pos);
            if (target == null) {
                LOGGER.debug("Skipping FTB Ultimine memory-card target {} because no block entity exists", pos);
                continue;
            }
            appliedTargets += applyTargetObject(target, settingsTag, storedName, player, pos.toShortString());
        }
        return appliedTargets;
    }

    static boolean isEmptySelection(@Nullable Collection<BlockPos> positions) {
        return positions == null || positions.isEmpty();
    }

    @Nullable
    private static Object lookupTarget(TargetLookup targetLookup, BlockPos pos) {
        try {
            return targetLookup.targetAt(pos);
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to resolve FTB Ultimine memory-card target at {}", pos, exception);
            return null;
        }
    }

    static int applyTargetObject(
            Object target, CompoundTag settingsTag, Component storedName,
            @Nullable ServerPlayer player, String debugName
    ) {
        int appliedTargets = 0;
        if (target instanceof IPartHost host) {
            for (Direction side : PART_SIDES) {
                appliedTargets += applyPartSide(host, side, settingsTag, storedName, player, debugName);
            }
            return appliedTargets;
        }
        if (target instanceof AEBaseBlockEntity aeBlockEntity
                && applyToBlockEntity(aeBlockEntity, settingsTag, storedName, player)) {
            return 1;
        }
        LOGGER.debug("Skipping FTB Ultimine memory-card target {} because it is not an AE settings target", debugName);
        return 0;
    }

    private static int applyPartSide(
            IPartHost host, @Nullable Direction side, CompoundTag settingsTag,
            Component storedName, @Nullable ServerPlayer player, String debugName
    ) {
        try {
            IPart part = host.getPart(side);
            if (part != null && applyToPart(part, settingsTag, storedName, player)) return 1;
            return 0;
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to apply FTB Ultimine memory-card settings to {} side {}", debugName, side, exception);
            return 0;
        }
    }

    static boolean applyToPart(IPart part, CompoundTag settingsTag, Component storedName, @Nullable ServerPlayer player) {
        return applyToTarget(new PartMemoryCardTarget(part), settingsTag, storedName, player);
    }

    static boolean applyToBlockEntity(AEBaseBlockEntity blockEntity, CompoundTag settingsTag, Component storedName, @Nullable ServerPlayer player) {
        return applyToTarget(new BlockEntityMemoryCardTarget(blockEntity), settingsTag, storedName, player);
    }

    static boolean applyToTarget(
            MemoryCardTarget target, CompoundTag settingsTag,
            Component storedName, @Nullable ServerPlayer player
    ) {
        try {
            if (Objects.equals(target.targetName(), storedName)) {
                target.importSettings(SettingsFrom.MEMORY_CARD, settingsTag, player);
                return true;
            }
            LOGGER.debug("Skipping target {} because settings source name didn't match", target.debugName());
            return false;
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to apply FTB Ultimine memory-card settings to {}", target.debugName(), exception);
            return false;
        }
    }

    static Component partTargetName(IPart part) {
        return partTargetName(
                part.getPartItem().asItem().getDescription(),
                part instanceof appeng.parts.misc.InterfacePart,
                AEBlocks.INTERFACE.asItem().getDescription(),
                part instanceof appeng.parts.crafting.PatternProviderPart,
                AEBlocks.PATTERN_PROVIDER.asItem().getDescription()
        );
    }

    static Component partTargetName(
            Component partName, boolean interfacePart, Component interfaceBlockName,
            boolean patternProviderPart, Component patternProviderBlockName
    ) {
        if (interfacePart) return interfaceBlockName;
        if (patternProviderPart) return patternProviderBlockName;
        return partName;
    }

    @FunctionalInterface
    interface TargetLookup {
        @Nullable Object targetAt(BlockPos pos);
    }

    public interface MemoryCardTarget {
        Component targetName();
        void importSettings(SettingsFrom mode, CompoundTag settings, @Nullable Player player);
        String debugName();
    }

    private record PartMemoryCardTarget(IPart part) implements MemoryCardTarget {
        @Override public Component targetName() { return partTargetName(part); }
        @Override public void importSettings(SettingsFrom mode, CompoundTag settings, @Nullable Player player) {
            part.importSettings(mode, settings, player);
        }
        @Override public String debugName() { return "part " + part.getPartItem().asItem(); }
    }

    private record BlockEntityMemoryCardTarget(AEBaseBlockEntity blockEntity) implements MemoryCardTarget {
        @Override public Component targetName() { return blockEntity.getBlockState().getBlock().getName(); }
        @Override public void importSettings(SettingsFrom mode, CompoundTag settings, @Nullable Player player) {
            blockEntity.importSettings(mode, settings, player);
        }
        @Override public String debugName() { return "block entity " + blockEntity.getBlockPos(); }
    }
}
