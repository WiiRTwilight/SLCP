package com.linyuyuan.slcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public class DownloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:136.0) Gecko/20100101 Firefox/136.0";
    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 300_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 8192;
    private static final String FALLBACK_FILENAME = "download";

    public static void downloadAll(SLCPConfig config, boolean blocking) {
        downloadAll(config, blocking, null);
    }

    public static void downloadAll(SLCPConfig config, boolean blocking, Runnable onComplete) {
        if (config == null || config.isEmpty()) {
            LOGGER.warn("No entries to download");
            if (!blocking && onComplete != null) {
                net.minecraft.client.MinecraftClient.getInstance().execute(onComplete);
            }
            return;
        }

        Runnable task = () -> {
            for (SLCPConfig.Entry entry : config.getEntries()) {
                try {
                    downloadEntry(entry);
                } catch (Exception e) {
                    LOGGER.error("Failed to download [{}]: {}", entry.name(), e.getMessage());
                }
            }
            if (!blocking && onComplete != null) {
                net.minecraft.client.MinecraftClient.getInstance().execute(onComplete);
            }
        };

        if (blocking) {
            task.run();
        } else {
            Thread thread = new Thread(task, "SLCP-Redownload");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void downloadEntry(SLCPConfig.Entry entry) throws IOException {
        String name = entry.name();
        String urlStr = entry.url();
        String outputDir = entry.output();
        Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();

        String filename = extractFilename(urlStr);
        Path outputPath = gameDir.resolve(outputDir).resolve(filename).normalize();

        if (!outputPath.normalize().startsWith(gameDir.normalize())) {
            LOGGER.error("[{}] Path traversal detected, skipping: {}", name, outputPath);
            return;
        }

        Files.createDirectories(outputPath.getParent());

        LOGGER.info("[{}] Downloading: {} -> {}", name, urlStr, outputPath);

        URL url = URI.create(urlStr).toURL();
        int redirectCount = 0;
        HttpURLConnection connection = null;

        while (redirectCount <= MAX_REDIRECTS) {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestMethod("GET");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect with no Location header");
                }
                url = URI.create(location).toURL();
                redirectCount++;
                continue;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode + " for " + url);
            }

            break;
        }

        if (redirectCount > MAX_REDIRECTS) {
            throw new IOException("Too many redirects (> " + MAX_REDIRECTS + ") for " + urlStr);
        }

        long bytesRead;
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            bytesRead = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                bytesRead += read;
            }
        } finally {
            connection.disconnect();
        }

        LOGGER.info("[{}] Download complete: {} bytes -> {}", name, bytesRead, outputPath);
    }

    static String extractFilename(String urlStr) {
        try {
            String path = new URI(urlStr).getPath();
            if (path == null || path.isEmpty() || path.endsWith("/")) {
                return FALLBACK_FILENAME;
            }
            int lastSlash = path.lastIndexOf('/');
            String filename = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
            if (filename.isEmpty()) {
                return FALLBACK_FILENAME;
            }
            return java.net.URLDecoder.decode(filename, java.nio.charset.StandardCharsets.UTF_8);
        } catch (URISyntaxException e) {
            LOGGER.warn("Failed to parse URL for filename extraction: {}", urlStr);
            return FALLBACK_FILENAME;
        }
    }
}
