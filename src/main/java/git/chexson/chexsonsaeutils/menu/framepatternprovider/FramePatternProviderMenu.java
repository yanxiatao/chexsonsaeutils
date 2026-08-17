package git.chexson.chexsonsaeutils.menu.framepatternprovider;

import appeng.menu.SlotSemantics;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import java.util.Objects;

/**
 * 框架样板供应器菜单。
 * <p>
 * 槽位构成：36 个样板槽（ENCODED_PATTERN，仅可放入 AE2 样板物品）、9 格返回库存（STORAGE）、
 * 升级卡槽（由 {@link UpgradeableMenu} 自动添加）。样板推送逻辑属于阶段 3，本菜单只负责存取。
 * <p>
 * 打开方式：由 {@link git.chexson.chexsonsaeutils.block.framepatternprovider.FramePatternProviderBlock}
 * 在潜行右击之外的交互路径（wrench 或瞄准边框）调用 MenuOpener 打开。
 */
public class FramePatternProviderMenu extends UpgradeableMenu<FramePatternProviderBlockEntity> {

    public static final MenuType<FramePatternProviderMenu> TYPE = MenuTypeBuilder
            .create(FramePatternProviderMenu::new, FramePatternProviderBlockEntity.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":frame_pattern_provider")
            ));

    public FramePatternProviderMenu(int id, Inventory playerInventory, FramePatternProviderBlockEntity host) {
        super(TYPE, id, playerInventory, host);
    }

    @Override
    protected void setupInventorySlots() {
        var patternInventory = getHost().getTerminalPatternInventory();
        for (int slot = 0; slot < patternInventory.size(); slot++) {
            addSlot(
                    new RestrictedInputSlot(
                            RestrictedInputSlot.PlacableItemType.PROVIDER_PATTERN,
                            patternInventory,
                            slot
                    ),
                    SlotSemantics.ENCODED_PATTERN
            );
        }
        var returnInventory = getHost().getReturnInventory();
        for (int slot = 0; slot < returnInventory.size(); slot++) {
            addSlot(new AppEngSlot(returnInventory, slot), SlotSemantics.STORAGE);
        }
    }
}