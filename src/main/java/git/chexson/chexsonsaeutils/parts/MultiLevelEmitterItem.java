package git.chexson.chexsonsaeutils.parts;

import appeng.api.parts.PartModels;
import appeng.items.parts.PartItem;
import appeng.items.parts.PartModelsHelper;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;

/**
 * Multi-Level Emitter Item - 可以监控多个物品并设置逻辑关系的发射器
 */
public class MultiLevelEmitterItem extends PartItem<MultiLevelEmitterPart> {
    
    public static RegistryObject<Item> ITEM;
    
    public MultiLevelEmitterItem(Item.Properties properties) {
        super(properties, MultiLevelEmitterPart.class, (partItem) -> new MultiLevelEmitterPart((PartItem<?>) partItem));
    }
    
    /**
     * 便捷的注册方法
     * 注册 Multi-Level Emitter 物品到游戏中，并注册 Part 模型
     */
    public static void register() {
        if (ITEM != null) {
            return; // 已经注册过了
        }
        
        // 注册 Part 模型（使用 PartModelsHelper 自动扫描 @PartModels 注解的字段）
        PartModels.registerModels(PartModelsHelper.createModels(MultiLevelEmitterPart.class));
        
        // 注册物品
        ITEM = Chexsonsaeutils.ITEMS.register("multi_level_emitter", 
            () -> new MultiLevelEmitterItem(new Item.Properties()));

    }

}

