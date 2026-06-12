package xyz.langyo.minecraft.mcp.mod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import xyz.langyo.minecraft.mcp.common.McpConfig;
import xyz.langyo.minecraft.mcp.common.McpHttpServer;

@Mod("mcpmod")
public class ModDevMcpMod {
    public static ModDevMcpMod INSTANCE;

    private volatile McpHttpServer httpServer;
    private volatile String debugUrl;

    public ModDevMcpMod(IEventBus modEventBus) {
        INSTANCE = this;
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        Thread serverThread = new Thread(this::startHttpServer, "MCP-HTTP");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void startHttpServer() {
        try {
            Thread.sleep(5000L);
            ChexsonSmokeInputHandler inputHandler = new ChexsonSmokeInputHandler();
            int port = McpConfig.getServerPort();
            McpHttpServer server = new McpHttpServer(inputHandler, port);
            this.httpServer = server;
            server.start();
            this.debugUrl = "http://localhost:" + server.getPort() + "/debug/";
            System.out.println("[MCP] Debug page: " + this.debugUrl);
        } catch (Exception e) {
            System.err.println("[MCP] Failed to start HTTP server: " + e.getMessage());
        } catch (Error e) {
            System.err.println("[MCP] Failed to start HTTP server: " + e.getMessage());
        }
    }
}
