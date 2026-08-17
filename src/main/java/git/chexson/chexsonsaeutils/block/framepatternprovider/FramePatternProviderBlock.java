package git.chexson.chexsonsaeutils.block.framepatternprovider;

import appeng.block.AEBaseEntityBlock;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * 框架样板供应器方块。
 * <p>
 * 该方块可以包裹任意可破坏方块（熔炉、压印机等），把原方块 BlockState 与原方块 BE 的 NBT
 * 存入框架 BE，并在渲染时透出原方块外观。阶段 3 将在此方块上接入 AE2 样板供应器逻辑。
 * <p>
 * 交互约定：
 * <ul>
 *   <li>潜行右击：拆框架，恢复原方块（含 BE NBT），框架方块掉落为物品。</li>
 *   <li>非潜行右击且手持带 wrench 标签的物品：打开 GUI（阶段 2 接入 MenuOpener）。</li>
 *   <li>非潜行右击且瞄准点在边框区域：打开 GUI（阶段 2 接入 MenuOpener）。</li>
 *   <li>非潜行右击且瞄准点在内部区域：透传原方块交互。</li>
 * </ul>
 * 捕获目标方块的动作由 {@link git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem}
 * 的 useOn 触发（手持框架物品右键已有方块时）。
 */
public class FramePatternProviderBlock extends AEBaseEntityBlock<FramePatternProviderBlockEntity> {

    /**
     * 边框判定阈值：命中点相对方块中心偏移超过该值（x/z 边缘或 y 接近 0/1）视为边框区域。
     */
    private static final double BORDER_THRESHOLD = 0.375;

    public FramePatternProviderBlock() {
        super(metalProps());
    }

    /**
     * 判断目标方块是否可被框架捕获。
     * <p>
     * 可捕获条件：非空气、非本框架方块、可破坏（destroySpeed &gt;= 0，排除基岩、屏障等）。
     *
     * @param level       目标方块所在世界
     * @param pos         目标方块位置
     * @param targetState 目标方块状态
     * @return true 表示可捕获
     */
    public static boolean canCapture(Level level, BlockPos pos, BlockState targetState) {
        if (targetState.isAir() || targetState.getBlock() instanceof FramePatternProviderBlock) {
            return false;
        }
        // 基岩、屏障等不可破坏方块 destroySpeed < 0，不允许被框架包裹
        return targetState.getDestroySpeed(level, pos) >= 0;
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack heldItem,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult
    ) {
        FramePatternProviderBlockEntity blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player.isShiftKeyDown()) {
            return dismantleFrame(level, pos, blockEntity);
        }
        if (heldItem.is(Tags.Items.TOOLS_WRENCH)) {
            // TODO(阶段2): 接入 MenuOpener 打开框架样板供应器 GUI
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        if (isOnBorder(hitResult, pos)) {
            // TODO(阶段2): 接入 MenuOpener 打开框架样板供应器 GUI
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        // 内部区域：透传原方块交互（用捕获的 BlockState 与原位置参数）
        BlockState capturedState = blockEntity.getCapturedState();
        if (capturedState == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return capturedState.useItemOn(heldItem, level, player, hand, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        FramePatternProviderBlockEntity blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            return dismantleFrameWithoutItem(level, pos, blockEntity);
        }
        if (isOnBorder(hitResult, pos)) {
            // TODO(阶段2): 接入 MenuOpener 打开框架样板供应器 GUI
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        // 内部区域：透传原方块交互（用捕获的 BlockState 与原位置参数）
        BlockState capturedState = blockEntity.getCapturedState();
        if (capturedState == null) {
            return InteractionResult.PASS;
        }
        return capturedState.useWithoutItem(level, player, hitResult);
    }

    /**
     * 拆框架（手持物品路径）：恢复原方块（含 BE NBT），框架方块掉落为物品。
     */
    private ItemInteractionResult dismantleFrame(
            Level level,
            BlockPos pos,
            FramePatternProviderBlockEntity blockEntity
    ) {
        if (!level.isClientSide()) {
            blockEntity.restoreCapturedBlock(level, pos);
            popResource(level, pos, new ItemStack(this));
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 拆框架（空手路径）：恢复原方块（含 BE NBT），框架方块掉落为物品。
     */
    private InteractionResult dismantleFrameWithoutItem(
            Level level,
            BlockPos pos,
            FramePatternProviderBlockEntity blockEntity
    ) {
        if (!level.isClientSide()) {
            blockEntity.restoreCapturedBlock(level, pos);
            popResource(level, pos, new ItemStack(this));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 破坏方块时恢复原方块（含 BE NBT）并掉落框架物品。
     * <p>
     * 注意：onRemove 在方块被替换（如捕获流程 setBlock）时也会触发，此时框架 BE 已被移除，
     * getBlockEntity 返回 null，不会误恢复。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide() && !newState.is(state.getBlock())) {
            FramePatternProviderBlockEntity blockEntity = this.getBlockEntity(level, pos);
            if (blockEntity != null && blockEntity.hasCapturedContent()) {
                blockEntity.restoreCapturedBlock(level, pos);
                popResource(level, pos, new ItemStack(this));
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * 判断命中点是否落在边框区域（相对方块中心偏移超过阈值）。
     */
    private static boolean isOnBorder(BlockHitResult hitResult, BlockPos pos) {
        Vec3 relative = hitResult.getLocation().subtract(Vec3.atCenterOf(pos));
        return Math.abs(relative.x) > BORDER_THRESHOLD
                || Math.abs(relative.y) > BORDER_THRESHOLD
                || Math.abs(relative.z) > BORDER_THRESHOLD;
    }
}