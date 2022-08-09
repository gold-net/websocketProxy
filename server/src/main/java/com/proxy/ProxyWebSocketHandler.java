package com.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Configuration
public class ProxyWebSocketHandler extends AbstractWebSocketHandler implements DisposableBean, InitializingBean {

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        ServletWebSocketHandlerRegistry registry = new ServletWebSocketHandlerRegistry();
        registry.addHandler(this, "/ws").setAllowedOrigins("*");
        return registry.getHandlerMapping();
    }

    private final Logger log = LoggerFactory.getLogger(ProxyWebSocketHandler.class);

    private final Map<WebSocketSession, Map<String, ChannelWrap>> sessionMap = new ConcurrentHashMap<>();

    private final EventLoopGroup loopGroup = new NioEventLoopGroup();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionMap.put(session, new ConcurrentHashMap<>());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("ConnectionClosed: {}", status);
        Map<String, ChannelWrap> remove = sessionMap.remove(session);
        for (ChannelWrap value : remove.values()) {
            if (value.channelFuture.channel().isActive()) {
                value.channelFuture.channel().close();
            }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        //需要对同一会话做同步处理，tomcat的websocket接收消息是多个线程处理的
        ByteBuffer payload = message.getPayload();
        byte[] idByte = new byte[8];
        payload.get(idByte);
        String clientId = new String(idByte);
        int size = payload.getInt();
        Map<String, ChannelWrap> clientMap = sessionMap.get(session);
        if (size == 2 && payload.remaining() == 2) {
            if (payload.getShort() == 0) {
                //关闭
                for (Map.Entry<String, ChannelWrap> entry : clientMap.entrySet()) {
                    if (entry.getValue().getClientId().equals(clientId)) {
                        entry.getValue().getChannelFuture().channel().close();
                    }
                }
                return;
            }
            payload.position(payload.position() - 2);
        }
        if (size == 0 && payload.hasRemaining()) {
            int se = getSeed(idByte);
            byte i = (byte) (payload.get() ^ se);
            byte[] addressBytes = new byte[i];
            for (int i1 = 0; i1 < i; i1++) {
                addressBytes[i1] = (byte) (payload.get() ^ se);
            }
            byte[] portBytes = new byte[2];
            portBytes[0] = (byte) (payload.get() ^ se);
            portBytes[1] = (byte) (payload.get() ^ se);
            //连接服务
            String address = new String(addressBytes);
            short port = (short) (portBytes[0] << 8 | portBytes[1] & 0xFF);
            ChannelFuture channelFuture = new Bootstrap().group(loopGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new Server2ClientHandler(session));
                        }
                    }).connect(address, port);
            String id = channelFuture.channel().id().asShortText();
            clientMap.put(id, new ChannelWrap(channelFuture, clientId));
            channelFuture.addListener((ChannelFutureListener) future -> {
                if (future != null && future.isSuccess()) {
                    log.debug("target server: {} connect success, clientChannelId: {}", future.channel().remoteAddress(), clientId);
                } else {
                    log.info("target server connect failed: {}:{}", address, port);
                    byte[] res = new byte[14];
                    System.arraycopy(idByte, 0, res, 0, idByte.length);
                    setInt(res, 8, 2);
                    res[13] = 0;
                    synchronized (session) {
                        session.sendMessage(new BinaryMessage(res));
                    }
                    clientMap.remove(id);
                }
            });
            return;
        }
        for (Map.Entry<String, ChannelWrap> entry : clientMap.entrySet()) {
            if (entry.getValue().getClientId().equals(clientId)) {
                entry.getValue().getChannelFuture().addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        int se = getSeed(idByte);
                        ByteBuf byteBuf = future.channel().alloc().buffer();
                        while (payload.hasRemaining()) {
                            byteBuf.writeByte(payload.get() ^ se);
                        }
                        log.debug("received msg from client: {}, length: {}", clientId, byteBuf.readableBytes());
                        future.channel().writeAndFlush(byteBuf);
                    }
                });
            }
        }
    }

    @Override
    public void destroy() {
        sessionMap.clear();
        loopGroup.shutdownGracefully();
    }

    @Override
    public void afterPropertiesSet() {
        loopGroup.scheduleAtFixedRate(() -> {
            int clientSize = 0;
            for (Map.Entry<WebSocketSession, Map<String, ChannelWrap>> entry : sessionMap.entrySet()) {
                if (!entry.getKey().isOpen()) {
                    Map<String, ChannelWrap> remove = sessionMap.remove(entry.getKey());
                    for (ChannelWrap value : remove.values()) {
                        if (value.channelFuture.channel().isActive()) {
                            value.channelFuture.channel().close();
                        }
                    }
                } else {
                    for (Map.Entry<String, ChannelWrap> wrapEntry : entry.getValue().entrySet()) {
                        if (!wrapEntry.getValue().channelFuture.channel().isActive()) {
                            entry.getValue().remove(wrapEntry.getKey());
                        } else {
                            clientSize++;
                        }
                    }
                }
            }
            log.info("connected websocket size: {}, connected server size: {}", sessionMap.size(), clientSize);
        }, 5, 5, TimeUnit.MINUTES);
    }

    public class Server2ClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private final WebSocketSession session;

        public Server2ClientHandler(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx2, ByteBuf in) throws Exception {
            Map<String, ChannelWrap> clientMap = sessionMap.get(session);
            String idStr = clientMap.get(ctx2.channel().id().asShortText()).getClientId();
            log.debug("received msg from target server: [{}], length: {}B, clientChannelId: {}", ctx2.channel().remoteAddress(), in.readableBytes(), idStr);
            byte[] id = idStr.getBytes(StandardCharsets.UTF_8);
            byte[] res = new byte[id.length + 4 + in.readableBytes()];
            System.arraycopy(id, 0, res, 0, id.length);
            setInt(res, id.length, in.readableBytes());
            int se = getSeed(id);
            int i = id.length + 4;
            while (in.readableBytes() > 0) {
                res[i] = (byte) (in.readByte() ^ se);
                i++;
            }
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new BinaryMessage(res));
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx2) throws Exception {
            Map<String, ChannelWrap> clientMap = sessionMap.get(session);
            if (clientMap != null) {
                ChannelWrap wrap = clientMap.remove(ctx2.channel().id().asShortText());
                if (wrap != null) {
                    byte[] idByte = wrap.getClientId().getBytes(StandardCharsets.UTF_8);
                    String clientId = new String(idByte);
                    log.debug("target server {} disconnected, clientChannelId: {}", ctx2.channel(), clientId);
                    byte[] res = new byte[14];
                    System.arraycopy(idByte, 0, res, 0, idByte.length);
                    setInt(res, 8, 2);
                    res[13] = 0;
                    if (session.isOpen()) {
                        synchronized (session) {
                            session.sendMessage(new BinaryMessage(res));
                        }
                    }
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error(cause.getMessage() + ctx.channel().remoteAddress(), cause);
        }
    }

    byte getSeed(byte[] bytes) {
        return bytes[3];
    }

    void setInt(byte[] memory, int index, int value) {
        memory[index] = (byte) (value >>> 24);
        memory[index + 1] = (byte) (value >>> 16);
        memory[index + 2] = (byte) (value >>> 8);
        memory[index + 3] = (byte) value;
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
