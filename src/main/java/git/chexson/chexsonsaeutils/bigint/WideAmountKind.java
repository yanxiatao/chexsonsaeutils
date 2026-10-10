package git.chexson.chexsonsaeutils.bigint;

import org.jetbrains.annotations.Nullable;

/**
 * 宽域账的三类数量：缺料 / 已用（对应确认表"从存储"）/ 产出（对应"待合成"）。
 *
 * <p>每类带一个稳定的线格式 token（{@link #wireToken()}），只出现在 M1 的
 * {@code @GuiSync} 字符串里，与服务端写入、客户端解析共用同一份常量。
 */
public enum WideAmountKind {

    MISSING("miss"),
    USED("used"),
    EMIT("emit");

    private final String wireToken;

    WideAmountKind(String wireToken) {
        this.wireToken = wireToken;
    }

    public String wireToken() {
        return this.wireToken;
    }

    @Nullable
    public static WideAmountKind fromWireToken(@Nullable String token) {
        if (token == null) {
            return null;
        }
        for (WideAmountKind kind : values()) {
            if (kind.wireToken.equals(token)) {
                return kind;
            }
        }
        return null;
    }
}
