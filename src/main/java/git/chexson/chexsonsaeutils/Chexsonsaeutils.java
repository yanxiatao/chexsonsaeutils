package git.chexson.chexsonsaeutils;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import git.chexson.chexsonsaeutils.parts.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.MultiLevelEmitterMenu;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Chexsonsaeutils.MODID)
public class Chexsonsaeutils {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "chexsonsaeutils";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "chexsonsaeutils" namespace
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "chexsonsaeutils" namespace
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "chexsonsaeutils" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "chexsonsaeutils:example_block", combining the namespace and path
    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)));
    // Creates a new BlockItem with the id "chexsonsaeutils:example_block", combining the namespace and path
    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> new BlockItem(EXAMPLE_BLOCK.get(), new Item.Properties()));

    // Creates a new food item with the id "chexsonsaeutils:example_id", nutrition 1 and saturation 2
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> new Item(new Item.Properties().food(new FoodProperties.Builder().alwaysEat().nutrition(1).saturationMod(2f).build())));

    // Creates a creative tab with the id "chexsonsaeutils:example_tab" for the example item, that is placed after the combat tab
    public static final RegistryObject<CreativeModeTab> chexsonsaeutils_tab = CREATIVE_MODE_TABS.register("chexsonsaeutils", () -> CreativeModeTab.builder().withTabsBefore(CreativeModeTabs.COMBAT).icon(() -> EXAMPLE_ITEM.get().getDefaultInstance()).displayItems((parameters, output) -> {
        output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
    }).build());

    public Chexsonsaeutils() {
    @SuppressWarnings("removal") IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the commonSetup method for modloading

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        //ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        
        // Register all AE2 parts using the convenient registration method
        // NOTE: This must be called AFTER ITEMS.register(modEventBus) to ensure items are properly registered
        registerAEParts(modEventBus);
    }
    
    /**
     * 便捷的 AE2 Part 注册方法
     * 统一注册所有 AE2 Part 物品和菜单
     * 使用示例：
     * registerAEPart("my_part", () -> new MyPartItem(new Item.Properties()), MyPartMenu.MENU_TYPE);
     */
    private void registerAEParts(IEventBus modEventBus) {
        // Register Multi-Level Emitter
        LOGGER.info("Registering Multi-Level Emitter Item...");
        MultiLevelEmitterItem.register();
        LOGGER.info("Multi-Level Emitter Item registered: {}", MultiLevelEmitterItem.ITEM);
        
        // Register Multi-Level Emitter Menu
        LOGGER.info("Registering Multi-Level Emitter Menu...");
        var menuTypes = MultiLevelEmitterMenu.getMenuTypes();
        menuTypes.register(modEventBus);
        LOGGER.info("Multi-Level Emitter Menu registered");
        
        // Register upgrades in CommonSetup event
        modEventBus.addListener(this::registerUpgrades);
        
        LOGGER.info("Registered AE2 parts: Multi-Level Emitter");
    }
    
    /**
     * 注册升级卡支持
     * 必须在 FMLCommonSetupEvent 中调用
     */
    private void registerUpgrades(FMLCommonSetupEvent event) {
        LOGGER.info("Registering upgrades for Multi-Level Emitter...");
        appeng.api.upgrades.Upgrades.add(appeng.core.definitions.AEItems.FUZZY_CARD, 
            MultiLevelEmitterItem.ITEM.get(), 1);
        appeng.api.upgrades.Upgrades.add(appeng.core.definitions.AEItems.CRAFTING_CARD, 
            MultiLevelEmitterItem.ITEM.get(), 1);
        LOGGER.info("Upgrades registered for Multi-Level Emitter");
    }


    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
        if (event.getTabKey() == chexsonsaeutils_tab.getKey() && MultiLevelEmitterItem.ITEM != null) {
            event.accept(MultiLevelEmitterItem.ITEM.get());
        }
    }


    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {

            // Register Multi-Level Emitter Screen
            try {
                var menuType = git.chexson.chexsonsaeutils.parts.MultiLevelEmitterMenu.MENU_TYPE.get();

                // 注册 Screen（使用 AE2 标准样式系统）
                net.minecraft.client.gui.screens.MenuScreens.register(
                    menuType,
                    (menu, playerInv, title) -> {
                        LOGGER.info("Creating Multi-Level Emitter Screen");
                        // 使用自定义的 loadStyleDoc 方法加载样式文件
                        var style = loadStyleDoc();
                        return new git.chexson.chexsonsaeutils.parts.MultiLevelEmitterScreen(menu, playerInv, title, style);
                    }
                );
            } catch (Exception ignored) {
            }
        }

        /**
         * 自定义样式文件加载方法
         * 支持加载非 ae2 命名空间的样式文件
         * 使用 AE2 的分层合并逻辑
         *
         * @return 加载的 ScreenStyle 对象
         */
        private static appeng.client.gui.style.ScreenStyle loadStyleDoc() {
            try {
                LOGGER.info("Loading style from: {}", "/screens/multi_level_emitter.json");
                
                // 使用 AE2 的分层合并逻辑
                com.google.gson.JsonObject document = loadMergedJsonTree("/screens/multi_level_emitter.json", new java.util.HashSet<>());
                
                // 创建 ScreenStyle 对象
                var style = appeng.client.gui.style.ScreenStyle.GSON.fromJson(document, appeng.client.gui.style.ScreenStyle.class);
                LOGGER.info("Successfully loaded style from: {}", "/screens/multi_level_emitter.json");
                return style;
                
            } catch (Exception e) {
                LOGGER.error("Failed to load style from: {}", "/screens/multi_level_emitter.json", e);
                throw new RuntimeException("Failed to load style: " + "/screens/multi_level_emitter.json", e);
            }
        }

        /**
         * 加载合并的 JSON 树（使用 AE2 的分层合并逻辑）
         * 
         * @param path 样式文件路径
         * @param loadedFiles 已加载的文件集合（用于检测循环引用）
         * @return 合并后的 JSON 对象
         */
        private static com.google.gson.JsonObject loadMergedJsonTree(String path, java.util.Set<String> loadedFiles) throws Exception {
            // 标准化路径
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            
            // 检测循环引用
            if (!loadedFiles.add(path)) {
                throw new IllegalStateException("Recursive style includes: " + loadedFiles);
            }

            // 解析资源位置
            net.minecraft.resources.ResourceLocation resourceLocation = resolveResourceLocation(path);

            LOGGER.debug("Loading JSON from: {}", resourceLocation);

            // 读取 JSON 文件
            var resource = Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(resourceLocation);
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.io.Reader reader = new java.io.InputStreamReader(resource.open());
            com.google.gson.JsonObject document = gson.fromJson(reader, com.google.gson.JsonObject.class);

            // 处理 includes
            if (document.has("includes")) {
                var includes = document.getAsJsonArray("includes");
                java.util.List<com.google.gson.JsonObject> layers = new java.util.ArrayList<>();
                
                // 获取当前文件的基础路径（用于解析相对路径）
                String basePath = getBasePath(path);
                
                // 递归加载所有 includes
                for (var include : includes) {
                    String includePath = include.getAsString();
                    LOGGER.debug("Processing include: {} (base: {})", includePath, basePath);
                    
                    // 解析 include 路径
                    String resolvedPath;
                    if (includePath.startsWith("ae2:")) {
                        // AE2 绝对路径
                        resolvedPath = includePath;
                    } else if (includePath.startsWith("/")) {
                        // 绝对路径
                        resolvedPath = includePath;
                    } else {
                        // 相对路径，相对于当前文件所在目录
                        resolvedPath = basePath + includePath;
                    }
                    
                    layers.add(loadMergedJsonTree(resolvedPath, new java.util.HashSet<>(loadedFiles)));
                }
                
                // 添加当前文档
                layers.add(document);
                
                // 合并所有 layers
                document = combineLayers(layers);
            }

            return document;
        }

        /**
         * 解析资源位置
         * 
         * @param path 文件路径
         * @return 资源位置
         */
        private static net.minecraft.resources.ResourceLocation resolveResourceLocation(String path) {
            // 标准化路径
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            
            if (path.startsWith("/ae2:")) {
                // AE2 路径
                String normalizedPath = path.substring(1); // 去掉开头的 "/"
                if (!normalizedPath.contains("/screens/")) {
                    String subPath = normalizedPath.substring(4); // 去掉 "ae2:"
                    if (!subPath.startsWith("screens/")) {
                        subPath = "screens/" + subPath;
                    }
                    return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ae2", subPath);
                } else {
                    return net.minecraft.resources.ResourceLocation.parse(normalizedPath);
                }
            } else {
                // 当前模组路径
                return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, path.substring(1));
            }
        }

        /**
         * 获取基础路径（用于解析相对路径）
         * 
         * @param path 文件路径
         * @return 基础路径
         */
        private static String getBasePath(String path) {
            // 标准化路径
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            
            // 找到最后一个斜杠
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1) {
                return "/";
            }
            return path.substring(0, lastSlash + 1);
        }

        /**
         * 合并多个 JSON 层（使用 AE2 的合并逻辑）
         * 
         * @param layers JSON 层列表
         * @return 合并后的 JSON 对象
         */
        private static com.google.gson.JsonObject combineLayers(java.util.List<com.google.gson.JsonObject> layers) {
            com.google.gson.JsonObject result = new com.google.gson.JsonObject();

            // 首先简单地复制所有属性（后面的覆盖前面的）
            for (com.google.gson.JsonObject layer : layers) {
                for (var entry : layer.entrySet()) {
                    result.add(entry.getKey(), entry.getValue());
                }
            }

            // 特殊处理某些键，通过合并它们的属性而不是覆盖
            mergeObjectKeys("slots", layers, result);
            mergeObjectKeys("text", layers, result);
            mergeObjectKeys("palette", layers, result);
            mergeObjectKeys("images", layers, result);
            mergeObjectKeys("terminalStyle", layers, result);
            mergeObjectKeys("widgets", layers, result);

            return result;
        }

        /**
         * 合并单个对象属性（使用 AE2 的合并逻辑）
         * 
         * @param propertyName 属性名
         * @param layers JSON 层列表
         * @param target 目标 JSON 对象
         */
        private static void mergeObjectKeys(String propertyName, java.util.List<com.google.gson.JsonObject> layers, 
                                           com.google.gson.JsonObject target) {
            com.google.gson.JsonObject mergedObject = null;
            for (com.google.gson.JsonObject layer : layers) {
                var layerEl = layer.get(propertyName);
                if (layerEl != null && layerEl.isJsonObject()) {
                    var layerObj = layerEl.getAsJsonObject();

                    if (mergedObject == null) {
                        mergedObject = new com.google.gson.JsonObject();
                    }
                    for (var entry : layerObj.entrySet()) {
                        mergedObject.add(entry.getKey(), entry.getValue());
                    }
                }
            }

            if (mergedObject != null) {
                target.add(propertyName, mergedObject);
            }
        }

        /**
         * 注册物品模型绑定
         * 使用 ModelEvent.RegisterAdditionalEvent 来注册自定义模型
         */
        @SubscribeEvent
        public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
            try {
                // 注册多物品发信器的模型
                if (MultiLevelEmitterItem.ITEM != null && MultiLevelEmitterItem.ITEM.getId() != null) {
                    String modelPath = MODID + ":item/" + MultiLevelEmitterItem.ITEM.getId().getPath();
                    event.register(net.minecraft.resources.ResourceLocation.parse(modelPath));
                    LOGGER.info("Registered additional model: {}", modelPath);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to register additional model for Multi-Level Emitter: {}", e.getMessage());
            }
        }

        /**
         * 注册物品模型和块状物品模型
         * 使用 ModelEvent.ModifyBakingResult 来自定义模型烘焙
         */
        @SubscribeEvent
        public static void onModelBaking(ModelEvent.ModifyBakingResult event) {
            try {
                // 为多物品发信器注册基础模型绑定
                if (MultiLevelEmitterItem.ITEM != null) {
                    ResourceLocation itemLocation = MultiLevelEmitterItem.ITEM.getId();
                    LOGGER.debug("Processing model baking for item: {}", itemLocation);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to process model baking: {}", e.getMessage());
            }
        }

        /**
         * 注册几何模型加载器
         * 使用 ModelEvent.RegisterGeometryLoaders 来注册自定义几何模型
         */
        @SubscribeEvent
        public static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
            try {
                // 可以在这里注册自定义几何模型加载器
                LOGGER.debug("Geometry loaders registration event");
            } catch (Exception e) {
                LOGGER.warn("Failed to register geometry loaders: {}", e.getMessage());
            }
        }

    }
}
