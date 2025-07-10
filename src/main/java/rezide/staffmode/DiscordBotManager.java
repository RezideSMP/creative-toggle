package rezide.staffmode;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer; // Import MinecraftServer

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DiscordBotManager {

    private static JDA jda;
    private static String botToken;
    public static int HTTP_PORT;
    public static AtomicInteger currentPlayerCount = new AtomicInteger(0);

    private static HttpServer httpServer;

    private static MinecraftServer minecraftServerInstance; // Reference to the MinecraftServer instance

    public static void startBot(String token, int httpPort, MinecraftServer server) {
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            StaffMode.LOGGER.info("Discord Bot is already running.");
            return;
        }

        botToken = token;
        HTTP_PORT = httpPort;
        minecraftServerInstance = server; // Store the server instance

        if (botToken.equals("YOUR_DISCORD_BOT_TOKEN_HERE") || botToken.isEmpty()) {
            StaffMode.LOGGER.warn("Discord BOT_TOKEN not set in config/staff-mode.json. Bot will not start.");
            return;
        }
        if (HTTP_PORT == 0) {
            StaffMode.LOGGER.warn("Discord Bot HTTP Port is 0. Bot will not start HTTP server.");
            return;
        }

        new Thread(() -> {
            try {
                jda = JDABuilder.createDefault(botToken)
                        .disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                        .setActivity(Activity.playing("Starting up..."))
                        .build();
                jda.awaitReady(); // This line blocks until the bot is connected and ready
                StaffMode.LOGGER.info("Discord Bot is online!");

                // Execute initial tasks AFTER JDA is ready
                startHttpServer(); // Start HTTP server after JDA is ready
                updateBotPresence(); // Update presence immediately
                sendInitialServerStatusMessage(); // Send server start message

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                StaffMode.LOGGER.error("Discord Bot connection interrupted: {}", e.getMessage());
            } catch (Exception e) {
                StaffMode.LOGGER.error("Error starting Discord Bot: {}", e.getMessage());
            } finally {
                // Clear the server instance reference once the initial setup is done
                minecraftServerInstance = null;
            }
        }, "DiscordBot-ConnectThread").start();
    }

    public static void stopBot() {
        sendServerStoppingMessage(); // Attempt to send message before any shutdown

        if (httpServer != null) {
            StaffMode.LOGGER.info("Stopping internal HTTP server...");
            httpServer.stop(0);
            httpServer = null;
            StaffMode.LOGGER.info("Internal HTTP server stopped.");
        }
        if (jda != null) {
            StaffMode.LOGGER.info("Shutting down Discord Bot...");
            jda.shutdownNow(); // Use shutdownNow for faster exit
            jda = null;
            StaffMode.LOGGER.info("Discord Bot offline.");
        }
    }

    private static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/updatePlayerCount", DiscordBotManager::handlePlayerCountUpdate);
        httpServer.setExecutor(Executors.newFixedThreadPool(2));
        httpServer.start();
        StaffMode.LOGGER.info("Internal HTTP server started on port {}", HTTP_PORT);
    }

    private static void handlePlayerCountUpdate(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            String query = new String(exchange.getRequestBody().readAllBytes());
            try {
                int count = Integer.parseInt(query.split("=")[1]);
                currentPlayerCount.set(count);
                updateBotPresence();
                StaffMode.LOGGER.debug("Received player count update: {}", count);
                String response = "OK";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                StaffMode.LOGGER.error("Invalid player count format received from internal request: {}", query);
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
            StaffMode.LOGGER.warn("JDA not connected, cannot update bot presence.");
        }
    }

    public static void sendMessageToChannel(long channelId, String message) {
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            jda.getTextChannelById(channelId)
                    .sendMessage(message)
                    .queue(null, throwable -> StaffMode.LOGGER.error("Failed to send message to Discord channel {}: {}", channelId, throwable.getMessage()));
        } else {
            StaffMode.LOGGER.warn("JDA not connected, cannot send message to channel {}. Message: {}", channelId, message);
        }
    }

    // Removed: sendCommandLogMessage method

    private static void sendInitialServerStatusMessage() {
        StaffModeConfig config = StaffModeConfig.getInstance();
        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message = String.format("✅ **Server Started!**\nTime: `%s EDT`", startTime);

        // Send to admin log channel
        sendMessageToChannel(config.getAdminLogChannelId(), message);
        // Send to server status channel
        if (config.getServerStatusChannelId() != 0L) {
            sendMessageToChannel(config.getServerStatusChannelId(), message);
        }

        if (minecraftServerInstance != null) {
            int playerCount = minecraftServerInstance.getCurrentPlayerCount();
            currentPlayerCount.set(playerCount);
            updateBotPresence();
            StaffMode.LOGGER.info("Initial player count sent: {}", playerCount);
        }
    }

    private static void sendServerStoppingMessage() {
        StaffModeConfig config = StaffModeConfig.getInstance();
        String stopTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message = String.format("⛔ **Server Stopping!**\nTime: `%s EDT`", stopTime);

        // Send to admin log channel
        sendMessageToChannel(config.getAdminLogChannelId(), message);
        // Send to server status channel
        if (config.getServerStatusChannelId() != 0L) {
            sendMessageToChannel(config.getServerStatusChannelId(), message);
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            StaffMode.LOGGER.warn("Interrupted while waiting for Discord 'Server Stopping' message to send.");
        }
    }

    // Corrected visibility for this method
    public static void updatePlayerCountViaHttp(int count) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("http://localhost:" + HTTP_PORT + "/updatePlayerCount");
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setDoOutput(true);

                String payload = "count=" + count;

                try (java.io.OutputStream os = connection.getOutputStream()) {
                    byte[] input = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    StaffMode.LOGGER.error("Failed to send player count to internal bot HTTP server. Response code: {}", responseCode);
                }
                connection.disconnect();
            } catch (Exception e) {
                StaffMode.LOGGER.error("Error sending player count to internal bot HTTP server: {}", e.getMessage());
            }
        }, "PlayerCountSender-Internal").start();
    }
}
