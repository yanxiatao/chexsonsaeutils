package git.chexson.chexsonsaeutils.bigint;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.math.BigInteger;

/**
 * M1 同步字段的线格式编解码：{@code kind|<base64url(AEKey 包字节)>|decimal}，记录以 {@code ;} 分隔。
 *
 * <p>键编码复用 AE2 自己的 {@link AEKey#writeKey} / {@link AEKey#readKey}——与
 * {@code CraftingPlanSummaryEntry} 同步 entry 用的是同一份 codec，保证客户端还原出的
 * {@code AEKey} 与表里那一行的 {@code entry.getWhat()} 按值相等，可直接作 map 键匹配。
 * base64url（无 padding）不含 {@code |} 与 {@code ;}，无需转义。decimal 用 {@link BigInteger#toString()}。
 *
 * <p>编解码都需要 {@link RegistryAccess}（写 item 组件时要用注册表）：服务端取
 * {@code player.level().registryAccess()}，客户端取 {@code minecraft.level.registryAccess()}。
 */
public final class WidePlanLedgerCodec {

    private static final String RECORD_SEPARATOR = ";";
    private static final char FIELD_SEPARATOR = '|';

    private WidePlanLedgerCodec() {
    }

    /** 快照 → 同步字符串；空快照返回空串。 */
    public static String encode(WideLedgerSnapshot snapshot, RegistryAccess registries) {
        if (snapshot == null || snapshot.isEmpty() || registries == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (WideAmountKind kind : WideAmountKind.values()) {
            for (Map.Entry<AEKey, BigInteger> entry : snapshot.entries(kind).entrySet()) {
                String keyToken = encodeKey(entry.getKey(), registries);
                if (keyToken == null) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append(RECORD_SEPARATOR);
                }
                out.append(kind.wireToken()).append(FIELD_SEPARATOR)
                        .append(keyToken).append(FIELD_SEPARATOR)
                        .append(entry.getValue().toString());
            }
        }
        return out.toString();
    }

    /** 同步字符串 → 快照；解析失败或空串返回空快照（客户端据此退回 AE2 显示）。 */
    public static WideLedgerSnapshot decode(@Nullable String encoded, RegistryAccess registries) {
        if (encoded == null || encoded.isEmpty() || registries == null) {
            return WideLedgerSnapshot.empty();
        }
        EnumMap<WideAmountKind, Map<AEKey, BigInteger>> byKind = new EnumMap<>(WideAmountKind.class);
        for (String record : encoded.split(RECORD_SEPARATOR)) {
            if (record.isEmpty()) {
                continue;
            }
            // key token 是 base64url（无分隔符），最多两个 FIELD_SEPARATOR，切三段足够。
            String[] parts = record.split("[|]", 3);
            if (parts.length != 3) {
                continue;
            }
            WideAmountKind kind = WideAmountKind.fromWireToken(parts[0]);
            AEKey key = decodeKey(parts[1], registries);
            if (kind == null || key == null) {
                continue;
            }
            try {
                BigInteger amount = new BigInteger(parts[2]);
                byKind.computeIfAbsent(kind, k -> new HashMap<>()).put(key, amount);
            } catch (NumberFormatException ignored) {
                // 坏记录跳过，不影响其余键。
            }
        }
        return WideLedgerSnapshot.fromDecoded(byKind);
    }

    @Nullable
    private static String encodeKey(AEKey key, RegistryAccess registries) {
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
            AEKey.writeKey(buf, key);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static AEKey decodeKey(String token, RegistryAccess registries) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);
            return AEKey.readKey(buf);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
