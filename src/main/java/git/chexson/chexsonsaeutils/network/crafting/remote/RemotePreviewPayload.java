package git.chexson.chexsonsaeutils.network.crafting.remote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 分页预览载荷：客户端仅选择菜单与页码，不携带任意来源位置或可伪造材料需求。 */
public final class RemotePreviewPayload {
    private static BiConsumer<Request, ServerPlayer> server;
    private static Consumer<Response> client;
    private RemotePreviewPayload() {}
    public static void bindServer(BiConsumer<Request, ServerPlayer> handler) { server = Objects.requireNonNull(handler); }
    public static void bindClient(Consumer<Response> handler) { client = Objects.requireNonNull(handler); }
    /** 每页最多 8 条，每条有有界文本，不构造超过协议尺寸的单包。 */
    public record Row(String source, String output, String amount, String status, List<String> details) {
        public Row { details = List.copyOf(details); }
    }
    public record Request(int menuId, long queryId, int page, String search, boolean problemsOnly, boolean refresh) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "remote_preview_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.ofMember(Request::write, Request::read);
        private void write(RegistryFriendlyByteBuf b) { b.writeVarInt(menuId); b.writeLong(queryId); b.writeVarInt(page); b.writeUtf(search, 128); b.writeBoolean(problemsOnly); b.writeBoolean(refresh); }
        private static Request read(RegistryFriendlyByteBuf b) { return new Request(b.readVarInt(), b.readLong(), b.readVarInt(), b.readUtf(128), b.readBoolean(), b.readBoolean()); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public static void handle(Request value, IPayloadContext context) {
            context.enqueueWork(() -> { if (context.player() instanceof ServerPlayer player) Objects.requireNonNull(server, "远程预览服务未装配").accept(value, player); });
        }
    }
    public record Response(int menuId, long queryId, int page, int pages, String message, List<Row> rows) implements CustomPacketPayload {
        public Response { rows = List.copyOf(rows); }
        public static final Type<Response> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "remote_preview_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.ofMember(Response::write, Response::read);
        private void write(RegistryFriendlyByteBuf b) {
            b.writeVarInt(menuId); b.writeLong(queryId); b.writeVarInt(page); b.writeVarInt(pages); b.writeUtf(message, 512); b.writeVarInt(rows.size());
            for (var row : rows) {
                b.writeUtf(row.source(), 256); b.writeUtf(row.output(), 256); b.writeUtf(row.amount(), 2048); b.writeUtf(row.status(), 256);
                b.writeVarInt(row.details().size()); for (var line : row.details()) b.writeUtf(line, 512);
            }
        }
        private static Response read(RegistryFriendlyByteBuf b) {
            int menu = b.readVarInt(); long query = b.readLong(); int page = b.readVarInt(), pages = b.readVarInt(); String message = b.readUtf(512);
            int size = b.readVarInt(); if (size < 0 || size > 8) throw new IllegalArgumentException("远程预览页尺寸越界");
            var rows = new java.util.ArrayList<Row>();
            for (int i = 0; i < size; i++) {
                String source = b.readUtf(256), output = b.readUtf(256), amount = b.readUtf(2048), status = b.readUtf(256);
                int lines = b.readVarInt(); if (lines < 0 || lines > 32) throw new IllegalArgumentException("远程预览详情尺寸越界");
                var details = new java.util.ArrayList<String>(); for (int n = 0; n < lines; n++) details.add(b.readUtf(512));
                rows.add(new Row(source, output, amount, status, details));
            }
            return new Response(menu, query, page, pages, message, rows);
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public static void handle(Response value, IPayloadContext context) {
            context.enqueueWork(() -> Objects.requireNonNull(client, "远程预览客户端未装配").accept(value));
        }
    }
}
