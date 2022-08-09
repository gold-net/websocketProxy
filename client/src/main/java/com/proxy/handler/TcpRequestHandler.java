package com.proxy.handler;

import com.proxy.TcpClient;
import com.proxy.util.CommonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class TcpRequestHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpRequestHandler.class);

    protected final TcpClient websocketClient;

    public TcpRequestHandler(TcpClient websocketClient) {
        this.websocketClient = websocketClient;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel proxy = websocketClient.getProxy();
        //客户端发给服务器的
        if (proxy != null && proxy.isActive()) {
            websocketClient.getChannelMap().put(ctx.channel().id().asShortText(), ctx.channel());
            String ip = websocketClient.getServerHost();
            String id = ctx.channel().id().asShortText();
            log.debug("received connect from client, channelId:{}", id);
            byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
            ByteBuf byteBuf = proxy.alloc().buffer().writeBytes(idBytes).writeInt(0).writeByte(ip.length())
                    .writeBytes(ip.getBytes(StandardCharsets.UTF_8)).writeShort(websocketClient.getServerPort());
            byte seed = CommonUtil.getSeed(idBytes);
            for (int i = TcpClient.LENGTH_FIELD_OFFSET + 4; i < byteBuf.readableBytes(); i++) {
                byteBuf.setByte(i, byteBuf.getByte(i) ^ seed);
            }
            proxy.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
        } else {
            ctx.channel().close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        websocketClient.getChannelMap().remove(ctx.channel().id().asShortText());
        Channel proxy = websocketClient.getProxy();
        //客户端发给服务器的
        if (proxy != null && proxy.isActive()) {
            ByteBuf byteBuf = ctx.alloc().buffer().writeBytes(ctx.channel().id().asShortText()
                    .getBytes(StandardCharsets.UTF_8)).writeInt(2).writeShort(0);
            ctx.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
        }
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
        Channel proxy = websocketClient.getProxy();
        //客户端发给服务器的
        if (proxy == null || !proxy.isActive()) {
            ByteBuf byteBuf = ctx.alloc().buffer().writeBytes("channel is not connected.".getBytes(StandardCharsets.UTF_8));
            ctx.writeAndFlush(byteBuf);
            ctx.channel().close();
            log.info("client proxy is not connected, disconnect current client.");
            return;
        }
        String id = ctx.channel().id().asShortText();
        log.debug("received msg from client, length: {}B >>> transfer to proxy, channelId:{}.", in.readableBytes(), id);
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        //扰乱
        byte seed = CommonUtil.getSeed(idBytes);
        for (int i = in.readerIndex(); i < in.readableBytes(); i++) {
            in.setByte(i, in.getByte(i) ^ seed);
        }
        writeMsgSplit(proxy, idBytes, in);
    }

    protected void writeMsgSplit(Channel proxy, byte[] idBytes, ByteBuf in) {
        // tomcat websocket 配置org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE默认最大8192
        int writeSize = 8192 - idBytes.length - 4;
        while (in.readableBytes() > writeSize) {
            ByteBuf byteBuf = proxy.alloc().buffer(8096)
                    .writeBytes(idBytes).writeInt(in.readableBytes()).writeBytes(in, writeSize);
            proxy.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
        }
        ByteBuf byteBuf = proxy.alloc().buffer().writeBytes(idBytes).writeInt(in.readableBytes()).writeBytes(in);
        proxy.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
    }
}
