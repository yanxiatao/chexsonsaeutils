package git.chexson.chexsonsaeutils.blockentity.mattermassprovider;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNodeListener;
import appeng.api.orientation.BlockOrientation;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.helpers.mattermassprovider.MatterMassPatternProviderHost;
import git.chexson.chexsonsaeutils.helpers.mattermassprovider.MatterMassPatternProviderLogic;
import git.chexson.chexsonsaeutils.helpers.mattermassprovider.ReturnMode;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 物质团供应器方块实体。
 * <p>
 * 无任何对外输入输出：不注册 ITEM/ENERGY capability，网格节点为唯一对外触点。
 * 放置时绑定放置者 UUID（玩家模式交付目标）；返回模式（网络/玩家）与
 * 待交付队列经 logic NBT 持久化。
 * <p>
 * 网格节点：REQUIRE_CHANNEL；ICraftingProvider 与 IGridTickable 服务由 logic
 * 构造时注册（字段初始化先于构造器体）。
 */
public class MatterMassPatternProviderBlockEntity extends AENetworkedBlockEntity
        implements MatterMassPatternProviderHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_PAGES = "pages";
    private static final String NBT_OWNER_UUID = "ownerUuid";
    private static final String NBT_RETURN_MODE = "returnMode";

    /** 每页样板槽数量：与定制样板供应器一致（4 行 x 9 列）。 */
    public static final int PATTERN_SLOTS_PER_PAGE = 36;

    private final MatterMassPatternProviderLogic logic = new MatterMassPatternProviderLogic(
            this.getMainNode(), this,
            ChexsonsaeutilsCompatibilityConfig.maxCustomPatternPages() * PATTERN_SLOTS_PER_PAGE);

    /** 已解锁样板页数：默认 1，范围 [1, maxCustomPatternPages()]。 */
    private int pages = 1;
    /** 放置者 UUID（玩家模式交付目标）；未绑定时玩家模式阻塞。 */
    @Nullable
    private UUID ownerUuid;
    /** 产物返回目标模式。 */
    private ReturnMode returnMode = ReturnMode.NETWORK;

    public MatterMassPatternProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(ChexsonsaeutilsContent.MATTER_MASS_PATTERN_PROVIDER_BLOCK_ENTITY.get(), pos, blockState);
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.0);
    }

    /** 放置时绑定放置者（玩家模式交付目标）。 */
    public void setOwner(@Nullable LivingEntity placer) {
        if (placer != null) {
            this.ownerUuid = placer.getUUID();
            saveChanges();
        }
    }

    @Override
    @Nullable
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    @Override
    public ReturnMode getReturnMode() {
        return returnMode;
    }

    public void setReturnMode(ReturnMode mode) {
        this.returnMode = mode;
        saveChanges();
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        logic.writeToNBT(data, registries);
        data.putInt(NBT_PAGES, pages);
        if (ownerUuid != null) {
            data.putUUID(NBT_OWNER_UUID, ownerUuid);
        }
        data.putString(NBT_RETURN_MODE, returnMode.name());
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        logic.readFromNBT(data, registries);
        this.pages = clampPages(data.getInt(NBT_PAGES));
        this.ownerUuid = data.hasUUID(NBT_OWNER_UUID) ? data.getUUID(NBT_OWNER_UUID) : null;
        ReturnMode parsed;
        try {
            parsed = ReturnMode.valueOf(data.getString(NBT_RETURN_MODE));
        } catch (IllegalArgumentException e) {
            parsed = ReturnMode.NETWORK;
        }
        this.returnMode = parsed;
    }

    @Override
    public void onReady() {
        super.onReady();
        this.logic.updatePatterns();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        this.logic.onMainNodeStateChanged();
    }

    @Override
    public void addAdditionalDrops(net.minecraft.world.level.Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        this.logic.addDrops(drops);
    }

    @Override
    public void clearContent() {
        super.clearContent();
        this.logic.clearContent();
    }

    @Override
    public int getPages() {
        return pages;
    }

    @Override
    public void setPages(int pages) {
        this.pages = clampPages(pages);
        saveChanges();
    }

    private int clampPages(int rawPages) {
        int max = ChexsonsaeutilsCompatibilityConfig.maxCustomPatternPages();
        int clamped = Math.max(1, Math.min(rawPages, max));
        if (clamped != rawPages) {
            LOGGER.warn("物质团供应器样板页数截断：old={}, new={}, maxPages={}", rawPages, clamped, max);
        }
        return clamped;
    }

    @Override
    public MatterMassPatternProviderLogic getLogic() {
        return logic;
    }

    @Override
    public BlockEntity getBlockEntity() {
        return this;
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(ChexsonsaeutilsContent.MATTER_MASS_PATTERN_PROVIDER_ITEM.get());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(ChexsonsaeutilsContent.MATTER_MASS_PATTERN_PROVIDER_ITEM.get());
    }

    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        return EnumSet.allOf(Direction.class);
    }
}
