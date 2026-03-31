package git.chexson.chexsonsaeutils.support;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Objects;

public final class TestKeySupport {

    private TestKeySupport() {
    }

    public static RegistryFriendlyByteBuf newRegistryFriendlyByteBuf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    public static final class DummyKey extends AEKey {
        private static final MapCodec<DummyKey> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("primary").forGetter(DummyKey::primaryKey),
                Codec.STRING.fieldOf("variant").forGetter(DummyKey::variantId),
                Codec.INT.optionalFieldOf("fuzzyValue", 0).forGetter(DummyKey::fuzzyValue),
                Codec.INT.optionalFieldOf("fuzzyMaxValue", 0).forGetter(DummyKey::fuzzyMaxValue)
        ).apply(instance, (primaryKey, variantId, fuzzyValue, fuzzyMaxValue) ->
                new DummyKey(primaryKey, variantId, fuzzyValue, fuzzyMaxValue)));

        private final String primaryKey;
        private final String variantId;
        private final int fuzzyValue;
        private final int fuzzyMaxValue;

        public DummyKey(String id) {
            this(id, id, 0, 0);
        }

        public DummyKey(String primaryKey, String variantId, int fuzzyValue, int fuzzyMaxValue) {
            this.primaryKey = primaryKey;
            this.variantId = variantId;
            this.fuzzyValue = fuzzyValue;
            this.fuzzyMaxValue = fuzzyMaxValue;
        }

        private String primaryKey() {
            return primaryKey;
        }

        private String variantId() {
            return variantId;
        }

        private int fuzzyValue() {
            return fuzzyValue;
        }

        private int fuzzyMaxValue() {
            return fuzzyMaxValue;
        }

        @Override
        public AEKeyType getType() {
            return DummyKeyType.INSTANCE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag(HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            tag.putString("primary", primaryKey);
            tag.putString("variant", variantId);
            tag.putInt("fuzzyValue", fuzzyValue);
            tag.putInt("fuzzyMaxValue", fuzzyMaxValue);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return primaryKey;
        }

        @Override
        public int getFuzzySearchValue() {
            return fuzzyValue;
        }

        @Override
        public int getFuzzySearchMaxValue() {
            return fuzzyMaxValue;
        }

        @Override
        public ResourceLocation getId() {
            return Objects.requireNonNull(ResourceLocation.tryParse(
                    "chexsonsaeutils:" + primaryKey + "_" + variantId
            ));
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
            data.writeUtf(primaryKey);
            data.writeUtf(variantId);
            data.writeInt(fuzzyValue);
            data.writeInt(fuzzyMaxValue);
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(primaryKey + ":" + variantId);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return false;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DummyKey dummyKey)) {
                return false;
            }
            return fuzzyValue == dummyKey.fuzzyValue
                    && fuzzyMaxValue == dummyKey.fuzzyMaxValue
                    && Objects.equals(primaryKey, dummyKey.primaryKey)
                    && Objects.equals(variantId, dummyKey.variantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(primaryKey, variantId, fuzzyValue, fuzzyMaxValue);
        }
    }

    private static final class DummyKeyType extends AEKeyType {
        private static final DummyKeyType INSTANCE = new DummyKeyType();

        private DummyKeyType() {
            super(Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:test")),
                    DummyKey.class,
                    Component.literal("Test"));
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            return DummyKey.CODEC;
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return new DummyKey(input.readUtf(), input.readUtf(), input.readInt(), input.readInt());
        }

        @Override
        public AEKey loadKeyFromTag(HolderLookup.Provider provider, CompoundTag tag) {
            return new DummyKey(
                    tag.getString("primary"),
                    tag.getString("variant"),
                    tag.getInt("fuzzyValue"),
                    tag.getInt("fuzzyMaxValue")
            );
        }
    }
}
