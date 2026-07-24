package com.lyy2182.slcp.processors;

import com.lyy2182.slcp.SLCPConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class ServersDatMergeProcessor implements Processors {

    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");

    @Override
    public SLCPConfig.Entry preProcessor(SLCPConfig.Entry data) {
        return data;
    }

    @Override
    public InputStream postProcessor(SLCPConfig.Entry entry, InputStream data) {
        String name = entry.name();
        LOGGER.info("[{}] Processing server list merge...", name);
        try {
            byte[] rawBytes = data.readAllBytes();
            if (rawBytes.length == 0) {
                LOGGER.warn("[{}] Downloaded server data is empty, skipping merge", name);
                return new ByteArrayInputStream(new byte[0]);
            }

            List<NbtCompound> remoteServers = parseRemoteServers(rawBytes);
            LOGGER.info("[{}] Parsed {} servers from remote", name, remoteServers.size());

            if (remoteServers.isEmpty()) {
                LOGGER.info("[{}] No servers in remote data, skipping merge", name);
                return new ByteArrayInputStream(rawBytes);
            }

            List<NbtCompound> localServers = readLocalServersDat();
            LOGGER.info("[{}] Local servers loaded: {}", name, localServers.size());

            List<NbtCompound> merged = mergeServerLists(localServers, remoteServers);
            LOGGER.info("[{}] Merged servers: {}", name, merged.size());

            writeServersDat(merged);
            LOGGER.info("[{}] Server list merge complete", name);
        } catch (IOException e) {
            LOGGER.error("[{}] Failed to merge server list: {}", name, e.getMessage());
        }

        return new ByteArrayInputStream(new byte[0]);
    }

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

    private static List<NbtCompound> parseRemoteServers(byte[] data) {
        if (data == null || data.length == 0) {
            return new ArrayList<>();
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            NbtCompound root = NbtIo.readCompound(
                    new DataInputStream(bais), NbtSizeTracker.ofUnlimitedBytes());
            return extractServersFromRoot(root);
        } catch (IOException e) {
            LOGGER.error("Failed to parse remote server data bytes", e);
            return new ArrayList<>();
        }
    }

    private static List<NbtCompound> extractServersFromRoot(NbtCompound root) {
        List<NbtCompound> servers = new ArrayList<>();
        if (root == null) {
            return servers;
        }
        Optional<NbtList> optList = root.getList("servers");
        if (optList.isEmpty()) {
            return servers;
        }
        for (NbtElement elem : optList.get()) {
            if (elem instanceof NbtCompound compound) {
                servers.add(compound.copy());
            }
        }
        return servers;
    }

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
                LOGGER.info("Local server '{}' ({}) conflicts with remote, removing", name, ip);
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
