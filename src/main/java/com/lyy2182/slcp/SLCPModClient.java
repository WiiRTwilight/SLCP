package com.lyy2182.slcp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.ServerList;

import java.security.NoSuchAlgorithmException;

public class SLCPModClient implements ClientModInitializer {
    static ServerList serverList;
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            client = MinecraftClient.getInstance();
            serverList = new ServerList(client);
            try {
                ServersDatMerger.doServerListMerge(serverList);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
