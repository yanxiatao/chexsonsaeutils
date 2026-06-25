package git.chexson.chexsonsaeutils.integration.buildinggadgets2;

import appeng.core.definitions.AEItems;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.setup.Registration;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.GadgetUtils;
import com.direwolf20.buildinggadgets2.util.MiscHelpers;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BuildingGadgets2TemplatePatternBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuildingGadgets2TemplatePatternBridge() {
    }

    public static boolean isAe2PatternTarget(ItemStack stack) {
        return stack.is(AEItems.BLANK_PATTERN.asItem()) || stack.is(AEItems.PROCESSING_PATTERN.asItem());
    }

    public static boolean tryEncodeIntoTemplateSlot(
            ServerPlayer player,
            AbstractContainerMenu menu,
            @Nullable String templateName
    ) {
        ItemStack gadgetStack = menu.getSlot(0).getItem();
        ItemStack targetStack = menu.getSlot(1).getItem();
        if (!isAe2PatternTarget(targetStack)) {
            return false;
        }

        UUID sourceUuid = GadgetNBT.getUUID(gadgetStack);
        BG2Data data = BG2Data.get(player.serverLevel().getServer().overworld());
        ArrayList<StatePos> buildList = data.getCopyPasteList(sourceUuid, false);
        if (buildList == null || buildList.isEmpty()) {
            LOGGER.warn("BG2 pattern encoding failed for template '{}' because copy-paste data is empty",
                    templateName);
            playFailureSound(player);
            return true;
        }

        BuildingGadgets2PatternEncodingResult result = BuildingGadgets2PatternEncoder.encode(
                states(buildList),
                state -> GadgetUtils.getDropsForBlockState(player.serverLevel(), BlockPos.ZERO, state, player),
                Registration.Template.get(),
                templateName
        );
        if (!result.encoded()) {
            LOGGER.warn("BG2 pattern encoding failed for template '{}' after skipping {} states",
                    templateName,
                    result.skippedStates());
            playFailureSound(player);
            return true;
        }

        menu.setItem(1, menu.getStateId(), result.stack());
        LOGGER.info(
                "BG2 pattern encoding wrote template '{}' with {} input types{}",
                templateName,
                result.encodedInputTypes(),
                result.truncated() ? " after truncating " + result.totalInputTypes() + " material types" : ""
        );
        playSuccessSound(player);
        return true;
    }

    private static Iterable<BlockState> states(List<StatePos> buildList) {
        return buildList.stream()
                .map(statePos -> statePos.state)
                .toList();
    }

    private static void playFailureSound(ServerPlayer player) {
        MiscHelpers.playSound(player, Holder.direct(SoundEvents.WAXED_SIGN_INTERACT_FAIL));
    }

    private static void playSuccessSound(ServerPlayer player) {
        MiscHelpers.playSound(player, Holder.direct(SoundEvents.ENCHANTMENT_TABLE_USE));
    }
}
