package git.chexson.chexsonsaeutils.bootstrap.mattermass;

import git.chexson.chexsonsaeutils.menu.mattermassprovider.MatterMassPatternProviderMenu;
import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** 质能样板供应器菜单的注册装配，将菜单工厂绑定到注册类型。 */
public final class MatterMassPatternProviderMenuRegistration {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, ModIds.MODID);

    public static final Supplier<MenuType<MatterMassPatternProviderMenu>> MATTER_MASS_PATTERN_PROVIDER_MENU =
            MENU_TYPES.register("matter_mass_pattern_provider", () -> MatterMassPatternProviderMenu.TYPE);

    private MatterMassPatternProviderMenuRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);
    }
}
