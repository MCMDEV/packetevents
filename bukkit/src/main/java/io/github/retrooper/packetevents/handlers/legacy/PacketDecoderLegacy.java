package io.github.retrooper.packetevents.handlers.legacy;

import io.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.impl.PacketReceiveEvent;
import io.github.retrooper.packetevents.handlers.compression.CompressionManager;
import io.github.retrooper.packetevents.handlers.compression.CustomPacketDecompressor;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.util.netty.buffer.ByteBufAbstract;
import com.github.retrooper.packetevents.util.netty.channel.ChannelHandlerContextAbstract;
import net.minecraft.util.io.netty.buffer.ByteBuf;
import net.minecraft.util.io.netty.channel.ChannelHandlerContext;
import net.minecraft.util.io.netty.handler.codec.ByteToMessageDecoder;
import org.bukkit.entity.Player;

import java.util.List;

public class PacketDecoderLegacy extends ByteToMessageDecoder {
    public volatile Player player;
    public ConnectionState connectionState = ConnectionState.HANDSHAKING;
    public boolean bypassCompression = false;
    private boolean handledCompression;
    private boolean skipDoubleTransform;

    public void handle(ChannelHandlerContextAbstract ctx, ByteBufAbstract byteBuf, List<Object> output) {
        if (skipDoubleTransform) {
            skipDoubleTransform = false;
            output.add(byteBuf.retain().rawByteBuf());
        }
        ByteBufAbstract transformedBuf = ctx.alloc().buffer().writeBytes(byteBuf);
        try {
            boolean needsCompress = !bypassCompression && handleCompressionOrder(ctx, transformedBuf);
            int firstReaderIndex = transformedBuf.readerIndex();
            PacketReceiveEvent packetReceiveEvent = new PacketReceiveEvent(ctx.channel(), player, transformedBuf);
            int readerIndex = transformedBuf.readerIndex();
            PacketEvents.get().getEventManager().callEvent(packetReceiveEvent, () -> {
                transformedBuf.readerIndex(readerIndex);
            });
            if (!packetReceiveEvent.isCancelled()) {
                if (packetReceiveEvent.getLastUsedWrapper() != null) {
                    packetReceiveEvent.getByteBuf().clear();
                    packetReceiveEvent.getLastUsedWrapper().writeVarInt(packetReceiveEvent.getPacketID());
                    packetReceiveEvent.getLastUsedWrapper().writeData();
                }
                transformedBuf.readerIndex(firstReaderIndex);
                if (needsCompress) {
                    CompressionManager.recompress(ctx, transformedBuf);
                    skipDoubleTransform = true;
                }
                output.add(transformedBuf.retain().rawByteBuf());
            }
        } finally {
            transformedBuf.release();
        }
    }

    @Override
    public void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> out) {
        handle(ChannelHandlerContextAbstract.generate(ctx), ByteBufAbstract.generate(byteBuf), out);
    }

    private boolean handleCompressionOrder(ChannelHandlerContextAbstract ctx, ByteBufAbstract buf) {
        if (handledCompression) return false;

        int decoderIndex = ctx.pipeline().names().indexOf("decompress");
        if (decoderIndex == -1) return false;
        handledCompression = true;
        if (decoderIndex > ctx.pipeline().names().indexOf(PacketEvents.get().decoderName)) {
            // Need to decompress this packet due to bad order
            ByteBufAbstract decompressed = CustomPacketDecompressor.decompress(ctx, buf);
            return CompressionManager.refactorHandlers(ctx, buf, decompressed);
        }
        return false;
    }
}
