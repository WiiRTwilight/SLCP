package com.lyy2182.slcp;

import net.minecraft.nbt.*;
import net.minecraft.client.option.ServerList;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 服务器列表合并器。
 * <p>
 * 全内存操作：读取本地 servers.dat，获取远程下载的服务器数据，
 * 以 IP 为唯一键进行差异对比后合并，最终写回 servers.dat。
 */
public class ServersDatMerger {
    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");

    /**
     * 执行服务器列表合并。
     *
     * @param serverList 当前客户端的服务器列表，合并后会重新加载
     */
    public static void doServerListMerge(ServerList serverList) {
        LOGGER.info("Starting server list merge...");

        List<NbtCompound> localServers = readLocalServersDat();
        LOGGER.info("Local servers loaded: {}", localServers.size());

        Map<String, byte[]> remoteBuffers = DownloadManager.getServerDatBuffers();
        if (remoteBuffers.isEmpty()) {
            LOGGER.info("No remote server data to merge");
            return;
        }

        List<NbtCompound> remoteServers = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : remoteBuffers.entrySet()) {
            List<NbtCompound> parsed = parseServerDatBytes(entry.getValue());
            LOGGER.info("[{}] Parsed {} servers from remote", entry.getKey(), parsed.size());
            remoteServers.addAll(parsed);
        }
        LOGGER.info("Remote servers total: {}", remoteServers.size());

        List<NbtCompound> merged = mergeServerLists(localServers, remoteServers);
        LOGGER.info("Merged servers: {}", merged.size());

        writeServersDat(merged);
        serverList.loadFile();
        DownloadManager.clearServerDatBuffers();
    }

    /**
     * 读取本地 servers.dat 并解析为服务器条目列表。
     *
     * @return 服务器 NBT 条目列表，文件不存在或读取失败时返回空列表
     */
    private static List<NbtCompound> readLocalServersDat() {
        Path serversDat = FabricLoader.getInstance().getGameDir().resolve("servers.dat");
        if (!Files.exists(serversDat)) {
            LOGGER.info("No local servers.dat found");
            return new ArrayList<>();
        }
        try {
            NbtCompound root = NbtIo.read(serversDat);
            return extractServersFromRoot(root);
        } catch (IOException e) {
            LOGGER.error("Failed to read local servers.dat", e);
            return new ArrayList<>();
        }
    }

    /**
     * 将原始 NBT 字节解析为服务器条目列表。
     *
     * @param data 未压缩的 NBT 二进制数据
     * @return 服务器 NBT 条目列表
     */
    private static List<NbtCompound> parseServerDatBytes(byte[] data) {
        if (data == null || data.length == 0)
            return new ArrayList<>();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            NbtCompound root = NbtIo.readCompound(new DataInputStream(bais), NbtSizeTracker.ofUnlimitedBytes());
            return extractServersFromRoot(root);
        } catch (IOException e) {
            LOGGER.error("Failed to parse server data bytes", e);
            return new ArrayList<>();
        }
    }

    /**
     * 从 NBT 根节点的 "servers" 列表中提取所有服务器条目。
     *
     * @param root NBT 根节点
     * @return 服务器条目列表（深拷贝）
     */
    private static List<NbtCompound> extractServersFromRoot(NbtCompound root) {
        List<NbtCompound> servers = new ArrayList<>();
        if (root == null)
            return servers;

        Optional<NbtList> optList = root.getList("servers");
        if (optList.isEmpty())
            return servers;

        NbtList list = optList.get();
        for (NbtElement elem : list) {
            if (elem instanceof NbtCompound compound) {
                servers.add(compound.copy());
            }
        }
        return servers;
    }

    /**
     * 合并本地和远程服务器列表。以 IP 为唯一键：
     * 本地 IP 与远程冲突时丢弃本地版本，否则保留；远程全部保留。
     *
     * @param local  本地服务器列表
     * @param remote 远程服务器列表
     * @return 合并后的服务器列表
     */
    private static List<NbtCompound> mergeServerLists(List<NbtCompound> local, List<NbtCompound> remote) {
        Set<String> remoteIps = new HashSet<>();
        for (NbtCompound server : remote) {
            server.getString("ip").ifPresent(remoteIps::add);
        }

        List<NbtCompound> result = new ArrayList<>();

        for (NbtCompound server : local) {
            String name = server.getString("name").orElse("");
            String ip = server.getString("ip").orElse("");

            if (remoteIps.contains(ip)) {
                LOGGER.info("Local server '{}' ({}) IP conflicts with remote, skipping", name, ip);
                continue;
            }
            LOGGER.info("Keeping local server '{}' ({})", name, ip);
            result.add(server);
        }

        for (NbtCompound server : remote) {
            String name = server.getString("name").orElse("");
            String ip = server.getString("ip").orElse("");
            LOGGER.info("Adding remote server '{}' ({})", name, ip);
            result.add(server);
        }

        return result;
    }

    /**
     * 将合并后的服务器列表构建为 NBT 并写入 servers.dat。
     * <p>
     * NBT 结构：
     * <pre>
     * TAG_Compound (root)
     *   └─ TAG_List "servers"
     *        └─ TAG_Compound (each server)
     *             ├─ TAG_Byte   "hidden"        = 0
     *             ├─ TAG_String "ip"             = ...
     *             ├─ TAG_String "name"           = ...
     *             ├─ TAG_String "viaForge$version"          (optional)
     *             ├─ TAG_String "viafabricplus_forcedversion" (optional)
     *             └─ TAG_End
     * </pre>
     *
     * @param servers 合并后的服务器列表
     */
    private static void writeServersDat(List<NbtCompound> servers) {
        NbtCompound root = new NbtCompound();
        NbtList serverList = new NbtList();
        for (NbtCompound server : servers) {
            serverList.add(server);
        }
        root.put("servers", serverList);

        Path serversDat = FabricLoader.getInstance().getGameDir().resolve("servers.dat");
        try {
            NbtIo.write(root, serversDat);
            LOGGER.info("Wrote servers.dat with {} servers", servers.size());
        } catch (IOException e) {
            LOGGER.error("Failed to write servers.dat", e);
        }
    }
}
