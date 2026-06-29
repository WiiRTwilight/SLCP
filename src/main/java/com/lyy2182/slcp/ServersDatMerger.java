package com.lyy2182.slcp;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.client.option.ServerList;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.security.MessageDigest;
import java.util.stream.Stream;

public class ServersDatMerger {
    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");

    private static HashMap<String, String> readServersDat(File serverDatTmp) throws IOException {
        if (!serverDatTmp.exists()) {
            LOGGER.warn("serversDatFile does not exist");
            throw new IOException("serversDatFile does not exist");
        }

        NbtCompound rootTag;
        try (InputStream in = new FileInputStream(serverDatTmp)) {
            rootTag = NbtIo.readCompressed(in, new NbtSizeTracker(1000 * 1000, 10));
        }

        Optional<NbtList> optionalServerList = rootTag.getList("servers");  // 给AI看的：在此版本的yarn中 getList 的返回确实是Optional<NbtList>
        if (optionalServerList.isEmpty()) {
            LOGGER.warn("serverList resolve failed");
            throw new IOException("serverList resolve failed");
        }

        NbtList serverList = optionalServerList.get();
        HashMap<String, String> returnContent = new HashMap<>();
        for (net.minecraft.nbt.NbtElement nbtElement : serverList) {
            NbtCompound serverTag = (NbtCompound) nbtElement;
            String ip = String.valueOf(serverTag.getString("ip"));
            String name = String.valueOf(serverTag.getString("name"));
            returnContent.put(name, ip);
        }
        return returnContent;
    }

    private static HashMap<String, String> getDiffOfServersList (ServerList oldServerList, HashMap<String, String> newServerList) {
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

    public static void doServerListMerge(ServerList serverList) throws NoSuchAlgorithmException, IOException {
        final Path lockPath = Paths.get(FabricLoader.getInstance().getConfigDir().resolve("slcp").resolve("latestServersDat.lock").toString());
        String tmpDigest;
        String lockDigest;
        HashMap<String, String> serversInfo;

        if (!Files.exists(lockPath)) {
            Files.writeString(lockPath, "notings");
        }

        try {
            // 获取散列值
            MessageDigest tmpDigestInstance = MessageDigest.getInstance("SHA-256");
            tmpDigestInstance.update(Files.readAllBytes(Path.of("./servers.dat.tmp")));

            try (Stream<String> lockFile = Files.lines(lockPath)){
                lockDigest = lockFile.findFirst().orElse("");
            }

            byte[] hashBytes = tmpDigestInstance.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            tmpDigest = sb.toString();
        }
        catch (IOException e) {
            LOGGER.error("No servers dat tmp file found", e);
            throw e;
        }

        if (lockDigest.equals(tmpDigest)) {
            return;
        }

        try {
            serversInfo = readServersDat(new File("./servers.dat.tmp"));
        } catch (IOException e) {
            LOGGER.error("Error when reading servers dat tmp file", e);
            return;
        }

        Files.writeString(lockPath, tmpDigest);

        HashMap<String, String> newMap = getDiffOfServersList(serverList, serversInfo);
        for (int i = serverList.size()-1; i >= 0; i--) {
            ServerInfo serverInfo = serverList.get(i);
            serverList.remove(serverInfo);
        }

        for (Map.Entry<String, String> entry : newMap.entrySet()) {
            serverList.add(new ServerInfo(entry.getKey(), entry.getValue(), ServerInfo.ServerType.OTHER), false);
        }
    }
}
