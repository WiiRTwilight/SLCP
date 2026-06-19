package com.lyy2182.slcp;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SLCPMod implements ModInitializer {

    public static final String MOD_ID = "slcp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static SLCPConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("SLCP initializing...");
        config = SLCPConfig.load();

        if (config != null && !config.isEmpty()) {
            DownloadManager.downloadAll(config, true);
        } else {
            LOGGER.info("No entries to download at startup");
        }
    }
}
