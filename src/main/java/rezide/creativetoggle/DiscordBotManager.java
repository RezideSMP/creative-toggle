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
import java.util.concurrent.atomic.AtomicInteger; // To safely update player count

public class DiscordBotManager {

    private static JDA jda;
    // !! IMPORTANT: Replace with your bot token !!
    private static final String BOT_TOKEN = "0da5e0aa5a3388e977aa625abfc09951d29f25eafc49316e0eeab7173f1ac13d";
    static final int HTTP_PORT = 8080; // Port for the bot to listen for player count updates from itself/mod
    static AtomicInteger currentPlayerCount = new AtomicInteger(0);

    private static HttpServer httpServer; // Keep a reference to shutdown gracefully

    public static void startBot() {
        if (BOT_TOKEN.equals("YOUR_DISCORD_BOT_TOKEN") || BOT_TOKEN.isEmpty()) {
            CreativeToggle.LOGGER.warn("Discord BOT_TOKEN not set in DiscordBotManager. Bot will not start.");
            return;
        }

        // Start bot connection in a new thread
        new Thread(() -> {
            try {
                jda = JDABuilder.createDefault(BOT_TOKEN)
                        .disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                        .setActivity(Activity.playing("Starting up..."))
                        // You can add event listeners here if your bot will handle Discord commands
                        // .addEventListeners(new MyBotCommandListener())
                        .build();
                jda.awaitReady(); // Wait until the bot is fully logged in
                CreativeToggle.LOGGER.info("Rezide SMP Bot is online!");

                // Start the internal HTTP server after the bot is online
                startHttpServer();
                updateBotPresence(); // Set initial presence

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                CreativeToggle.LOGGER.error("Discord Bot connection interrupted: {}", e.getMessage());
            } catch (Exception e) {
                CreativeToggle.LOGGER.error("Error starting Discord Bot: {}", e.getMessage());
            }
        }, "DiscordBot-ConnectThread").start();
    }

    public static void stopBot() {
        if (jda != null) {
            CreativeToggle.LOGGER.info("Shutting down Discord Bot...");
            jda.shutdownNow(); // Immediately shuts down JDA
            jda = null; // Clear reference
            CreativeToggle.LOGGER.info("Discord Bot offline.");
        }
        if (httpServer != null) {
            CreativeToggle.LOGGER.info("Stopping internal HTTP server...");
            httpServer.stop(0); // Stop immediately
            httpServer = null;
            CreativeToggle.LOGGER.info("Internal HTTP server stopped.");
        }
    }

    private static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/updatePlayerCount", DiscordBotManager::handlePlayerCountUpdate);
        // Use a separate thread pool for HTTP requests to not block JDA's thread or Minecraft's
        httpServer.setExecutor(Executors.newFixedThreadPool(2)); // Small pool for concurrency
        httpServer.start();
        CreativeToggle.LOGGER.info("Internal HTTP server started on port {}", HTTP_PORT);
    }

    private static void handlePlayerCountUpdate(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            String query = new String(exchange.getRequestBody().readAllBytes());
            try {
                int count = Integer.parseInt(query.split("=")[1]);
                currentPlayerCount.set(count);
                updateBotPresence(); // Update presence immediately
                CreativeToggle.LOGGER.debug("Received player count update: {}", count); // Use debug for frequent updates
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

    // You can add other methods here if your bot will do more, e.g., send messages to channels
    public static void sendMessageToChannel(long channelId, String message) {
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            jda.getTextChannelById(channelId)
                .sendMessage(message)
                .queue(null, throwable -> CreativeToggle.LOGGER.error("Failed to send message to Discord channel {}: {}", channelId, throwable.getMessage()));
        } else {
            CreativeToggle.LOGGER.warn("JDA not connected, cannot send message to channel {}.", channelId);
        }
    }
}