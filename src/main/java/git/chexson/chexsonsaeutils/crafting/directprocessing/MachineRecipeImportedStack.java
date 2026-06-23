package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;



import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

public record MachineRecipeImportedStack(
        @Nullable AEKey key,
        long amount
) {

    public static final String ITEM_KEY_TYPE = "item";
    public static final String FLUID_KEY_TYPE = "fluid";
    private static final String KEY_MEMBER_NAME = "key";

    public MachineRecipeImportedStack {
        amount = Math.max(0L, amount);
    }

    boolean isStructurallyValid() {
        return key != null && amount > 0L;
    }

    @Nullable
    public GenericStack toGenericStack() {
        return key == null || amount <= 0L ? null : new GenericStack(key, amount);
    }

    @Nullable
    public static MachineRecipeImportedStack fromGenericStack(@Nullable GenericStack stack) {
        if (stack == null || stack.what() == null || stack.amount() <= 0L) {
            return null;
        }
        return new MachineRecipeImportedStack(stack.what(), stack.amount());
    }

    @Nullable
    public static MachineRecipeImportedStack fromItemStack(@Nullable ItemStack stack) {
        return fromGenericStack(DirectProcessingStackConverterRegistry.directProcessingDefaults().convert(stack));
    }

    @Nullable
    public static MachineRecipeImportedStack fromFluidStack(@Nullable FluidStack stack) {
        return fromGenericStack(DirectProcessingStackConverterRegistry.directProcessingDefaults().convert(stack));
    }

    @Nullable
    JsonObject toJsonObject(HolderLookup.Provider registries) {
        if (!isStructurallyValid()) {
            return null;
        }
        JsonElement encodedKey = encodeKey(registries, key);
        if (encodedKey == null) {
            return null;
        }
        JsonObject object = new JsonObject();
        object.add(KEY_MEMBER_NAME, encodedKey);
        object.addProperty("amount", amount);
        return object;
    }

    @Nullable
    static MachineRecipeImportedStack fromJsonObject(HolderLookup.Provider registries, @Nullable JsonObject object) {
        if (object == null) {
            return null;
        }
        MachineRecipeImportedStack decoded = decodeKeyObject(registries, object);
        if (decoded != null) {
            return decoded;
        }
        return decodeLegacyObject(object);
    }

    @Nullable
    private static JsonElement encodeKey(HolderLookup.Provider registries, @Nullable AEKey key) {
        if (registries == null || key == null) { return null; } return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, key.toTagGeneric());
    }

    @Nullable
    private static MachineRecipeImportedStack decodeKeyObject(
            HolderLookup.Provider registries,
            JsonObject object
    ) {
        if (registries == null || !object.has(KEY_MEMBER_NAME)) {
            return null;
        }
        JsonElement keyElement = object.get(KEY_MEMBER_NAME);
        CompoundTag keyTag = (CompoundTag) JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, keyElement).getValue(); AEKey decodedKey = keyTag != null && !keyTag.isEmpty() ? AEKey.fromTagGeneric(keyTag) : null;
        long decodedAmount = object.has("amount") ? object.get("amount").getAsLong() : 0L;
        MachineRecipeImportedStack stack = new MachineRecipeImportedStack(decodedKey, decodedAmount);
        return stack.isStructurallyValid() ? stack : null;
    }

    @Nullable
    private static MachineRecipeImportedStack decodeLegacyObject(JsonObject object) {
        ResourceLocation keyId = readResourceLocation(object, "key_id");
        String keyType = object.has("key_type") ? object.get("key_type").getAsString() : "";
        long amount = object.has("amount") ? object.get("amount").getAsLong() : 0L;
        if (keyId == null || amount <= 0L) {
            return null;
        }
        return switch (keyType == null ? "" : keyType.trim().toLowerCase(java.util.Locale.ROOT)) {
            case ITEM_KEY_TYPE -> decodeLegacyItem(keyId, amount);
            case FLUID_KEY_TYPE -> decodeLegacyFluid(keyId, amount);
            default -> null;
        };
    }

    @Nullable
    private static MachineRecipeImportedStack decodeLegacyItem(ResourceLocation keyId, long amount) {
        var item = BuiltInRegistries.ITEM.get(keyId);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        AEItemKey key = AEItemKey.of(item);
        return key == null ? null : new MachineRecipeImportedStack(key, amount);
    }

    @Nullable
    private static MachineRecipeImportedStack decodeLegacyFluid(ResourceLocation keyId, long amount) {
        if (amount > Integer.MAX_VALUE) {
            return null;
        }
        var fluid = BuiltInRegistries.FLUID.get(keyId);
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return null;
        }
        AEFluidKey key = AEFluidKey.of(new FluidStack(fluid, (int) amount));
        return key == null ? null : new MachineRecipeImportedStack(key, amount);
    }

    @Nullable
    private static ResourceLocation readResourceLocation(JsonObject object, String memberName) {
        if (!object.has(memberName) || !object.get(memberName).isJsonPrimitive()) {
            return null;
        }
        return ResourceLocation.tryParse(object.get(memberName).getAsString());
    }
}
