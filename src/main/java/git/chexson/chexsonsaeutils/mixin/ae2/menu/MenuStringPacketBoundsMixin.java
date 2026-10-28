package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import git.chexson.chexsonsaeutils.network.codec.PacketTextWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2 GuiSync 字符串共用的编码入口：等待分支、键名和精确字节不再撞默认短字符串上限。
 * 保留原生 UTF-8 格式与字段生命周期，不接触其反射框架；由 NeoForge 负责整个包的分片。 */
@Mixin(targets = "appeng.menu.guisync.SynchronizedField$StringField", remap = false)
public abstract class MenuStringPacketBoundsMixin {
    @Inject(method = "writeValue(Lnet/minecraft/network/RegistryFriendlyByteBuf;Ljava/lang/String;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$writeCompleteText(RegistryFriendlyByteBuf buffer, String value, CallbackInfo ci) {
        PacketTextWire.write(buffer, value);
        ci.cancel();
    }

    @Inject(method = "readValue(Lnet/minecraft/network/RegistryFriendlyByteBuf;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$readCompleteText(RegistryFriendlyByteBuf buffer, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(PacketTextWire.read(buffer));
    }
}
