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
import java.io.FileWriter; // For file writing
import java.io.PrintWriter; // For print writing
import java.io.File; // For file operations

public class DiscordBotManager {

    private static JDA jda;
    private static String botToken;
    public static int HTTP_PORT;
    public static AtomicInteger currentPlayerCount = new AtomicInteger(0);

    private static HttpServer httpServer;

    private static MinecraftServer minecraftServerInstance; // Reference to the MinecraftServer instance

    // New: Reference to StaffModeConfig
    private static StaffModeConfig config;

    public static void startBot(String token, int httpPort, MinecraftServer server, StaffModeConfig staffModeConfig) {
        config = staffModeConfig; // Store the config instance

        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            StaffMode.LOGGER.info("Discord Bot is already running.");
            logToFile("Discord Bot is already running."); // Log to file
            return;
        }

        botToken = token;
        HTTP_PORT = httpPort;
        minecraftServerInstance = server; // Store the server instance

        // Check if Discord bot should even attempt to start
        boolean discordBotEnabled = !(botToken.equals("YOUR_DISCORD_BOT_TOKEN_HERE") || botToken.isEmpty());

        if (!discordBotEnabled) {
            StaffMode.LOGGER.warn("Discord BOT_TOKEN not set in config/staff-mode.json. Discord bot will not start.");
            logToFile("Discord BOT_TOKEN not set. Discord bot will not start."); // Log to file
        }

        if (HTTP_PORT == 0) {
            StaffMode.LOGGER.warn("Discord Bot HTTP Port is 0. Bot will not start HTTP server.");
            logToFile("Discord Bot HTTP Port is 0. HTTP server will not start."); // Log to file
        }

