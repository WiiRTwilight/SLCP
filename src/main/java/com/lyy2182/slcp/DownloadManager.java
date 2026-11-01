package com.lyy2182.slcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Modifier;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;

import com.lyy2182.slcp.processors.Processors;

/**
 * 文件下载管理器。
 * <p>
 * 根据配置下载文件到磁盘。使用原生 {@link HttpURLConnection}，手动处理重定向。
 */
public class DownloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");
    private static final String USER_AGENT = "SLCP/1.1.0";
    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 300_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 8192;
    private static final String FALLBACK_FILENAME = "download";

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
     * 下载单个配置条目到磁盘。先解析处理器(如有配置)并执行preProcessor,
     * 再发起HTTP请求下载文件,经postProcessor处理后写入磁盘。
     *
     * @param entry 配置条目
     */
    private static void downloadEntry(SLCPConfig.Entry entry) throws IOException {
        String name = entry.name();
        String urlStr = entry.url();

        // 解析并实例化处理器,未配置或加载失败时返回null
        Processors processor = resolveProcessor(entry);
        if (processor != null) {
            entry = processor.preProcessor(entry);
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
        // 使用嵌套try-with-resources确保postProcessor返回的流和磁盘输出流都被正确关闭
        try (InputStream rawIn = new BufferedInputStream(connection.getInputStream())) {
            try (InputStream source = (processor != null) ? processor.postProcessor(entry, rawIn) : rawIn;
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                bytesRead = 0;
                while ((read = source.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    bytesRead += read;
                }
            }
        } finally {
            connection.disconnect();
        }

        LOGGER.info("[{}] Download complete: {} bytes -> {}", name, bytesRead, outputPath);
    }

    /**
     * 解析并实例化配置中指定的预处理器/后处理器。
     * <p>
     * 若jar为"_this"则从mod自身classpath加载处理器类;
     * 否则从指定的外部jar文件创建URLClassLoader加载。
     * 若未配置处理器(jar或clazz为空)或加载失败,返回null,下载将跳过处理器。
     *
     * @param entry 配置条目
     * @return 处理器实例,或null表示不使用处理器
     */
    private static Processors resolveProcessor(SLCPConfig.Entry entry) {
        String jar = entry.jar();
        String clazzName = entry.clazz();
        if (jar.isEmpty() || clazzName.isEmpty()) {
            return null;
        }

        URLClassLoader loader = null;
        try {
            ClassLoader parent;
            if (!jar.equals("_this")) {
                // 从外部jar文件加载处理器
                URL jarUrl = new File(jar).toURI().toURL();
                loader = new URLClassLoader(new URL[]{jarUrl},
                        SLCPMod.class.getClassLoader());
                parent = loader;
            } else {
                // 使用mod自身的classloader加载内置处理器
                parent = SLCPMod.class.getClassLoader();
            }

            Class<?> clz = parent.loadClass(clazzName);

            // 接口或抽象类无法实例化,跳过处理器
            if (clz.isInterface()
                    || Modifier.isAbstract(clz.getModifiers())
                    || !Processors.class.isAssignableFrom(clz)) {
                LOGGER.warn("[{}] Processor class {} is not instantiable, skipping processor",
                        entry.name(), clazzName);
                return null;
            }

            return (Processors) clz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOGGER.warn("[{}] Failed to load processor {}: {}",
                    entry.name(), clazzName, e.getMessage());
            return null;
        } finally {
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                }
            }
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
