package git.chexson.chexsonsaeutils;

import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.client.MultiLevelEmitterRuntimeScreen;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.parts.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.parts.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.MultiLevelEmitterScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(Chexsonsaeutils.MODID)
public class Chexsonsaeutils {

    public static final String MODID = "chexsonsaeutils";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final RegistryObject<Item> MULTI_LEVEL_EMITTER_ITEM =
            ITEMS.register(MultiLevelEmitterItem.id(), MultiLevelEmitterItem::createItem);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);
    public static final RegistryObject<MenuType<MultiLevelEmitterMenu.RuntimeMenu>> MULTI_LEVEL_EMITTER_MENU =
            MENU_TYPES.register(
                    MultiLevelEmitterMenu.registrationKey(),
                    () -> IForgeMenuType.create(MultiLevelEmitterMenu.RuntimeMenu::fromNetwork)
            );

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final RegistryObject<CreativeModeTab> CHEXSONSAEUTILS_TAB =
            CREATIVE_MODE_TABS.register("chexsonsaeutils", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.chexsonsaeutils"))
                    .icon(() -> new ItemStack(MULTI_LEVEL_EMITTER_ITEM.get()))
                    .displayItems((parameters, output) -> output.accept(MULTI_LEVEL_EMITTER_ITEM.get()))
                    .build());

    @SuppressWarnings("removal")
    public Chexsonsaeutils() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ChexsonsaeutilsCompatibilityConfig.SPEC);
        ITEMS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::onCommonSetup);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Chexsonsaeutils::registerMultiLevelEmitterBootstrap);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Server lifecycle anchor kept for future networked emitter behavior.
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                Chexsonsaeutils.registerMultiLevelEmitterClientBindings();
                MenuScreens.register(MULTI_LEVEL_EMITTER_MENU.get(), MultiLevelEmitterRuntimeScreen::new);
            });
        }
    }

    public static String emitterRegistryPath() {
        return MultiLevelEmitterItem.id();
    }

    public static String menuBindingKey() {
        return MultiLevelEmitterMenu.registrationKey();
    }

    public static String screenBindingKey() {
        return MultiLevelEmitterScreen.registrationKey();
    }

    public static String runtimePartType() {
        return MultiLevelEmitterRuntimePart.class.getSimpleName();
    }

    private static void registerMultiLevelEmitterBootstrap() {
        MultiLevelEmitterMenu.registerMenuBindings(
                MULTI_LEVEL_EMITTER_MENU::get,
                (inventory, networkData) -> MultiLevelEmitterRuntimePart.consumePublishedMenuRuntime()
        );
        LOGGER.info(
                "Registered MultiLevelEmitter content: itemId={}, runtimePart={}, menuKey={}, screenKey={}, menuBindingsReady={}",
                emitterRegistryPath(),
                runtimePartType(),
                menuBindingKey(),
                screenBindingKey(),
                MultiLevelEmitterMenu.hasRegisteredMenuBindings()
        );
    }

    private static void registerMultiLevelEmitterClientBindings() {
        MultiLevelEmitterScreen.registerClientBindings(
                MULTI_LEVEL_EMITTER_MENU.get(),
                (menu, slotIndex, threshold, maxValue) -> menu.commitThreshold(slotIndex, threshold, maxValue)
        );
        LOGGER.info(
                "Registered MultiLevelEmitter client bindings: screenKey={}, clientBindingsReady={}",
                screenBindingKey(),
                MultiLevelEmitterScreen.hasClientBindingsRegistered()
        );
    }
}
