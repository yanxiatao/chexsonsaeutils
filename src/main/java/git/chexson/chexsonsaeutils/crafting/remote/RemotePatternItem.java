package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** 内部代理样板的真实物品及持久化解码；解码不会授予接单权限。 */
public final class RemotePatternItem extends Item {
    public RemotePatternItem(Properties properties) { super(properties); }
    public RemotePattern encode(AEKey output, HolderLookup.Provider registries) {
        var stack = new ItemStack(this);
        var tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.put("output", output.toTagGeneric(registries));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return new RemotePatternImpl(AEItemKey.of(stack), output);
    }
    public static final IPatternDetailsDecoder DECODER = new IPatternDetailsDecoder() {
        @Override public boolean isEncodedPattern(ItemStack stack) { return stack.getItem() instanceof RemotePatternItem; }
        @Override public IPatternDetails decodePattern(AEItemKey key, Level level) {
            if (key == null || level == null || !(key.getItem() instanceof RemotePatternItem)) return null;
            return decodeDefinition(key, level.registryAccess());
        }
    };
    /** 编解码逻辑独立于世界对象，保存/恢复测试使用真实注册表直接验证。 */
    public static RemotePattern decodeDefinition(AEItemKey key, HolderLookup.Provider registries) {
            try {
                var tag = key.toStack().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                if (tag.getInt("version") != 1) throw new IllegalArgumentException("不支持的远程样板版本");
                var output = AEKey.fromTagGeneric(registries, tag.getCompound("output"));
                if (output == null) throw new IllegalArgumentException("远程样板缺少有效输出");
                return new RemotePatternImpl(key, output);
            } catch (RuntimeException failure) {
                LogUtils.getLogger().error("远程样板解码失败：{}", key, failure);
                return null;
            }
    }
}
