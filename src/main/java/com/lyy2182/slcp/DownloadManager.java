package com.lyy2182.slcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件下载管理器。
 * <p>
 * 根据配置下载普通文件到磁盘，或将 isServerDat 类型的数据缓存到内存供合并使用。
 * 使用原生 {@link HttpURLConnection}，手动处理重定向。
 */
public class DownloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");
    private static final String USER_AGENT = "SLCP/1.1.0";
    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 300_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 8192;
    private static final String FALLBACK_FILENAME = "download";

    private static final Map<String, byte[]> serverDatBuffers = new ConcurrentHashMap<>();

    /**
     * 获取远程服务器列表数据的只读视图。
     *
     * @return entry name → NBT 字节数据
     */
    public static Map<String, byte[]> getServerDatBuffers() {
        return Collections.unmodifiableMap(serverDatBuffers);
    }

    /** 清空服务器列表数据缓冲区。 */
    public static void clearServerDatBuffers() {
        serverDatBuffers.clear();
    }

    /**
     * 下载所有配置条目（阻塞模式，不带回调）。
     *
     * @param config   下载配置
     * @param blocking 是否在当前线程阻塞执行
     */
    public static void downloadAll(SLCPConfig config, boolean blocking) {
        downloadAll(config, blocking, null);
    }

    /**
     * 下载所有配置条目。
     *
     * @param config     下载配置
     * @param blocking   是否在当前线程阻塞执行
     * @param onComplete 下载完成后的回调（仅非阻塞模式生效）
     */
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
                    LOGGER.error("Failed to download [{}]: {}", entry.name(), e);
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

    /**
     * 下载单个配置条目。
     * <p>
     * isServerDat 条目下载到内存缓冲区，普通条目下载到磁盘。
     *
     * @param entry 配置条目
     */
    private static void downloadEntry(SLCPConfig.Entry entry) throws IOException {
        String name = entry.name();
        String urlStr = entry.url();
        boolean isSD = entry.isServerDat();

        if (isSD) {
            LOGGER.info("[{}] Downloading server list to memory: {}", name, urlStr);
            URL url = URI.create(urlStr).toURL();
            byte[] data = downloadToBytes(url);
            serverDatBuffers.put(name, data);
            LOGGER.info("[{}] Server list buffered: {} bytes", name, data.length);
            return;
        }

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

    /**
     * 下载 URL 内容到内存 byte[]，支持重定向。
     *
     * @param url 目标 URL
     * @return 下载的数据
     */
    private static byte[] downloadToBytes(URL url) throws IOException {
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
            throw new IOException("Too many redirects (> " + MAX_REDIRECTS + ")");
        }

        try (InputStream in = new BufferedInputStream(connection.getInputStream());
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 从 URL 中提取文件名。
     *
     * @param urlStr URL 字符串
     * @return 提取的文件名，无法提取时返回 {@link #FALLBACK_FILENAME}
     */
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
