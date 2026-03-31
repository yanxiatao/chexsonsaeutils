package git.chexson.chexsonsaeutils;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.client.gui.implementations.MultiLevelEmitterRuntimeScreen;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.ProcessingPatternReplacementFeatureGate;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementDecoder;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

@Mod(Chexsonsaeutils.MODID)
public class Chexsonsaeutils {

    public static final String MODID = "chexsonsaeutils";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final Supplier<Item> MULTI_LEVEL_EMITTER_ITEM =
            ITEMS.register(MultiLevelEmitterItem.id(), MultiLevelEmitterItem::createItem);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final Supplier<MenuType<MultiLevelEmitterMenu.RuntimeMenu>> MULTI_LEVEL_EMITTER_MENU =
            MENU_TYPES.register(
                    MultiLevelEmitterMenu.registrationKey(),
                    () -> IMenuTypeExtension.create(MultiLevelEmitterMenu.RuntimeMenu::fromNetwork)
            );

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final Supplier<CreativeModeTab> CHEXSONSAEUTILS_TAB =
            CREATIVE_MODE_TABS.register("chexsonsaeutils", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.chexsonsaeutils"))
                    .icon(() -> new ItemStack(MULTI_LEVEL_EMITTER_ITEM.get()))
                    .displayItems((parameters, output) -> output.accept(MULTI_LEVEL_EMITTER_ITEM.get()))
                    .build());

    public Chexsonsaeutils(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ChexsonsaeutilsCompatibilityConfig.SPEC);
        ITEMS.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Chexsonsaeutils::registerMultiLevelEmitterBootstrap);
        if (ProcessingPatternReplacementFeatureGate.isEnabledAtStartup()) {
            event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);
        }
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
            Chexsonsaeutils.registerMultiLevelEmitterClientBindings();
            event.register(MULTI_LEVEL_EMITTER_MENU.get(), MultiLevelEmitterRuntimeScreen::new);
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
        Upgrades.add(AEItems.FUZZY_CARD, MULTI_LEVEL_EMITTER_ITEM.get(), 1);
        Upgrades.add(AEItems.CRAFTING_CARD, MULTI_LEVEL_EMITTER_ITEM.get(), 1);
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

    private static void registerProcessingPatternReplacementDecoder() {
        IPatternDetailsDecoder decoder = new ProcessingPatternReplacementDecoder();
        List<IPatternDetailsDecoder> decoders = PatternDetailsHelperAccessor.chexsonsaeutils$getDecoders();
        decoders.removeIf(existingDecoder -> existingDecoder.getClass() == decoder.getClass());
        decoders.add(0, decoder);
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
