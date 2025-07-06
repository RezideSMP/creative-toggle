package rezide.creativetoggle;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DiscordBotManager {

    private static JDA jda;
    private static String botToken; // Now set via startBot method
    public static int HTTP_PORT; // Now set via startBot method (made public for CreativeToggle to access)
    public static AtomicInteger currentPlayerCount = new AtomicInteger(0); // Kept public for direct access for 0 count on stop

    private static HttpServer httpServer;

    // Modified startBot to accept token and port
    public static void startBot(String token, int httpPort) {
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            CreativeToggle.LOGGER.info("Discord Bot is already running.");
            return;
        }

        botToken = token;
        HTTP_PORT = httpPort;

        if (botToken.equals("YOUR_DISCORD_BOT_TOKEN_HERE") || botToken.isEmpty()) {
            CreativeToggle.LOGGER.warn("Discord BOT_TOKEN not set in config/creative-toggle.json. Bot will not start.");
            return;
        }
        if (HTTP_PORT == 0) { // Should be caught by config validation, but another check
            CreativeToggle.LOGGER.warn("Discord Bot HTTP Port is 0. Bot will not start HTTP server.");
            return;
        }

        new Thread(() -> {
            try {
                jda = JDABuilder.createDefault(botToken)
                        .disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                        .setActivity(Activity.playing("Starting up..."))
                        .build();
                jda.awaitReady();
                CreativeToggle.LOGGER.info("Discord Bot is online!");

                startHttpServer(); // Use the HTTP_PORT from the config
                updateBotPresence();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CreativeToggle.LOGGER.error("Discord Bot connection interrupted: {}", e.getMessage());
            } catch (Exception e) {
                CreativeToggle.LOGGER.error("Error starting Discord Bot: {}", e.getMessage());
            }
        }, "DiscordBot-ConnectThread").start();
    }

    public static void stopBot() {
        if (httpServer != null) {
            CreativeToggle.LOGGER.info("Stopping internal HTTP server...");
            httpServer.stop(0);
            httpServer = null;
            CreativeToggle.LOGGER.info("Internal HTTP server stopped.");
        }
        if (jda != null) {
            CreativeToggle.LOGGER.info("Shutting down Discord Bot...");
            jda.shutdownNow();
            jda = null;
            CreativeToggle.LOGGER.info("Discord Bot offline.");
        }
    }

    private static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/updatePlayerCount", DiscordBotManager::handlePlayerCountUpdate);
        httpServer.setExecutor(Executors.newFixedThreadPool(2));
        httpServer.start();
        CreativeToggle.LOGGER.info("Internal HTTP server started on port {}", HTTP_PORT);
    }

    private static void handlePlayerCountUpdate(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            String query = new String(exchange.getRequestBody().readAllBytes());
            try {
                int count = Integer.parseInt(query.split("=")[1]);
                currentPlayerCount.set(count);
                updateBotPresence();
                CreativeToggle.LOGGER.debug("Received player count update: {}", count);
                String response = "OK";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                CreativeToggle.LOGGER.error("Invalid player count format received from internal request: {}", query);
                String response = "Bad Request";
                exchange.sendResponseHeaders(400, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        } else {
            String response = "Method Not Allowed";
            exchange.sendResponseHeaders(405, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    public static void updateBotPresence() {
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            jda.getPresence().setActivity(Activity.playing("Players: " + currentPlayerCount.get()));
        } else {
            CreativeToggle.LOGGER.warn("JDA not connected, cannot update bot presence.");
        }
    }

    public static void sendMessageToChannel(long channelId, String message) {
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            jda.getTextChannelById(channelId)
                    .sendMessage(message)
                    .queue(null, throwable -> CreativeToggle.LOGGER.error("Failed to send message to Discord channel {}: {}", channelId, throwable.getMessage()));
        } else {
            CreativeToggle.LOGGER.warn("JDA not connected, cannot send message to channel {}. Message: {}", channelId, message);
        }
    }
}