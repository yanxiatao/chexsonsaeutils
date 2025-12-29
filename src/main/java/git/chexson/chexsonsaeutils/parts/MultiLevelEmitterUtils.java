package git.chexson.chexsonsaeutils.parts;

import appeng.util.ConfigInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Level Emitter 通用工具类
 */
public class MultiLevelEmitterUtils {
    
    /**
     * 计算配置槽中实际配置的物品数量
     * 遇到第一个空槽位则停止计数（强制按顺序配置）
     * 
     * @param config 配置槽
     * @return 已配置的物品数量
     */
    public static int calculateConfiguredItemCount(ConfigInventory config) {
        int count = 0;
        for (int i = 0; i < config.size(); i++) {
            if (config.getKey(i) != null) {
                count++;
            } else {
                // 遇到第一个空槽位，停止计数
                break;
            }
        }
        return count;
    }
    
    /**
     * 从NBT标签中读取逻辑关系列表
     * 
     * @param data 包含逻辑关系数据的NBT标签
     * @param tagName 逻辑关系列表在NBT中的标签名
     * @return 读取的逻辑关系列表
     */
    public static List<MultiLevelEmitterPart.LogicRelation> readLogicRelationsFromNBT(CompoundTag data, String tagName) {
        List<MultiLevelEmitterPart.LogicRelation> relations = new ArrayList<>();
        if (data.contains(tagName, Tag.TAG_LIST)) {
            ListTag relationsList = data.getList(tagName, Tag.TAG_STRING);
            for (int i = 0; i < relationsList.size(); i++) {
                String relationStr = relationsList.getString(i);
                try {
                    relations.add(MultiLevelEmitterPart.LogicRelation.valueOf(relationStr));
                } catch (IllegalArgumentException e) {
                    relations.add(MultiLevelEmitterPart.LogicRelation.AND);
                }
            }
        }
        return relations;
    }
    
    /**
     * 将逻辑关系列表写入NBT标签
     * 
     * @param relations 逻辑关系列表
     * @param data 目标NBT标签
     * @param tagName 逻辑关系列表在NBT中的标签名
     */
    public static void writeLogicRelationsToNBT(List<MultiLevelEmitterPart.LogicRelation> relations, CompoundTag data, String tagName) {
        ListTag relationsList = new ListTag();
        for (MultiLevelEmitterPart.LogicRelation relation : relations) {
            relationsList.add(net.minecraft.nbt.StringTag.valueOf(relation.name()));
        }
        data.put(tagName, relationsList);
    }
    
    /**
     * 从NBT标签中读取比较模式列表
     * 
     * @param data 包含比较模式数据的NBT标签
     * @param tagName 比较模式列表在NBT中的标签名
     * @return 读取的比较模式列表
     */
    public static List<MultiLevelEmitterPart.ComparisonMode> readComparisonModesFromNBT(CompoundTag data, String tagName) {
        List<MultiLevelEmitterPart.ComparisonMode> modes = new ArrayList<>();
        if (data.contains(tagName, Tag.TAG_LIST)) {
            ListTag modesList = data.getList(tagName, Tag.TAG_STRING);
            for (int i = 0; i < modesList.size(); i++) {
                String modeStr = modesList.getString(i);
                try {
                    modes.add(MultiLevelEmitterPart.ComparisonMode.valueOf(modeStr));
                } catch (IllegalArgumentException e) {
                    modes.add(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL);
                }
            }
        }
        return modes;
    }
    
    /**
     * 将比较模式列表写入NBT标签
     * 
     * @param modes 比较模式列表
     * @param data 目标NBT标签
     * @param tagName 比较模式列表在NBT中的标签名
     */
    public static void writeComparisonModesToNBT(List<MultiLevelEmitterPart.ComparisonMode> modes, CompoundTag data, String tagName) {
        ListTag modesList = new ListTag();
        for (MultiLevelEmitterPart.ComparisonMode mode : modes) {
            modesList.add(net.minecraft.nbt.StringTag.valueOf(mode.name()));
        }
        data.put(tagName, modesList);
    }

}