package git.chexson.chexsonsaeutils.registration;

import appeng.block.AEBaseEntityBlock;
import git.chexson.chexsonsaeutils.bootstrap.mattermass.MatterMassPatternProviderMenuRegistration;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderBlockEntityRegistration;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderPartRegistration;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderRegistration;
import git.chexson.chexsonsaeutils.bootstrap.patterntransmitter.PatternTransmitterRegistration;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternRegistration;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingRecipeRegistration;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternRegistration;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassRegistration;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassViewMenuHost;
import git.chexson.chexsonsaeutils.menu.custompatternencoder.CustomPatternEncoderMenu;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeMenu;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ExtremeAssemblerMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.menu.mattermassprovider.MatterMassPatternProviderMenu;
import git.chexson.chexsonsaeutils.modid.ModIds;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredRegister;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册与配置的行为场景；由类别测试统一调用。 */
public final class RegistrationScenarios {
    private RegistrationScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.registration",
                () -> RegistrationRuntimeContractCases.verify(temporaryRoot)
        );
    }

    /** RegistrationRuntimeContractCases 的独立场景夹具，按类别统一执行。 */
    public static final class RegistrationRuntimeContractCases {
        private record RegistryContract(Registry<?> registry, List<String> paths) {
            RegistryContract {
                if (paths.contains("self_powered_controller") && ModList.get().isLoaded("ae2lt")) {
                    var expanded = new java.util.ArrayList<>(paths);
                    expanded.addAll(1, List.of("self_powered_overloaded_controller",
                            "self_powered_wireless_overloaded_controller", "self_powered_advanced_wireless_overloaded_controller"));
                    paths = List.copyOf(expanded);
                }
            }
        }

        /** 八类真实注册值；顺序按当前各注册器的挂接顺序及其条目顺序展开。 */
        private static final List<RegistryContract> CONTRACTS = List.of(
                new RegistryContract(BuiltInRegistries.BLOCK, List.of(
                        "self_powered_controller",
                        "pattern_transmitter",
                        "extreme_assembler", "ae_direct_processing_machine", "ae2_parallel_cpu_tool",
                        "auto_item_gen", "matter_mass_pattern_provider", "custom_pattern_provider")),
                new RegistryContract(BuiltInRegistries.ITEM, List.of(
                        "self_powered_controller",
                        "pattern_transmitter", "remote_pattern",
                        "extreme_assembler", "ae_direct_processing_machine", "ae2_parallel_cpu_tool",
                        "multi_level_emitter", "player_simulation_plane", "infinity_cell", "auto_item_gen", "matter_mass_pattern_provider",
                        "custom_pattern", "custom_pattern_provider_part", "custom_pattern_provider",
                        "matter_mass_pattern", "matter_mass", "bigint_crafting_terminal")),
                new RegistryContract(BuiltInRegistries.BLOCK_ENTITY_TYPE, List.of(
                        "pattern_transmitter",
                        "auto_item_gen", "extreme_assembler", "ae_direct_processing_machine",
                        "ae2_parallel_cpu_tool", "matter_mass_pattern_provider", "custom_pattern_provider")),
                new RegistryContract(BuiltInRegistries.MENU, List.of(
                        "pattern_transmitter",
                        "simulation_amount", "player_simulation_plane",
                        "extreme_assembler", "ae_direct_processing_machine", "ae2_parallel_cpu_tool_cpu",
                        "custom_pattern_provider", "custom_pattern_encoder", "custom_pattern_upgrade",
                        "multi_level_emitter_menu", "matter_mass_pattern_provider", "matter_mass_view", "bigint_crafting_terminal", "wide_craft_confirm")),
                new RegistryContract(BuiltInRegistries.CREATIVE_MODE_TAB, List.of("chexsonsaeutils")),
                new RegistryContract(BuiltInRegistries.DATA_COMPONENT_TYPE, List.of(
                        "encoded_custom_pattern", "custom_pattern_pages",
                        "encoded_matter_mass_pattern", "matter_mass_uuid")),
                new RegistryContract(BuiltInRegistries.RECIPE_TYPE, List.of("direct_processing_mock", "direct_processing")),
                new RegistryContract(BuiltInRegistries.RECIPE_SERIALIZER, List.of("direct_processing_mock", "direct_processing"))
        );

        void registeredIdsAndBoundHandlesMatchActualRegistryValues() {
            requireLoadedMod();
            Map<ResourceLocation, Set<ResourceLocation>> declared = new LinkedHashMap<>();
            for (DeferredRegister<?> registration : registrations()) {
                ResourceLocation kind = registration.getRegistryName();
                Registry<?> runtime = BuiltInRegistries.REGISTRY.get(kind);
                assertNotNull(runtime, "运行注册表缺失：" + kind);
                Set<ResourceLocation> ids = declared.computeIfAbsent(kind, ignored -> new LinkedHashSet<>());
                for (var holder : registration.getEntries()) {
                    assertTrue(ids.add(holder.getId()), "重复登记：" + kind + "/" + holder.getId());
                    assertTrue(holder.isBound(), "注册事件未绑定把手：" + kind + "/" + holder.getId());
                    assertSame(holder.get(), runtime.get(holder.getId()),
                            "把手与实际注册值不一致：" + kind + "/" + holder.getId());
                }
            }
            Set<ResourceLocation> expectedKinds = new LinkedHashSet<>();
            for (RegistryContract contract : CONTRACTS) {
                ResourceLocation kind = contract.registry().key().location();
                expectedKinds.add(kind);
                Set<ResourceLocation> expected = new LinkedHashSet<>(ids(contract.paths()));
                assertEquals(expected, new LinkedHashSet<>(registeredIds(contract.registry())),
                        "实际登记集合发生变化：" + kind);
                assertEquals(expected, declared.get(kind), "注册器清单未覆盖实际登记：" + kind);
            }
            assertEquals(expectedKinds, declared.keySet(), "新增或遗漏的注册种类必须进入运行契约");
        }

        void registrationOrderIsPreservedWithinEachRegistryKind() {
            requireLoadedMod();
            for (RegistryContract contract : CONTRACTS) {
                assertEquals(ids(contract.paths()), registeredIds(contract.registry()),
                        "同种注册表中的实际登记顺序发生变化：" + contract.registry().key().location());
            }
        }

        void everyRegisteredBlockItemPlacesItsOwnRegisteredBlock() {
            requireLoadedMod();
            for (String path : CONTRACTS.getFirst().paths()) {
                ResourceLocation id = id(path);
                var block = BuiltInRegistries.BLOCK.get(id);
                BlockItem item = assertInstanceOf(BlockItem.class, BuiltInRegistries.ITEM.get(id),
                        "方块对应物品不是 BlockItem：" + id);
                assertSame(block, item.getBlock(), "BlockItem 绑定了别的方块：" + id);
            }
        }

        void everyBlockEntityTypeIsBoundToItsOwnAe2Block() {
            requireLoadedMod();
            for (String path : CONTRACTS.getFirst().paths()) {
                ResourceLocation id = id(path);
                AEBaseEntityBlock<?> block = assertInstanceOf(AEBaseEntityBlock.class,
                        BuiltInRegistries.BLOCK.get(id), "注册方块不是 AE2 实体方块：" + id);
                var typeId = path.startsWith("self_powered_")
                        ? ResourceLocation.fromNamespaceAndPath(path.equals("self_powered_controller") ? "ae2" : "ae2lt",
                                path.substring("self_powered_".length())) : id;
                var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(typeId);
                assertNotNull(type, "方块实体类型未登记：" + id);
                assertSame(type, block.getBlockEntityType(), "AE2 bootstrap 未正确绑定实体类型：" + id);
                assertTrue(type.isValid(block.defaultBlockState()), "实体类型不接受自身方块：" + id);
            }
        }

        void registeredMenusAreTheTypesUsedByTheirMenuImplementations() {
            requireLoadedMod();
            assertMenu("extreme_assembler", ChexsonsaeutilsContent.EXTREME_ASSEMBLER_MENU, ExtremeAssemblerMenu.TYPE);
            assertMenu("pattern_transmitter", PatternTransmitterRegistration.MENU,
                    git.chexson.chexsonsaeutils.menu.patterntransmitter.PatternTransmitterMenu.TYPE);
            assertMenu("ae_direct_processing_machine", ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_MENU,
                    AEDirectProcessingMachineMenu.TYPE);
            assertMenu("ae2_parallel_cpu_tool_cpu", ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_CPU_MENU,
                    ParallelCraftingCPUMenu.TYPE);
            assertMenu("custom_pattern_provider", ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_MENU,
                    CustomPatternProviderMenu.TYPE);
            assertMenu("custom_pattern_encoder", ChexsonsaeutilsContent.CUSTOM_PATTERN_ENCODER_MENU,
                    CustomPatternEncoderMenu.TYPE);
            assertMenu("custom_pattern_upgrade", ChexsonsaeutilsContent.CUSTOM_PATTERN_UPGRADE_MENU,
                    CustomPatternUpgradeMenu.TYPE);
            assertTrue(MultiLevelEmitterMenu.hasRegisteredMenuBindings(), "发射器菜单 bootstrap 未完成");
            assertMenu("multi_level_emitter_menu", ChexsonsaeutilsContent.MULTI_LEVEL_EMITTER_MENU,
                    MultiLevelEmitterMenu.registeredMenuType());
            assertMenu("matter_mass_pattern_provider",
                    MatterMassPatternProviderMenuRegistration.MATTER_MASS_PATTERN_PROVIDER_MENU,
                    MatterMassPatternProviderMenu.TYPE);
            assertMenu("wide_craft_confirm",
                    git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.WIDE_CONFIRM,
                    git.chexson.chexsonsaeutils.menu.crafting.WideCraftConfirmMenu.TYPE);
            assertMenu("bigint_crafting_terminal",
                    git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.MENU,
                    git.chexson.chexsonsaeutils.menu.terminal.BigIntCraftingTerminalMenu.TYPE);
            assertMenu("matter_mass_view", MatterMassRegistration.MATTER_MASS_VIEW_MENU, MatterMassViewMenuHost.TYPE);
        }

        void bigintTerminalPartItemCreatesRegisteredCraftingTerminalPart() {
            requireLoadedMod();
            var item = assertInstanceOf(appeng.api.parts.IPartItem.class,
                    BuiltInRegistries.ITEM.get(id("bigint_crafting_terminal")));
            var part = assertInstanceOf(git.chexson.chexsonsaeutils.parts.terminal.BigIntCraftingTerminalPart.class,
                    item.createPart());
            assertSame(git.chexson.chexsonsaeutils.menu.terminal.BigIntCraftingTerminalMenu.TYPE, part.getMenuType(null));
        }

        /** 必须由既有 NeoForge 测试启动加载 mod；此处只验证前提，不自行注册或吞掉失败。 */
        private static void requireLoadedMod() {
            ModList mods = ModList.get();
            assertNotNull(mods, "NeoForge ModList 未建立；测试必须使用已配置的 forgejunitdev 启动入口");
            assertTrue(mods.isLoaded(ModIds.MODID), "NeoForge testedMod 未加载：" + ModIds.MODID);
        }

        /** 注册器逐个列出，直接调用公开 API，避免用反射探查字段或从源码猜测把手。 */
        private static List<DeferredRegister<?>> registrations() {
            return List.of(
                    git.chexson.chexsonsaeutils.bootstrap.controller.SelfPoweredControllerRegistration.BLOCKS,
                    git.chexson.chexsonsaeutils.bootstrap.controller.SelfPoweredControllerRegistration.ITEMS,
                    PatternTransmitterRegistration.BLOCKS, PatternTransmitterRegistration.ITEMS,
                    PatternTransmitterRegistration.ENTITIES, PatternTransmitterRegistration.MENUS,
                    ChexsonsaeutilsContent.BLOCKS, ChexsonsaeutilsContent.ITEMS,
                    ChexsonsaeutilsContent.BLOCK_ENTITY_TYPES, ChexsonsaeutilsContent.MENU_TYPES,
                    DirectProcessingRecipeRegistration.RECIPE_TYPES, DirectProcessingRecipeRegistration.RECIPE_SERIALIZERS,
                    ChexsonsaeutilsContent.CREATIVE_MODE_TABS, ChexsonsaeutilsContent.DATA_COMPONENT_TYPES,
                    CustomPatternRegistration.ITEMS, CustomPatternRegistration.DATA_COMPONENT_TYPES,
                    CustomPatternProviderPartRegistration.ITEMS,
                    CustomPatternProviderRegistration.BLOCKS, CustomPatternProviderRegistration.ITEMS,
                    CustomPatternProviderBlockEntityRegistration.BLOCK_ENTITY_TYPES,
                    MatterMassPatternRegistration.ITEMS, MatterMassPatternRegistration.DATA_COMPONENT_TYPES,
                    MatterMassPatternProviderMenuRegistration.MENU_TYPES,
                    MatterMassRegistration.ITEMS, MatterMassRegistration.MENU_TYPES, MatterMassRegistration.DATA_COMPONENT_TYPES,
                    git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.ITEMS,
                    git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.MENUS
            );
        }

        /** 数值 ID 是注册完成后的顺序证据；仅筛本 mod，不依赖其他 mod 插入的具体数值。 */
        private static <T> List<ResourceLocation> registeredIds(Registry<T> registry) {
            return registry.keySet().stream()
                    .filter(id -> ModIds.MODID.equals(id.getNamespace()))
                    .sorted(Comparator.<ResourceLocation>comparingInt(id -> registry.getId(registry.get(id))))
                    .toList();
        }

        private static List<ResourceLocation> ids(List<String> paths) {
            return paths.stream().map(RegistrationRuntimeContractCases::id).toList();
        }

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath(ModIds.MODID, path);
        }

        private static void assertMenu(String path, Supplier<? extends MenuType<?>> holder, MenuType<?> expected) {
            assertSame(expected, holder.get(), "菜单把手与实现 TYPE 不一致：" + path);
            assertSame(expected, BuiltInRegistries.MENU.get(id(path)), "菜单实现 TYPE 未正确登记：" + path);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RegistrationRuntimeContractCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RegistrationRuntimeContractCases.registeredIdsAndBoundHandlesMatchActualRegistryValues", () -> new RegistrationRuntimeContractCases().registeredIdsAndBoundHandlesMatchActualRegistryValues()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RegistrationRuntimeContractCases.registrationOrderIsPreservedWithinEachRegistryKind", () -> new RegistrationRuntimeContractCases().registrationOrderIsPreservedWithinEachRegistryKind()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RegistrationRuntimeContractCases.everyRegisteredBlockItemPlacesItsOwnRegisteredBlock", () -> new RegistrationRuntimeContractCases().everyRegisteredBlockItemPlacesItsOwnRegisteredBlock()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RegistrationRuntimeContractCases.everyBlockEntityTypeIsBoundToItsOwnAe2Block", () -> new RegistrationRuntimeContractCases().everyBlockEntityTypeIsBoundToItsOwnAe2Block()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RegistrationRuntimeContractCases.registeredMenusAreTheTypesUsedByTheirMenuImplementations", () -> new RegistrationRuntimeContractCases().registeredMenusAreTheTypesUsedByTheirMenuImplementations()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RegistrationRuntimeContractCases.bigintTerminalPartItemCreatesRegisteredCraftingTerminalPart", () -> new RegistrationRuntimeContractCases().bigintTerminalPartItemCreatesRegisteredCraftingTerminalPart())
            );
        }
    }
}
