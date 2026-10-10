package git.chexson.chexsonsaeutils.parts.terminal;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.parts.PartModel;
import appeng.parts.reporting.CraftingTerminalPart;
import appeng.items.parts.PartModels;
import git.chexson.chexsonsaeutils.menu.terminal.BigIntCraftingTerminalMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import java.util.List;
/** 精确数量合成终端部件，合成栏持久化和掉落行为继承 AE2。 */
public final class BigIntCraftingTerminalPart extends CraftingTerminalPart {
    public static final List<ResourceLocation> MODELS = List.of(
            ResourceLocation.parse("chexsonsaeutils:part/bigint_crafting_terminal_off"),
            ResourceLocation.parse("chexsonsaeutils:part/bigint_crafting_terminal_on"));
    @PartModels public static final IPartModel OFF = new PartModel(MODEL_BASE, MODELS.getFirst(), MODEL_STATUS_OFF);
    @PartModels public static final IPartModel ON = new PartModel(MODEL_BASE, MODELS.get(1), MODEL_STATUS_ON);
    @PartModels public static final IPartModel CHANNEL = new PartModel(MODEL_BASE, MODELS.get(1), MODEL_STATUS_HAS_CHANNEL);
    public BigIntCraftingTerminalPart(IPartItem<?> item) { super(item); }
    @Override public MenuType<?> getMenuType(Player player) { return BigIntCraftingTerminalMenu.TYPE; }
    @Override public IPartModel getStaticModels() { return selectModel(OFF, ON, CHANNEL); }
}
