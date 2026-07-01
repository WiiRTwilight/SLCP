package com.lyy2182.slcp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.ServerList;

/**
 * SLCP 客户端入口。
 * <p>
 * 在客户端启动后触发服务器列表合并。
 */
public class SLCPModClient implements ClientModInitializer {
    static ServerList serverList;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            client = MinecraftClient.getInstance();
            serverList = new ServerList(client);
            ServersDatMerger.doServerListMerge(serverList);
        });
    }
}
