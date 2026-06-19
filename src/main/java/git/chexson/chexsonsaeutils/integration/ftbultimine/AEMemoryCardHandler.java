package git.chexson.chexsonsaeutils.integration.ftbultimine;

import appeng.api.ids.AEComponents;
import appeng.api.implementations.items.IMemoryCard;
import appeng.api.implementations.items.MemoryCardMessages;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.items.tools.MemoryCardItem;
import appeng.parts.crafting.PatternProviderPart;
import appeng.parts.misc.InterfacePart;
import appeng.util.SettingsFrom;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

public final class AEMemoryCardHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Direction[] PART_SIDES = {
            Direction.DOWN,
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            null
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

        DataComponentMap settings = stack.getComponents();
        Component storedName = stack.get(AEComponents.EXPORTED_SETTINGS_SOURCE);
        if (isEmptyMemoryCard(settings, storedName)) {
            return 0;
        }

        int appliedTargets = applySettings(player.level(), positions, settings, storedName, player);
        if (appliedTargets > 0) {
            memoryCard.notifyUser(player, MemoryCardMessages.SETTINGS_LOADED);
        }
        return appliedTargets;
    }

    static int applySettings(
            Level level,
            @Nullable Collection<BlockPos> positions,
            DataComponentMap settings,
            Component storedName,
            ServerPlayer player
    ) {
        return applySettings(positions, level::getBlockEntity, settings, storedName, player);
    }

    static int applySettings(
            @Nullable Collection<BlockPos> positions,
            TargetLookup targetLookup,
            DataComponentMap settings,
            Component storedName,
            @Nullable ServerPlayer player
    ) {
        if (isEmptySelection(positions)) {
            return 0;
        }

        int appliedTargets = 0;
        for (BlockPos pos : positions) {
            Object target = lookupTarget(targetLookup, pos);
            if (target == null) {
                LOGGER.debug("Skipping FTB Ultimine memory-card target {} because no block entity exists", pos);
                continue;
            }

            appliedTargets += applyTargetObject(target, settings, storedName, player, pos.toShortString());
        }
        return appliedTargets;
    }

    static boolean isEmptySelection(@Nullable Collection<BlockPos> positions) {
        return positions == null || positions.isEmpty();
    }

    static boolean isEmptyMemoryCard(DataComponentMap settings, @Nullable Component storedName) {
        return storedName == null || settings.isEmpty();
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

    static int applyBlockEntity(
            BlockEntity blockEntity,
            DataComponentMap settings,
            Component storedName,
            ServerPlayer player
    ) {
        return applyTargetObject(blockEntity, settings, storedName, player, blockEntity.getBlockPos().toShortString());
    }

    static int applyTargetObject(
            Object target,
            DataComponentMap settings,
            Component storedName,
            @Nullable ServerPlayer player,
            String debugName
    ) {
        int appliedTargets = 0;
        if (target instanceof IPartHost host) {
            for (Direction side : PART_SIDES) {
                appliedTargets += applyPartSide(host, side, settings, storedName, player, debugName);
            }
            return appliedTargets;
        }

        if (target instanceof AEBaseBlockEntity aeBlockEntity
                && applyToBlockEntity(aeBlockEntity, settings, storedName, player)) {
            return 1;
        }

        LOGGER.debug("Skipping FTB Ultimine memory-card target {} because it is not an AE settings target", debugName);
        return 0;
    }

    private static int applyPartSide(
            IPartHost host,
            @Nullable Direction side,
            DataComponentMap settings,
            Component storedName,
            @Nullable ServerPlayer player,
            String debugName
    ) {
        try {
            IPart part = host.getPart(side);
            if (part != null && applyToPart(part, settings, storedName, player)) {
                return 1;
            }
            return 0;
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to apply FTB Ultimine memory-card settings to {} side {}",
                    debugName, side, exception);
            return 0;
        }
    }

    static boolean applyToPart(
            IPart part,
            DataComponentMap settings,
            Component storedName,
            @Nullable ServerPlayer player
    ) {
        return applyToTarget(new PartMemoryCardTarget(part), settings, storedName, player);
    }

    static boolean applyToBlockEntity(
            AEBaseBlockEntity blockEntity,
            DataComponentMap settings,
            Component storedName,
            @Nullable ServerPlayer player
    ) {
        return applyToTarget(new BlockEntityMemoryCardTarget(blockEntity), settings, storedName, player);
    }

    static boolean hasStoredSettings(DataComponentMap settings) {
        return !isEmptyMemoryCard(settings, settings.get(AEComponents.EXPORTED_SETTINGS_SOURCE));
    }

    static boolean applyToTarget(
            MemoryCardTarget target,
            DataComponentMap settings,
            Component storedName,
            @Nullable ServerPlayer player
    ) {
        Component targetName = target.targetName();
        try {
            if (Objects.equals(targetName, storedName)) {
                target.importSettings(SettingsFrom.MEMORY_CARD, settings, player);
                return true;
            }

            Set<DataComponentType<?>> imported = target.importGenericSettings(settings, player);
            if (!imported.isEmpty()) {
                return true;
            }

            LOGGER.debug("Skipping FTB Ultimine memory-card target {} because no generic settings matched",
                    target.debugName());
            return false;
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to apply FTB Ultimine memory-card settings to {}", target.debugName(), exception);
            return false;
        }
    }

    static Component partTargetName(IPart part) {
        return partTargetName(
                part.getPartItem().asItem().getDescription(),
                part instanceof InterfacePart,
                AEBlocks.INTERFACE.asItem().getDescription(),
                part instanceof PatternProviderPart,
                AEBlocks.PATTERN_PROVIDER.asItem().getDescription()
        );
    }

    static Component partTargetName(
            Component partName,
            boolean interfacePart,
            Component interfaceBlockName,
            boolean patternProviderPart,
            Component patternProviderBlockName
    ) {
        if (interfacePart) {
            return interfaceBlockName;
        }
        if (patternProviderPart) {
            return patternProviderBlockName;
        }
        return partName;
    }

    @FunctionalInterface
    interface TargetLookup {

        @Nullable
        Object targetAt(BlockPos pos);
    }

    public interface MemoryCardTarget {

        Component targetName();

        void importSettings(SettingsFrom mode, DataComponentMap settings, @Nullable Player player);

        Set<DataComponentType<?>> importGenericSettings(DataComponentMap settings, @Nullable Player player);

        String debugName();
    }

    private record PartMemoryCardTarget(IPart part) implements MemoryCardTarget {

        @Override
        public Component targetName() {
            return partTargetName(part);
        }

        @Override
        public void importSettings(SettingsFrom mode, DataComponentMap settings, @Nullable Player player) {
            part.importSettings(mode, settings, player);
        }

        @Override
        public Set<DataComponentType<?>> importGenericSettings(DataComponentMap settings, @Nullable Player player) {
            return MemoryCardItem.importGenericSettings(part, settings, player);
        }

        @Override
        public String debugName() {
            return "part " + part.getPartItem().asItem();
        }
    }

    private record BlockEntityMemoryCardTarget(AEBaseBlockEntity blockEntity) implements MemoryCardTarget {

        @Override
        public Component targetName() {
            return blockEntity.getBlockState().getBlock().getName();
        }

        @Override
        public void importSettings(SettingsFrom mode, DataComponentMap settings, @Nullable Player player) {
            blockEntity.importSettings(mode, settings, player);
        }

        @Override
        public Set<DataComponentType<?>> importGenericSettings(DataComponentMap settings, @Nullable Player player) {
            return MemoryCardItem.importGenericSettings(blockEntity, settings, player);
        }

        @Override
        public String debugName() {
            return "block entity " + blockEntity.getBlockPos();
        }
    }
}
