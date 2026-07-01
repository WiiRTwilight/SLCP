package com.lyy2182.slcp;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLCP 模组主入口。
 * <p>
 * 在服务端和客户端启动时加载配置并执行文件下载。
 */
public class SLCPMod implements ModInitializer {

    public static final String MOD_ID = "slcp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static SLCPConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("SLCP initializing...");
        config = SLCPConfig.load();

        if (!config.isEmpty()) {
            DownloadManager.downloadAll(config, true);
        } else {
            LOGGER.info("No entries to download at startup");
        }
    }
}
