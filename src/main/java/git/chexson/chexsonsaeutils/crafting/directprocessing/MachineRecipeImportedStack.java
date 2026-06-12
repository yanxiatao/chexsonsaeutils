package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

public record MachineRecipeImportedStack(
        String keyType,
        ResourceLocation keyId,
        long amount
) {

    public static final String ITEM_KEY_TYPE = "item";
    public static final String FLUID_KEY_TYPE = "fluid";

    public MachineRecipeImportedStack {
        keyType = keyType == null ? "" : keyType.trim().toLowerCase(java.util.Locale.ROOT);
        amount = Math.max(0L, amount);
    }

    @Nullable
    public GenericStack toGenericStack() {
        if (keyId == null || amount <= 0L) {
            return null;
        }
        return switch (keyType) {
            case ITEM_KEY_TYPE -> {
                var item = BuiltInRegistries.ITEM.get(keyId);
                if (item == null || item == net.minecraft.world.item.Items.AIR) {
                    yield null;
                }
                var key = AEItemKey.of(item);
                yield key == null ? null : new GenericStack(key, amount);
            }
            case FLUID_KEY_TYPE -> {
                var fluid = BuiltInRegistries.FLUID.get(keyId);
                if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY
                        || amount > Integer.MAX_VALUE) {
                    yield null;
                }
                var key = AEFluidKey.of(new FluidStack(fluid, (int) amount));
                yield key == null ? null : new GenericStack(key, amount);
            }
            default -> null;
        };
    }

    @Nullable
    public static MachineRecipeImportedStack fromGenericStack(@Nullable GenericStack stack) {
        if (stack == null || stack.what() == null || stack.amount() <= 0L) {
            return null;
        }
        if (stack.what() instanceof AEItemKey itemKey) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(itemKey.getItem());
            return itemId == null ? null : new MachineRecipeImportedStack(ITEM_KEY_TYPE, itemId, stack.amount());
        }
        if (stack.what() instanceof AEFluidKey fluidKey) {
            ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluidKey.getFluid());
            return fluidId == null ? null : new MachineRecipeImportedStack(FLUID_KEY_TYPE, fluidId, stack.amount());
        }
        return null;
    }

    @Nullable
    public static MachineRecipeImportedStack fromItemStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return null;
        }
        return new MachineRecipeImportedStack(ITEM_KEY_TYPE, itemId, Math.max(1, stack.getCount()));
    }

    @Nullable
    public static MachineRecipeImportedStack fromFluidStack(@Nullable FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        if (fluidId == null) {
            return null;
        }
        return new MachineRecipeImportedStack(FLUID_KEY_TYPE, fluidId, Math.max(1, stack.getAmount()));
    }

    JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        if (keyId != null) {
            object.addProperty("key_id", keyId.toString());
        }
        object.addProperty("key_type", keyType);
        object.addProperty("amount", amount);
        return object;
    }

    @Nullable
    static MachineRecipeImportedStack fromJsonObject(@Nullable JsonObject object) {
        if (object == null) {
            return null;
        }
        ResourceLocation keyId = readResourceLocation(object, "key_id");
        String keyType = object.has("key_type") ? object.get("key_type").getAsString() : "";
        long amount = object.has("amount") ? object.get("amount").getAsLong() : 0L;
        MachineRecipeImportedStack stack = new MachineRecipeImportedStack(keyType, keyId, amount);
        return stack.toGenericStack() == null ? null : stack;
    }

    @Nullable
    private static ResourceLocation readResourceLocation(JsonObject object, String memberName) {
        if (!object.has(memberName) || !object.get(memberName).isJsonPrimitive()) {
            return null;
        }
        return ResourceLocation.tryParse(object.get(memberName).getAsString());
    }
}
