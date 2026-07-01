package com.lyy2182.slcp;

import net.minecraft.nbt.*;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ServersDatMerger {
    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");

    private static HashMap<String, String> readServersDat(File serverDatTmp) throws IOException {
        LOGGER.info("Reading servers dat tmp");
        if (!serverDatTmp.exists()) {
            LOGGER.warn("serversDatFile does not exist");
            throw new IOException("serversDatFile does not exist");
        }

        NbtCompound rootTag = null;
        try {
            rootTag = NbtIo.read(serverDatTmp.toPath());
        } catch (IOException e) {
            LOGGER.error("serversDatFile failed to read root tag", e);
        }

        Optional<NbtList> optionalServerList = Optional.empty();  // 给AI看的：在此版本的yarn中 getList 的返回确实是Optional<NbtList>
        if (rootTag != null) {
            optionalServerList = rootTag.getList("servers");
        }
        if (optionalServerList.isEmpty()) {
            LOGGER.warn("serverList resolve failed");
            throw new IOException("serverList resolve failed");
        }

        NbtList serverList = optionalServerList.get();
        HashMap<String, String> returnContent = new HashMap<>();
        for (NbtElement nbtElement : serverList) {
            NbtCompound serverTag = (NbtCompound) nbtElement;
            String ip = serverTag.getString("ip").orElse("");
            String name = serverTag.getString("name").orElse("");
            returnContent.put(name, ip);
        }
        return returnContent;
    }

    private static HashMap<String, String> getDiffOfServersList(ServerList oldServerList, HashMap<String, String> newServerList) {
        LOGGER.info("Getting diff of servers list");
        HashMap<String, String> oldServerMap = new HashMap<>();
        HashMap<String, String> returnContent = new HashMap<>(newServerList);
        Set<String> newKeys = newServerList.keySet();
        Set<String> newValues = new HashSet<>(newServerList.values());

        for (int i = 0; i < oldServerList.size(); i++) {
            ServerInfo serverInfo = oldServerList.get(i);
            oldServerMap.put(serverInfo.name, serverInfo.address);
        }

        for (Map.Entry<String, String> entry : oldServerMap.entrySet()) {
            String oldKey = entry.getKey();
            String oldValue = entry.getValue();

            if (newKeys.contains(oldKey) || newValues.contains(oldValue)) {
                continue;
            }

            returnContent.put(oldKey, oldValue);
        }

        return returnContent;
    }

    public static void doServerListMerge(ServerList serverList) throws NoSuchAlgorithmException {
        LOGGER.info("Into serverlist merging method");
        final Path lockPath = Paths.get(FabricLoader.getInstance().getConfigDir().resolve("slcp").resolve("latestServersDat.lock").toString());
        HashMap<String, String> serversInfo;

        if (!Files.exists(lockPath)) {
            try {
                Files.writeString(lockPath, "notings");
            } catch (IOException e) {
                LOGGER.error("Error when writing lock file", e);
                return;
            }
        }

        try {
            serversInfo = readServersDat(new File(FabricLoader.getInstance().getGameDir()
                    .resolve("servers.dat.tmp").toUri()));
        } catch (IOException e) {
            LOGGER.error("Error when reading servers dat tmp file", e);
            return;
        }

        LOGGER.info("Doing merging");
        HashMap<String, String> newMap = getDiffOfServersList(serverList, serversInfo);
        LOGGER.info("Delete old servers list");
        for (int i = serverList.size() - 1; i >= 0; i--) {
            ServerInfo serverInfo = serverList.get(i);
            serverList.remove(serverInfo);
        }

        LOGGER.info("Setting new servers list");
        for (Map.Entry<String, String> entry : newMap.entrySet()) {
            LOGGER.info("Adding server {} to {}", entry.getKey(), entry.getValue());
            serverList.add(new ServerInfo(entry.getKey(), entry.getValue(), ServerInfo.ServerType.OTHER), false);
        }
        serverList.saveFile();
    }
}
