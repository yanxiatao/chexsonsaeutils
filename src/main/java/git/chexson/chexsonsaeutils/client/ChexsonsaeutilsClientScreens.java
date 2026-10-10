package git.chexson.chexsonsaeutils.client;

import appeng.client.gui.style.StyleManager;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.bootstrap.mattermass.MatterMassPatternProviderMenuRegistration;
import git.chexson.chexsonsaeutils.client.gui.custompatternencoder.CustomPatternEncoderScreen;
import git.chexson.chexsonsaeutils.client.gui.custompatternprovider.CustomPatternProviderScreen;
import git.chexson.chexsonsaeutils.client.gui.custompatternupgrade.CustomPatternUpgradeScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.AEDirectProcessingMachineScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.ExtremeAssemblerScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.MultiLevelEmitterRuntimeScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.ParallelCraftingCPUScreen;
import git.chexson.chexsonsaeutils.client.gui.mattermassprovider.MatterMassPatternProviderScreen;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassRegistration;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.slf4j.Logger;

/**
 * 客户端菜单屏幕装配出口。
 * 公共内容注册不应加载客户端屏幕，因此由客户端菜单注册事件统一建立屏幕和交互绑定。
 */
public final class ChexsonsaeutilsClientScreens {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ChexsonsaeutilsClientScreens() {
    }

    /** 在菜单类型注册完成后的客户端事件中，按既定顺序绑定屏幕和发射器阈值提交行为。 */
    public static void register(RegisterMenuScreensEvent event) {
        event.register(git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.MENU.get(),
                git.chexson.chexsonsaeutils.client.gui.terminal.BigIntCraftingTerminalScreen::new);
        event.register(git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.WIDE_CONFIRM.get(),
                git.chexson.chexsonsaeutils.client.gui.crafting.WideAmountCraftConfirmScreen::new);
        registerMultiLevelEmitterClientBindings();
        event.register(ChexsonsaeutilsContent.SIMULATION_AMOUNT_MENU.get(),
                git.chexson.chexsonsaeutils.client.gui.playersimulation.SimulationAmountScreen::new);
        event.register(ChexsonsaeutilsContent.PLAYER_SIMULATION_PLANE_MENU.get(),
                git.chexson.chexsonsaeutils.client.gui.playersimulation.PlayerSimulationScreen::new);
        event.register(ChexsonsaeutilsContent.EXTREME_ASSEMBLER_MENU.get(), ExtremeAssemblerScreen::new);
        event.register(ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_MENU.get(),
                AEDirectProcessingMachineScreen::new);
        event.<ParallelCraftingCPUMenu, ParallelCraftingCPUScreen>register(
                ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_CPU_MENU.get(),
                (menu, playerInventory, title) ->
                new ParallelCraftingCPUScreen(
                        menu,
                        playerInventory,
                        title,
                        StyleManager.loadStyleDoc("/screens/chexson_parallel_cpu.json")
                )
        );
        event.register(ChexsonsaeutilsContent.MULTI_LEVEL_EMITTER_MENU.get(), MultiLevelEmitterRuntimeScreen::new);
        event.register(ChexsonsaeutilsContent.CUSTOM_PATTERN_ENCODER_MENU.get(), CustomPatternEncoderScreen::new);
        event.register(ChexsonsaeutilsContent.CUSTOM_PATTERN_UPGRADE_MENU.get(), CustomPatternUpgradeScreen::new);
        event.register(ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_MENU.get(), CustomPatternProviderScreen::new);
        event.register(MatterMassPatternProviderMenuRegistration.MATTER_MASS_PATTERN_PROVIDER_MENU.get(),
                MatterMassPatternProviderScreen::new);
        event.<appeng.menu.me.common.MEStorageMenu,
                appeng.client.gui.me.common.MEStorageScreen<appeng.menu.me.common.MEStorageMenu>>register(
                        MatterMassRegistration.MATTER_MASS_VIEW_MENU.get(),
                        (menu, inv, title) -> new appeng.client.gui.me.common.MEStorageScreen<>(menu, inv, title,
                                StyleManager.loadStyleDoc("/screens/terminals/portable_item_cell.json")));
    }

    private static void registerMultiLevelEmitterClientBindings() {
        MultiLevelEmitterScreen.registerClientBindings(
                ChexsonsaeutilsContent.MULTI_LEVEL_EMITTER_MENU.get(),
                (menu, slotIndex, threshold, maxValue) -> menu.commitThreshold(slotIndex, threshold, maxValue)
        );
        LOGGER.info(
                "Registered MultiLevelEmitter client bindings: screenKey={}, clientBindingsReady={}",
                MultiLevelEmitterScreen.registrationKey(),
                MultiLevelEmitterScreen.hasClientBindingsRegistered()
        );
    }
}
