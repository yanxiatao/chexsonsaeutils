package git.chexson.chexsonsaeutils.block.framepatternprovider;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

import java.util.List;

/**
 * 框架样板供应器方块。
 * <p>
 * 该方块可以包裹任意可破坏方块（熔炉、压印机等），把原方块 BlockState 与原方块 BE 的 NBT
 * 存入框架 BE，并在渲染时透出原方块外观。阶段 3 将在此方块上接入 AE2 样板供应器逻辑。
 * <p>
 * 交互约定：
 * <ul>
 *   <li>潜行右击：拆框架，恢复原方块（含 BE NBT），框架方块掉落为物品；空框架不弹物品也不移除方块。</li>
 *   <li>非潜行右击且手持带 wrench 标签的物品：打开 GUI。</li>
 *   <li>非潜行右击且瞄准点在边框区域：打开 GUI。</li>
 *   <li>非潜行右击且瞄准点在内部区域：打开框架 GUI（不透传原方块交互）。</li>
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
     * 可捕获条件：非空气、非本框架方块、带方块实体（原位包装架构依赖机器 BE 实例代理，
     * 无 BE 方块无法包装）、可破坏（destroySpeed &gt;= 0，排除基岩、屏障等）。
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
        // 原位包装架构要求机器 BE 实例存在（无 BE 方块无法代理运行）
        if (!targetState.hasBlockEntity()) {
            return false;
        }
        // 基岩、屏障等不可破坏方块 destroySpeed < 0，不允许被框架包裹
        return targetState.getDestroySpeed(level, pos) >= 0;
    }

    /**
     * 服务端 ticker：驱动跨维度虚拟连接的建立/销毁（机器节点就绪检查）。
     * <p>
     * 项目 BE 注册不走 AE2 的自动 ticker 通道，需在此显式提供。
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof FramePatternProviderBlockEntity frameBlockEntity) {
                frameBlockEntity.serverTick();
            }
        };
    }

    /**
     * 邻居方块变化：驱动红石锁定模式（LOCK_UNTIL_PULSE）解锁判定（照 AE2
     * PatternProviderBlock 先例——logic.updateRedstoneState 的唯一触发点）；
     * 并转发给包装机器的原方块（多方块结构检查/红石感知依赖邻居事件）。
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
            boolean isMoving) {
        var be = this.getBlockEntity(level, pos);
        if (be != null) {
            be.getLogic().updateRedstoneState();
            var machine = be.getWrappedMachine();
            if (machine != null) {
                // 向包装机器位置广播邻居更新（Level 层标准入口；多方块结构检查/红石感知依赖此通知）
                level.neighborChanged(machine.getBlockPos(), block, fromPos);
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
            openMenu(level, player, blockEntity);
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        if (isOnBorder(hitResult, pos)) {
            openMenu(level, player, blockEntity);
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        // 内部区域：透传给包装机器执行原生右键交互（打开熔炉 GUI 等）；
        // 机器缺失或交互未消费时回退打开框架 GUI
        if (tryOriginalBlockUse(blockEntity, player, hand, hitResult)) {
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
        FramePatternProviderBlockEntity blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            return dismantleFrameWithoutItem(level, pos, blockEntity);
        }
        if (isOnBorder(hitResult, pos)) {
            openMenu(level, player, blockEntity);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        // 内部区域：透传给包装机器执行原生右键交互；机器缺失或交互 PASS 时回退打开框架 GUI
        var originalResult = tryOriginalBlockUse(blockEntity, player, InteractionHand.MAIN_HAND, hitResult);
        if (originalResult) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        openMenu(level, player, blockEntity);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 把右击透传给包装机器的原方块执行原生交互（内部区域点击路径）。
     * <p>
     * 机器 BE 与原方块同维度同位置，直接以其 BlockState 调用 useItemOn/useWithoutItem：
     * GUI 打开、距离校验（stillValid）均按真实位置工作。
     *
     * @return true 表示原方块消费了本次交互
     */
    /**
     * 打开包装机器的原生 GUI（内部区域点击路径）。
     * <p>
     * 实现说明：机器 BE 自身实现 MenuProvider（如箱子/熔炉），服务端直接
     * {@code openMenu} 打开其原生界面。不能走原方块 useWithoutItem 透传——
     * 其内部经 level.getBlockEntity(pos) 取自身时会拿到框架 BE（chunk 注册表
     * 一格一 BE），导致 GUI 打不开。
     * <p>
     * 已知限制：绕过原方块 Block 层的自定义交互逻辑（如带物品右击的特殊行为），
     * 统一直接打开机器 GUI；GUI 存活校验按机器真实位置（同维度同位置）天然通过。
     *
     * @return true 表示机器可提供 GUI（客户端据此发 C2S、服务端执行打开）
     */
    private boolean tryOriginalBlockUse(
            FramePatternProviderBlockEntity blockEntity,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult
    ) {
        var machine = blockEntity.getWrappedMachine();
        if (!(machine instanceof net.minecraft.world.MenuProvider menuProvider)) {
            return false;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.openMenu(menuProvider);
        }
        return true;
    }

    /**
     * 拆框架（手持物品路径）：恢复原方块（含 BE NBT），框架方块掉落为物品。
     * <p>
     * 空框架（未包裹任何方块）不弹物品也不移除方块，按正常挖掘破坏处理（B2 修复）。
     */
    private ItemInteractionResult dismantleFrame(
            Level level,
            BlockPos pos,
            FramePatternProviderBlockEntity blockEntity
    ) {
        if (!level.isClientSide()) {
            if (!blockEntity.hasCapturedContent()) {
                return ItemInteractionResult.sidedSuccess(false);
            }
            blockEntity.restoreCapturedBlock(level, pos);
            popResource(level, pos, createDroppedStack(blockEntity));
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 拆框架（空手路径）：恢复原方块（含 BE NBT），框架方块掉落为物品。
     * <p>
     * 空框架（未包裹任何方块）不弹物品也不移除方块，按正常挖掘破坏处理（B2 修复）。
     */
    private InteractionResult dismantleFrameWithoutItem(
            Level level,
            BlockPos pos,
            FramePatternProviderBlockEntity blockEntity
    ) {
        if (!level.isClientSide()) {
            if (!blockEntity.hasCapturedContent()) {
                return InteractionResult.sidedSuccess(false);
            }
            blockEntity.restoreCapturedBlock(level, pos);
            popResource(level, pos, createDroppedStack(blockEntity));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 破坏方块时恢复原方块（含 BE NBT）。
     * <p>
     * 掉落语义：挖掘（生存）与爆炸由 getDrops 掉 1 个框架物品，创造模式不掉；
     * shift 右击拆框架由 dismantleFrame/dismantleFrameWithoutItem 手动 popResource。
     * 此处不再 popResource，避免与 getDrops 叠加造成双倍掉落。
     * <p>
     * 顺序约定：先取 BE 引用 → super.onRemove 先执行（移除本框架 BE）→ 再恢复。
     * 嵌套 setBlock（restoreCapturedBlock 内部恢复机器）触发本方法时，restoring 标志置位，
     * 跳过恢复逻辑防递归；捕获流程 setBlock 触发的是原机器方块的 onRemove，与本方法无关。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        FramePatternProviderBlockEntity blockEntity = this.getBlockEntity(level, pos);
        super.onRemove(state, level, pos, newState, isMoving);
        if (!level.isClientSide() && !newState.is(state.getBlock()) && blockEntity != null && !blockEntity.isRestoring()) {
            if (blockEntity.hasCapturedContent()) {
                blockEntity.restoreCapturedBlock(level, pos);
            }
        }
    }

    /**
     * 构造掉落物品：携带已解锁样板页数组件（需求 5 拆除保留闭环）。
     * <p>
     * 拆框架路径（dismantleFrame/dismantleFrameWithoutItem）与挖掘掉落路径（getDrops）共用。
     */
    private static ItemStack createDroppedStack(FramePatternProviderBlockEntity blockEntity) {
        ItemStack stack = new ItemStack(ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get());
        stack.set(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), blockEntity.getPages());
        return stack;
    }

    /**
     * 挖掘掉落：携带已解锁样板页数组件（需求 5 拆除保留闭环）。
     * <p>
     * destroyBlock 流程中 getDrops 在 setBlock(air) 之前调用，BE 仍可访问；
     * 覆写保持原版单物品掉落语义，仅附加页数组件。
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        ItemStack stack = new ItemStack(ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get());
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
                instanceof FramePatternProviderBlockEntity blockEntity) {
            stack.set(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), blockEntity.getPages());
        }
        return List.of(stack);
    }

    /**
     * 打开框架样板供应器 GUI。
     * <p>
     * 只在服务端调用 MenuOpener（客户端由菜单打开包自动处理），与项目现有方块开 GUI 方式一致。
     */
    private void openMenu(Level level, Player player, FramePatternProviderBlockEntity blockEntity) {
        if (!level.isClientSide()) {
            MenuOpener.open(
                    Chexsonsaeutils.FRAME_PATTERN_PROVIDER_MENU.get(),
                    player,
                    MenuLocators.forBlockEntity(blockEntity)
            );
        }
    }

    /**
     * 判断命中点是否落在边框区域（点在边条投影区内）。
     * <p>
     * 判定规则：命中点恒在方块表面（命中面法线轴偏移恒为 ±0.5），法线轴不参与判定；
     * 只检查命中点在面内两轴的偏移是否超过阈值——面中心区域为内部（透传机器交互），
     * 面边缘（边条投影区）为边框（打开框架 GUI）。
     */
    private static boolean isOnBorder(BlockHitResult hitResult, BlockPos pos) {
        Vec3 relative = hitResult.getLocation().subtract(Vec3.atCenterOf(pos));
        return switch (hitResult.getDirection().getAxis()) {
            case X -> Math.abs(relative.y) > BORDER_THRESHOLD || Math.abs(relative.z) > BORDER_THRESHOLD;
            case Y -> Math.abs(relative.x) > BORDER_THRESHOLD || Math.abs(relative.z) > BORDER_THRESHOLD;
            case Z -> Math.abs(relative.x) > BORDER_THRESHOLD || Math.abs(relative.y) > BORDER_THRESHOLD;
        };
    }
}