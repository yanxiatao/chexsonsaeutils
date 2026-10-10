package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** 整合包的直连处理规则契约；核心逻辑只读取不可变快照，不依赖脚本引擎。 */
public interface DirectProcessingPackRules {
    /** 标记物品能否参与识别；禁用机器后保留实体库存，允许玩家取回。 */
    boolean allowsMachine(MachineIdentity identity);
    /** 类型能否在指定机器上执行；规则覆盖专用适配器、自动发现和旧导入。 */
    boolean allowsType(MachineIdentity identity, ResourceLocation recipeType);
    /** 导入权限独立于执行权限，禁止新导入不会删除已存配方。 */
    boolean allowsImport(MachineIdentity identity, ResourceLocation recipeType);
    /** 是否允许名称自动发现，避免整合包意外开放相似机器配方。 */
    boolean allowsAutomaticDiscovery(MachineIdentity identity);
    /** 自定义标记物品及显式机器类型的追加集合。 */
    List<ResourceLocation> addedTypes(MachineIdentity identity);
    /** 是否使用整合包指定的类型集合替代所有内建机器类型。 */
    boolean replacesTypes(MachineIdentity identity);
    /** 普通物品是否已被声明为标记，允许无 BlockItem 的自定义标记。 */
    boolean isCustomMarker(ResourceLocation item);
    /** 客户端规则快照；只包含 ID 与权限，不含脚本或任意可执行内容。 */
    String toJson();
}
