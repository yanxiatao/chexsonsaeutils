package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 键序列化必须保留组件 patch 与 NBT 数值类型（JsonOps 往返会把 int 降级成 byte）。 */
class CapturedKeyCodecTest {

    @Test
    void patchedKeySnbtRoundTripPreservesNbtTypes() throws Exception {
        var stack = new ItemStack(Items.GUNPOWDER);
        var tag = new CompoundTag();
        tag.putInt("roundTripVariant", 3);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        AEItemKey original = AEItemKey.of(stack);
        var ctx = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                .createSerializationContext(NbtOps.INSTANCE);

        var encoded = AEKey.CODEC.encodeStart(ctx, original).getOrThrow();
        AEItemKey decoded = (AEItemKey) AEKey.CODEC.parse(ctx, TagParser.parseTag(encoded.toString()))
                .getOrThrow();

        assertEquals(original, decoded);
        CompoundTag expected = new CompoundTag();
        expected.putInt("roundTripVariant", 3);
        assertEquals(expected, decoded.getReadOnlyStack()
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag())).copyTag());
    }
}
