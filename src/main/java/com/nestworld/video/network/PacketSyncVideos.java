package com.nestworld.video.network;

import com.nestworld.video.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The whole catalogue, gzip-compressed and sent in chunks (one S2C packet may not exceed 1 MiB, and an
 * uncompressed catalogue of many anime easily does). The client reassembles the chunks of the newest transfer.
 */
public class PacketSyncVideos {
    /** Limit for uses that still send plain JSON text (browse results). */
    public static final int MAX_LENGTH = 1_000_000;
    static final int CHUNK_BYTES = 256 * 1024, MAX_CHUNKS = 64;
    static final int MAX_JSON_BYTES = 64 * 1024 * 1024;
    private static int nextTransfer;

    private final int transfer, index, count;
    private final byte[] data;

    PacketSyncVideos(int transfer, int index, int count, byte[] data) {
        this.transfer = transfer; this.index = index; this.count = count; this.data = data;
    }

    /** Compressed size of a catalogue in bytes, or -1 when it would not fit in MAX_CHUNKS chunks. */
    public static int compressedSize(String json) throws IOException {
        int size = gzip(json).length;
        return size > CHUNK_BYTES * MAX_CHUNKS ? -1 : size;
    }

    public static void send(ServerPlayer player, String json) {
        try {
            byte[] bytes = gzip(json);
            int count = Math.max(1, (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
            if (count > MAX_CHUNKS) throw new IOException("Каталог завеликий");
            int transfer;
            synchronized (PacketSyncVideos.class) { transfer = ++nextTransfer; }
            for (int i = 0; i < count; i++) {
                int from = i * CHUNK_BYTES, to = Math.min(bytes.length, from + CHUNK_BYTES);
                Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                        new PacketSyncVideos(transfer, i, count, java.util.Arrays.copyOfRange(bytes, from, to)));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static byte[] gzip(String json) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) { gz.write(json.getBytes(StandardCharsets.UTF_8)); }
        return out.toByteArray();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(transfer); buffer.writeVarInt(index); buffer.writeVarInt(count);
        buffer.writeByteArray(data);
    }

    public static PacketSyncVideos decode(FriendlyByteBuf buffer) {
        int transfer = buffer.readVarInt(), index = buffer.readVarInt(), count = buffer.readVarInt();
        return new PacketSyncVideos(transfer, index, count, buffer.readByteArray(CHUNK_BYTES));
    }

    /** Client side: the transfer currently being reassembled. Touched on the client thread only. */
    private static int receivingTransfer = -1;
    private static byte[][] received;

    public static void handle(PacketSyncVideos message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> message.receive()));
        context.setPacketHandled(true);
    }

    private void receive() {
        if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count) return;
        if (transfer != receivingTransfer || received == null || received.length != count) {
            receivingTransfer = transfer; received = new byte[count][];
        }
        received[index] = data;
        for (byte[] part : received) if (part == null) return;
        byte[][] parts = received;
        received = null; receivingTransfer = -1;
        try {
            var joined = new ByteArrayOutputStream();
            for (byte[] part : parts) joined.write(part);
            String json;
            try (var in = new GZIPInputStream(new ByteArrayInputStream(joined.toByteArray()))) {
                json = new String(in.readNBytes(MAX_JSON_BYTES), StandardCharsets.UTF_8);
            }
            ClientPacketHandler.handleSyncVideos(json);
        } catch (IOException e) {
            com.mojang.logging.LogUtils.getLogger().error("CLIENT: could not unpack the video catalogue", e);
        }
    }
}
