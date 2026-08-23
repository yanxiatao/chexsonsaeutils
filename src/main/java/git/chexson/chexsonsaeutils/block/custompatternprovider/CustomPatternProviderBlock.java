package git.chexson.chexsonsaeutils.block.custompatternprovider;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.Tags;

import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.crafting.PushDirection;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.util.Platform;
import git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 定制样板供应器方块（阶段 2）。
 * <p>
 * 与框架样板供应器的差异：不包裹机器，机器即周围相邻方块。推送方向由
 * PUSH_DIRECTION 属性控制（复用 AE2 PatternProviderBlock 的属性与 PushDirection
 * 枚举，blockstates 变体名 push_direction）：ALL = 全部 6 方向，定向 = 单方向。
 * <p>
 * 交互约定：
 * <ul>
 *   <li>手持 wrench 标签物品右击：循环旋转推送方向（照 extendedae BlockExPatternProvider.setSide）。</li>
 *   <li>其他右击（含空手）：打开定制样板供应器 GUI。</li>
 * </ul>
 * 无 serverTick（无私有维度/隔离），getTicker 返回 null。
 */
public class CustomPatternProviderBlock extends AEBaseEntityBlock<CustomPatternProviderBlockEntity> {

    /** 推送方向属性（复用 AE2 的属性实例，blockstates 变体名 push_direction）。 */
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<PushDirection> PUSH_DIRECTION = PatternProviderBlock.PUSH_DIRECTION;

    public CustomPatternProviderBlock() {
        super(metalProps());
        this.registerDefaultState(this.defaultBlockState().setValue(PUSH_DIRECTION, PushDirection.ALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PUSH_DIRECTION);
    }

    /**
     * 无 ticker：本方块无 serverTick 需求（无私有维度/隔离/搬移逻辑）。
     */
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    /**
     * 邻居方块变化：驱动红石锁定模式（LOCK_UNTIL_PULSE）解锁判定（照 AE2
     * PatternProviderBlock 先例——logic.updateRedstoneState 的唯一触发点）。
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
            boolean isMoving) {
        var be = this.getBlockEntity(level, pos);
        if (be != null) {
            be.getLogic().updateRedstoneState();
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
        // 记忆卡优先（I2 修复）：AEBaseEntityBlock.useItemOn 处理 IMemoryCard
        // （潜行导出/非潜行导入），非 PASS 表示基类已处理，直接返回
        var result = super.useItemOn(heldItem, state, level, pos, player, hand, hitResult);
        if (result != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return result;
        }
        CustomPatternProviderBlockEntity blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (heldItem.is(Tags.Items.TOOLS_WRENCH)) {
            setSide(level, pos, hitResult.getDirection());
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
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
        CustomPatternProviderBlockEntity blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        openMenu(level, player, blockEntity);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 扳手循环旋转推送方向（照 extendedae BlockExPatternProvider.setSide）。
     * <p>
     * 规则：点击面 = 当前推送方向的反面 → 定向到点击面；点击面 = 当前推送方向 →
     * 回到 ALL；当前为 ALL → 定向到点击面的反面；否则绕点击面轴旋转。
     */
    public void setSide(Level level, BlockPos pos, Direction facing) {
        var currentState = level.getBlockState(pos);
        var pushSide = currentState.getValue(PUSH_DIRECTION).getDirection();

        PushDirection newPushDirection;
        if (pushSide == facing.getOpposite()) {
            newPushDirection = PushDirection.fromDirection(facing);
        } else if (pushSide == facing) {
            newPushDirection = PushDirection.ALL;
        } else if (pushSide == null) {
            newPushDirection = PushDirection.fromDirection(facing.getOpposite());
        } else {
            newPushDirection = PushDirection.fromDirection(Platform.rotateAround(pushSide, facing));
        }

        level.setBlockAndUpdate(pos, currentState.setValue(PUSH_DIRECTION, newPushDirection));
    }

    /**
     * 挖掘掉落：携带已解锁样板页数组件（需求 5 拆除保留闭环，与框架版一致）。
     * <p>
     * S2 修复：super.getDrops（AEBaseEntityBlock）已导出 DISMANTLE_ITEM 设置
     * （customName 等，经 BE.exportSettings），此处仅对掉落物品附加页数组件，
     * 不再自行构造掉落（原实现丢失自定义名称）。
     * destroyBlock 流程中 getDrops 在 setBlock(air) 之前调用，BE 仍可访问。
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var drops = super.getDrops(state, params);
        for (var drop : drops) {
            if (drop.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == this) {
                if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
                        instanceof CustomPatternProviderBlockEntity blockEntity) {
                    drop.set(ChexsonsaeutilsContent.CUSTOM_PATTERN_PAGES.get(), blockEntity.getPages());
                }
                break;
            }
        }
        return drops;
    }

    /**
     * 打开定制样板供应器 GUI（只在服务端调用 MenuOpener，与项目现有方块一致）。
     */
    private void openMenu(Level level, Player player, CustomPatternProviderBlockEntity blockEntity) {
        if (!level.isClientSide()) {
            MenuOpener.open(
                    CustomPatternProviderMenu.TYPE,
                    player,
                    MenuLocators.forBlockEntity(blockEntity)
            );
        }
    }
}