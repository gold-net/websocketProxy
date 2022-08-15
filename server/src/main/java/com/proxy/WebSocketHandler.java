package com.proxy;


import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    static Logger log = LoggerFactory.getLogger(WebSocketHandler.class);

    private final Map<String, ChannelWrap> clientMap = new ConcurrentHashMap<>();

    private final EventLoopGroup loopGroup = new NioEventLoopGroup();


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            ctx.writeAndFlush(frame.retain()).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            ByteBuf payload = frame.content();
            byte[] idByte = new byte[8];
            payload.readBytes(idByte);
            String clientId = new String(idByte);
            int size = payload.readInt();
            if (size == 2 && payload.readableBytes() == 2) {
                if (payload.getShort(payload.readerIndex()) == 0) {
                    //关闭
                    for (Map.Entry<String, ChannelWrap> entry : clientMap.entrySet()) {
                        if (entry.getValue().getClientId().equals(clientId)) {
                            entry.getValue().getChannelFuture().channel().close();
                        }
                    }
                    return;
                }
            }
            if (size == 0 && payload.readableBytes() > 0) {
                //连接服务
                int se = getSeed(idByte);
                byte i = (byte) (payload.readByte() ^ se);
                byte[] addressBytes = new byte[i];
                for (int i1 = 0; i1 < i; i1++) {
                    addressBytes[i1] = (byte) (payload.readByte() ^ se);
                }
                byte[] portBytes = new byte[2];
                portBytes[0] = (byte) (payload.readByte() ^ se);
                portBytes[1] = (byte) (payload.readByte() ^ se);
                //连接服务
                String address = new String(addressBytes);
                short port = (short) (portBytes[0] << 8 | portBytes[1] & 0xFF);
                ChannelFuture channelFuture = new Bootstrap().group(loopGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new Server2ClientHandler(ctx.channel()));
                            }
                        }).connect(address, port);
                String id = channelFuture.channel().id().asShortText();
                clientMap.put(id, new ChannelWrap(channelFuture, clientId));
                channelFuture.addListener((ChannelFutureListener) future -> {
                    if (future != null && future.isSuccess()) {
                        log.debug("target server: {} connect success, clientChannelId: {}", future.channel().remoteAddress(), clientId);
                    } else {
                        log.info("target server connect failed: {}:{}", address, port);
                        ByteBuf byteBuf = ctx.alloc().buffer().writeBytes(ctx.channel().id().asShortText()
                                .getBytes(StandardCharsets.UTF_8)).writeInt(2).writeShort(0);
                        ctx.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
                        clientMap.remove(id);
                    }
                });
                return;
            }
            for (Map.Entry<String, ChannelWrap> entry : clientMap.entrySet()) {
                if (entry.getValue().getClientId().equals(clientId)) {
                    payload.retain();
                    entry.getValue().getChannelFuture().addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            int se = getSeed(idByte);
                            ByteBuf byteBuf = future.channel().alloc().buffer();
                            while (payload.readableBytes() > 0) {
                                byteBuf.writeByte(payload.readByte() ^ se);
                            }
                            payload.release();
                            log.debug("received msg from client: {}, length: {}", clientId, byteBuf.readableBytes());
                            future.channel().writeAndFlush(byteBuf);
                        }
                    });
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error(cause.getMessage(), cause);
    }

    byte getSeed(byte[] bytes) {
        return bytes[3];
    }

    public class Server2ClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private final Channel channel;

        public Server2ClientHandler(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx2, ByteBuf in) {
            String idStr = clientMap.get(ctx2.channel().id().asShortText()).getClientId();
            log.debug("received msg from target server: [{}], length: {}B, clientChannelId: {}", ctx2.channel().remoteAddress(), in.readableBytes(), idStr);
            byte[] id = idStr.getBytes(StandardCharsets.UTF_8);
            //扰乱
            byte seed = getSeed(id);

            ByteBuf byteBuf = channel.alloc().buffer().writeBytes(id).writeInt(in.readableBytes());
            while (in.readableBytes() > 0) {
                byteBuf.writeByte(in.readByte() ^ seed);
            }
            channel.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx2) {
            ChannelWrap wrap = clientMap.remove(ctx2.channel().id().asShortText());
            if (wrap != null) {
                byte[] idByte = wrap.getClientId().getBytes(StandardCharsets.UTF_8);
                ByteBuf byteBuf = channel.alloc().buffer().writeBytes(idByte).writeInt(2).writeShort(0);
                channel.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
                log.debug("target server {} disconnected, clientChannelId: {}", ctx2.channel(), wrap.getClientId());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error(cause.getMessage() + ctx.channel().remoteAddress(), cause);
        }
    }

    public static class ChannelWrap {

        public ChannelWrap(ChannelFuture future, String clientId) {
            this.channelFuture = future;
            this.clientId = clientId;
        }

        private final ChannelFuture channelFuture;

        private final String clientId;

        public ChannelFuture getChannelFuture() {
            return channelFuture;
        }

        public String getClientId() {
            return clientId;
        }

    }
}
