package com.proxy.handler;

import com.proxy.TcpClient;
import com.proxy.util.CommonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WebSocketHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(WebSocketHandler.class);
    private ChannelPromise handShakeFuture;
    private final WebSocketClientHandshaker handShaker;
    private final TcpClient client;

    public WebSocketHandler(WebSocketClientHandshaker handShaker, TcpClient client) {
        super(false);
        this.handShaker = handShaker;
        this.client = client;
    }

    public ChannelFuture handshakeFuture() {
        return this.handShakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.handShakeFuture = ctx.newPromise();
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handShaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("disconnected from proxy, reconnect after 30 seconds.");
        ctx.executor().schedule(client::connectProxy, 30, TimeUnit.SECONDS);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        Channel ch = ctx.channel();
        FullHttpResponse response;
        if (!this.handShaker.isHandshakeComplete()) {
            try {
                response = (FullHttpResponse) msg;
                //握手协议返回，设置结束握手
                this.handShaker.finishHandshake(ch, response);
                //设置成功
                this.handShakeFuture.setSuccess();
                response.release();
            } catch (WebSocketHandshakeException var7) {
                FullHttpResponse res = (FullHttpResponse) msg;
                String errorMsg = String.format("WebSocket Client failed to connect,status:%s,reason:%s", res.status(), res.content().toString(CharsetUtil.UTF_8));
                this.handShakeFuture.setFailure(new Exception(errorMsg));
            }
        } else if (msg instanceof FullHttpResponse) {
            response = (FullHttpResponse) msg;
            response.release();
            throw new IllegalStateException("Unexpected FullHttpResponse (getStatus=" + response.status() + ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
        } else {
            WebSocketFrame frame = (WebSocketFrame) msg;
            if (frame instanceof TextWebSocketFrame) {
                // TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
                System.out.println("TextWebSocketFrame");
            } else if (frame instanceof BinaryWebSocketFrame) {
                BinaryWebSocketFrame binFrame = (BinaryWebSocketFrame) frame;
                ByteBuf in = binFrame.content();
                byte[] idBytes = new byte[TcpClient.LENGTH_FIELD_OFFSET];
                in.readBytes(idBytes);//读取channel id
                int dataSize = in.readInt();//读取data长度
                String channelId = new String(idBytes);
                Map<String, Channel> clientMap = client.getChannelMap();
                Channel channel = clientMap.get(channelId);
                if (channel != null) {
                    if (!channel.isActive()) {
                        clientMap.remove(channelId);
                    } else {
                        if (dataSize == 2 && in.getShort(in.readerIndex()) == 0) {
                            //目标服务器关闭连接
                            clientMap.remove(channelId);
                            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        } else {
                            log.debug("received msg from proxy, length: {}B >>> transfer to client, channelId:{}", in.readableBytes(), channelId);
                            byte seed = CommonUtil.getSeed(idBytes);
                            int index = in.readerIndex();
                            while (index < in.writerIndex()) {
                                in.setByte(index, in.getByte(index) ^ seed);
                                index++;
                            }
                            channel.writeAndFlush(in);
                            return;
                        }
                    }
                }
            } else if (frame instanceof PingWebSocketFrame) {
                log.debug("WebSocket Client receive ping frame");
            } else if (frame instanceof PongWebSocketFrame) {
                log.debug("WebSocket Client receive pong frame");
            } else if (frame instanceof CloseWebSocketFrame) {
                log.warn("WebSocket Client receive close frame: {}", ((CloseWebSocketFrame) frame).reasonText());
                ch.close();
            }
            frame.release();
        }
    }
}