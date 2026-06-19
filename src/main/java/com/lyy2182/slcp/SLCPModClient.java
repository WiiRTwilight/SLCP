package com.lyy2182.slcp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

public class SLCPModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommandManager.literal("slcp")
                            .then(ClientCommandManager.literal("redownload")
                                    .executes(context -> {
                                        SLCPConfig cfg = SLCPMod.config;
                                        if (cfg == null || cfg.isEmpty()) {
                                            context.getSource().sendFeedback(
                                                    Text.translatable("text.slcp.redownload_failed"));
                                            return 0;
                                        }
                                        var source = context.getSource();
                                        DownloadManager.downloadAll(cfg, false, () ->
                                                source.sendFeedback(
                                                        Text.translatable("text.slcp.redownload_done")));
                                        return 1;
                                    })
                            )
            );
        });
    }
}
