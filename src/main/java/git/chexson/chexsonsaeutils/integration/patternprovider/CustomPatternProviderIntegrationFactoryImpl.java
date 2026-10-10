package git.chexson.chexsonsaeutils.integration.patternprovider;

import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.helpers.patternprovider.PatternProviderLogic;
import git.chexson.chexsonsaeutils.integration.CustomPatternEnergyInjector;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderIntegration;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderIntegrationFactory;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;
import git.chexson.chexsonsaeutils.integration.appflux.energy.AppFluxEnergyInjectorImpl;
import git.chexson.chexsonsaeutils.integration.extendedae_plus.wireless.EapWirelessBridgeHelper;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/** 可选 AppFlux/EAP 装配；第三方类型只在对应集成分支执行时加载。 */
public final class CustomPatternProviderIntegrationFactoryImpl implements CustomPatternProviderIntegrationFactory {
    @Override
    public CustomPatternProviderIntegration create(CustomPatternProviderLogicHost host,
            IManagedGridNode mainNode, IActionSource source) {
        var mods = ModList.get();
        var energy = mods.isLoaded("appflux") ? AppFluxEnergyInjectorImpl.create(host, mainNode, source) : null;
        return new IntegrationImpl(energy, mods.isLoaded("extendedae_plus"));
    }

    private record IntegrationImpl(@Nullable CustomPatternEnergyInjector energyInjector,
            boolean wirelessInstalled) implements CustomPatternProviderIntegration {
        @Override
        public void handleDelayedInit(PatternProviderLogic logic) {
            if (wirelessInstalled) {
                EapWirelessBridgeHelper.handleDelayedInit(logic);
            }
        }
        @Override
        public boolean updateWirelessLink(PatternProviderLogic logic) {
            return wirelessInstalled && EapWirelessBridgeHelper.updateWirelessLink(logic);
        }
    }
}