        if (discordBotEnabled) {
            new Thread(() -> {
                try {
                    jda = JDABuilder.createDefault(botToken)
                            .disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                            .setActivity(Activity.playing("Starting up..."))
                            .build();
                    jda.awaitReady(); // This line blocks until the bot is connected and ready
                    StaffMode.LOGGER.info("Discord Bot is online!");
                    logToFile("Discord Bot is online!"); // Log to file

                    // Execute initial tasks AFTER JDA is ready
                    startHttpServer(); // Start HTTP server after JDA is ready
                    updateBotPresence(); // Update presence immediately
                    sendInitialServerStatusMessage(); // Send server start message

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    StaffMode.LOGGER.error("Discord Bot connection interrupted: {}", e.getMessage());
                    logToFile("Discord Bot connection interrupted: " + e.getMessage()); // Log to file
                } catch (Exception e) {
                    StaffMode.LOGGER.error("Error starting Discord Bot: {}", e.getMessage());
                    logToFile("Error starting Discord Bot: " + e.getMessage()); // Log to file
                } finally {
                    // Clear the server instance reference once the initial setup is done
                    minecraftServerInstance = null;
                }
            }, "DiscordBot-ConnectThread").start();
        } else {
            StaffMode.LOGGER.info("Discord Bot is disabled, starting HTTP server and logging to file only.");
            logToFile("Discord Bot is disabled. Running in file-only mode.");
            // If Discord bot is disabled but HTTP port is set, still start HTTP server
            if (HTTP_PORT != 0) {
                try {
                    startHttpServer();
                    // Initial player count for file logging
                    if (minecraftServerInstance != null) {
                        int playerCount = minecraftServerInstance.getCurrentPlayerCount();
                        currentPlayerCount.set(playerCount);
                        logToFile(String.format("Initial player count: %d", playerCount));
                    }
                } catch (IOException e) {
                    StaffMode.LOGGER.error("Error starting HTTP server without Discord Bot: {}", e.getMessage());
                    logToFile("Error starting HTTP server without Discord Bot: " + e.getMessage());
                }
            }
            sendInitialServerStatusMessage(); // Still send status message (to file if enabled)
        }
    }

    public static void stopBot() {
        sendServerStoppingMessage(); // Attempt to send message before any shutdown

        if (httpServer != null) {
            StaffMode.LOGGER.info("Stopping internal HTTP server...");
            logToFile("Stopping internal HTTP server..."); // Log to file
            httpServer.stop(0);
            httpServer = null;
            StaffMode.LOGGER.info("Internal HTTP server stopped.");
            logToFile("Internal HTTP server stopped."); // Log to file
        }
        if (jda != null) {
            StaffMode.LOGGER.info("Shutting down Discord Bot...");
            logToFile("Shutting down Discord Bot..."); // Log to file
            jda.shutdownNow(); // Use shutdownNow for faster exit
            jda = null;
            StaffMode.LOGGER.info("Discord Bot offline.");
            logToFile("Discord Bot offline."); // Log to file
        } else {
            StaffMode.LOGGER.info("Discord Bot was not running.");
            logToFile("Discord Bot was not running."); // Log to file
        }
    }

    private static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/updatePlayerCount", DiscordBotManager::handlePlayerCountUpdate);
        httpServer.setExecutor(Executors.newFixedThreadPool(2));
        httpServer.start();
        StaffMode.LOGGER.info("Internal HTTP server started on port {}", HTTP_PORT);
        logToFile(String.format("Internal HTTP server started on port %d", HTTP_PORT)); // Log to file
    }

    private static void handlePlayerCountUpdate(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            String query = new String(exchange.getRequestBody().readAllBytes());
            try {
                int count = Integer.parseInt(query.split("=")[1]);
                currentPlayerCount.set(count);
                updateBotPresence();
                StaffMode.LOGGER.debug("Received player count update: {}", count);
                logToFile(String.format("Received player count update: %d", count)); // Log to file
                String response = "OK";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                StaffMode.LOGGER.error("Invalid player count format received from internal request: {}", query);
                logToFile("Invalid player count format received from internal request: " + query); // Log to file
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
            logToFile("JDA not connected, cannot update bot presence. Current Players: " + currentPlayerCount.get()); // Log to file
        }
    }

    public static void sendMessageToChannel(long channelId, String message) {
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            jda.getTextChannelById(channelId)
                    .sendMessage(message)
                    .queue(null, throwable -> {
                        StaffMode.LOGGER.error("Failed to send message to Discord channel {}: {}", channelId, throwable.getMessage());
                        logToFile(String.format("Failed to send message to Discord channel %d: %s", channelId, throwable.getMessage())); // Log to file
                    });
        } else {
            StaffMode.LOGGER.warn("JDA not connected, cannot send message to channel {}. Message: {}", channelId, message);
            logToFile(String.format("Discord Bot not connected. Message for channel %d: %s", channelId, message)); // Log to file
        }
    }

    private static void sendInitialServerStatusMessage() {
        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message = String.format("✅ **Server Started!**\nTime: `%s EDT`", startTime);

        // Always attempt to send to Discord if connected
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            // Send to admin log channel
            sendMessageToChannel(config.getAdminLogChannelId(), message);
            // Send to server status channel
            if (config.getServerStatusChannelId() != 0L) {
                sendMessageToChannel(config.getServerStatusChannelId(), message);
            }
        }

        // Always log to file if enabled
        if (config.isLogToFileEnabled()) {
            logToFile("[Server Status] " + message.replace("`", "")); // Remove markdown for plain text log
        }

        if (minecraftServerInstance != null) {
            int playerCount = minecraftServerInstance.getCurrentPlayerCount();
            currentPlayerCount.set(playerCount);
            updateBotPresence(); // This will log presence update to file if JDA not connected
            StaffMode.LOGGER.info("Initial player count updated: {}", playerCount);
            logToFile(String.format("Initial player count updated: %d", playerCount)); // Log to file
        }
    }

    private static void sendServerStoppingMessage() {
        String stopTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message = String.format("⛔ **Server Stopping!**\nTime: `%s EDT`", stopTime);

        // Always attempt to send to Discord if connected
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            // Send to admin log channel
            sendMessageToChannel(config.getAdminLogChannelId(), message);
            // Send to server status channel
            if (config.getServerStatusChannelId() != 0L) {
                sendMessageToChannel(config.getServerStatusChannelId(), message);
            }
        }

        // Always log to file if enabled
        if (config.isLogToFileEnabled()) {
            logToFile("[Server Status] " + message.replace("`", "")); // Remove markdown for plain text log
        }

        // Give a small delay for Discord message to send if the bot is active
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                StaffMode.LOGGER.warn("Interrupted while waiting for Discord 'Server Stopping' message to send.");
                logToFile("Interrupted while waiting for Discord 'Server Stopping' message to send."); // Log to file
            }
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
                    logToFile(String.format("Failed to send player count to internal HTTP server. Response code: %d", responseCode)); // Log to file
                }
                connection.disconnect();
            } catch (Exception e) {
                StaffMode.LOGGER.error("Error sending player count to internal bot HTTP server: {}", e.getMessage());
                logToFile("Error sending player count to internal HTTP server: " + e.getMessage()); // Log to file
            }
        }, "PlayerCountSender-Internal").start();
    }

    /**
     * Appends a message to the configured log file if file logging is enabled.
     * Messages are prefixed with a timestamp.
     *
     * @param message The message to log.
     */
    private static void logToFile(String message) {
        if (config != null && config.isLogToFileEnabled() && config.getLogFilePath() != null && !config.getLogFilePath().isEmpty()) {
            try {
                File logFile = new File(config.getLogFilePath());
                // Create parent directories if they don't exist
                if (!logFile.getParentFile().exists()) {
                    logFile.getParentFile().mkdirs();
                }

                try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) { // true for append mode
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    out.println(String.format("[%s] %s", timestamp, message));
                }
            } catch (IOException e) {
                StaffMode.LOGGER.error("Failed to write to log file '{}': {}", config.getLogFilePath(), e.getMessage());
                // Avoid infinite recursion if logging to file fails due to an IO error.
            }
        }
    }
}