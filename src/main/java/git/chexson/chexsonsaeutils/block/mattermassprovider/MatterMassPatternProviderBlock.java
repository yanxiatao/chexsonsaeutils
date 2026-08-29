package git.chexson.chexsonsaeutils.block.mattermassprovider;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.blockentity.mattermassprovider.MatterMassPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 物质团供应器方块。
 * <p>
 * 无 PUSH_DIRECTION 属性（无任何对外推送）；右键打开菜单；放置时绑定放置者。
 * 无方块级 ticker（交付由网格 Ticker 驱动）。
 */
public class MatterMassPatternProviderBlock extends AEBaseEntityBlock<MatterMassPatternProviderBlockEntity> {

    public MatterMassPatternProviderBlock() {
        super(metalProps());
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()) {
            var blockEntity = this.getBlockEntity(level, pos);
            if (blockEntity != null) {
                blockEntity.setOwner(placer);
            }
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack heldItem,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            net.minecraft.world.InteractionHand hand,
            BlockHitResult hitResult
    ) {
        // 记忆卡优先：基类处理 IMemoryCard，非 PASS 表示已处理
        var result = super.useItemOn(heldItem, state, level, pos, player, hand, hitResult);
        if (result != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        var blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        openMenu(level, player, blockEntity);
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        var blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        openMenu(level, player, blockEntity);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    private void openMenu(Level level, Player player, MatterMassPatternProviderBlockEntity blockEntity) {
        if (!level.isClientSide()) {
            MenuOpener.open(
                    ChexsonsaeutilsContent.MATTER_MASS_PATTERN_PROVIDER_MENU.get(),
                    player,
                    MenuLocators.forBlockEntity(blockEntity)
            );
        }
    }
}
