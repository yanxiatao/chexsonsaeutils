package git.chexson.chexsonsaeutils.bootstrap.terminal;
import appeng.items.parts.PartItem;
import git.chexson.chexsonsaeutils.parts.terminal.BigIntCraftingTerminalPart;
import git.chexson.chexsonsaeutils.menu.terminal.BigIntCraftingTerminalMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
/** 面板物品、菜单及模型注册装配。 */
public final class BigIntTerminalRegistration {
    /** 终端物品注册表，供真实注册契约验证与装配调用。 */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("chexsonsaeutils");
    /** 终端菜单注册表，与菜单自身 TYPE 保持同一实例。 */
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "chexsonsaeutils");
    public static final Supplier<Item> ITEM = ITEMS.register("bigint_crafting_terminal", () ->
            new PartItem<>(new Item.Properties(), BigIntCraftingTerminalPart.class, BigIntCraftingTerminalPart::new));
    public static final Supplier<MenuType<BigIntCraftingTerminalMenu>> MENU = MENUS.register("bigint_crafting_terminal",
            () -> BigIntCraftingTerminalMenu.TYPE);
    public static final Supplier<MenuType<git.chexson.chexsonsaeutils.menu.crafting.WideCraftConfirmMenu>> WIDE_CONFIRM =
            MENUS.register("wide_craft_confirm", () -> git.chexson.chexsonsaeutils.menu.crafting.WideCraftConfirmMenu.TYPE);
    private BigIntTerminalRegistration() {}
    public static void register(IEventBus bus) { ITEMS.register(bus); MENUS.register(bus); }
    public static void bootstrap() {
        git.chexson.chexsonsaeutils.network.crafting.WideCraftRequestPayload.bind(git.chexson.chexsonsaeutils.menu.crafting.WideCraftRequestHandler::handle);
        git.chexson.chexsonsaeutils.crafting.request.CraftingRequestCompatibilityAccess.bind(new git.chexson.chexsonsaeutils.integration.craftingrequest.CraftingRequestCompatibilityImpl());
        appeng.api.parts.PartModels.registerModels(BigIntCraftingTerminalPart.MODELS);
        git.chexson.chexsonsaeutils.terminal.storage.TerminalInventoryAccess.bind(TerminalStorageAssembly.reader());
    }
}
