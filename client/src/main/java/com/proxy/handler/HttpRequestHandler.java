package com.proxy.handler;

import com.proxy.TcpClient;
import com.proxy.util.CommonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.internal.AppendableCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class HttpRequestHandler extends TcpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    public HttpRequestHandler(TcpClient websocketClient) {
        super(websocketClient);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel proxy = websocketClient.getProxy();
        //客户端发给服务器的
        if (proxy == null || !proxy.isActive()) {
            ctx.channel().close();
        }
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
        Channel proxy = websocketClient.getProxy();
        //客户端发给服务器的
        if (proxy == null || !proxy.isActive()) {
            ctx.close();
            return;
        }
        String clientId = ctx.channel().id().asShortText();
        byte[] idBytes = clientId.getBytes(StandardCharsets.UTF_8);
        int index = in.readerIndex();
        AppendableCharSequence sb = new AppendableCharSequence(HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE);
        char b;
        while (index < in.readableBytes() && index < HttpObjectDecoder.DEFAULT_INITIAL_BUFFER_SIZE
                && (b = (char) (in.getByte(index) & 0xFF)) != HttpConstants.LF) {
            sb.append(b);
            index++;
        }
        String line = sb.toString().trim();
        if (line.startsWith(HttpMethod.CONNECT.name())) {
            String[] initialLine = CommonUtil.splitInitialLine(sb);
            String[] hostSplit = initialLine[1].split(":");
            String ip = hostSplit[0];
            int port;
            if (hostSplit.length > 1) {
                port = Integer.parseInt(hostSplit[1]);
            } else {
                port = 443;
            }
            log.debug("received connect from client, channelId:{}", clientId);
            ByteBuf byteBuf = proxy.alloc().buffer().writeBytes(idBytes).writeInt(0).writeByte(ip.length())
                    .writeBytes(ip.getBytes(StandardCharsets.UTF_8)).writeShort(port);
            byte seed = CommonUtil.getSeed(idBytes);
            for (int i = TcpClient.LENGTH_FIELD_OFFSET + 4; i < byteBuf.readableBytes(); i++) {
                byteBuf.setByte(i, byteBuf.getByte(i) ^ seed);
            }
            proxy.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
            websocketClient.getChannelMap().put(ctx.channel().id().asShortText(), ctx.channel());
            ByteBuf byteBuf1 = ctx.alloc().buffer();
            HttpResponse connectedResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                    new HttpResponseStatus(200, "Connection Established"));
            CommonUtil.encodeCommandResponse(connectedResponse, byteBuf1);
            ctx.writeAndFlush(byteBuf1);
        } else {
            boolean connected = false;
            Channel channel = websocketClient.getChannelMap().get(clientId);
            if (channel != null && channel.isActive()) {
                byte seed = CommonUtil.getSeed(idBytes);
                for (int i = in.readerIndex(); i < in.readableBytes(); i++) {
                    in.setByte(i, in.getByte(i) ^ seed);
                }
                log.debug("received msg from client, length: {}B, channelId: {}", in.readableBytes(), clientId);
                writeMsgSplit(proxy, idBytes, in);
                connected = true;
            }
            if (!connected) {
                //还没有建立连接 就发送get或post等方法
                sb = new AppendableCharSequence(HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE);
                while (index < in.readableBytes() && index < HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH) {
                    if ((b = (char) (in.getByte(index) & 0xFF)) != HttpConstants.LF) {
                        sb.append(b);
                    } else {
                        if (sb.toString().toLowerCase().startsWith("host")) {
                            String host = CommonUtil.splitHeader(sb);
                            String[] hostSplit = host.split(":");
                            String ip = hostSplit[0];
                            int port;
                            if (hostSplit.length > 1) {
                                port = Integer.parseInt(hostSplit[1]);
                            } else {
                                port = 80;
                            }
                            log.debug("received connect from client, channelId:{}", clientId);
                            ByteBuf byteBuf = proxy.alloc().buffer().writeBytes(idBytes).writeInt(0).writeByte(ip.length())
                                    .writeBytes(ip.getBytes(StandardCharsets.UTF_8)).writeShort(port);
                            byte seed = CommonUtil.getSeed(idBytes);
                            for (int i = TcpClient.LENGTH_FIELD_OFFSET + 4; i < byteBuf.readableBytes(); i++) {
                                byteBuf.setByte(i, byteBuf.getByte(i) ^ seed);
                            }
                            proxy.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
                            websocketClient.getChannelMap().put(ctx.channel().id().asShortText(), ctx.channel());
                            for (int i = in.readerIndex(); i < in.readableBytes(); i++) {
                                in.setByte(i, in.getByte(i) ^ seed);
                            }
                            writeMsgSplit(proxy, idBytes, in);
                            return;
                        }
                    }
                    index++;
                }
                //如果header没找到host那么,返回失败吧。
                ctx.channel().close();
            }
        }
    }
}
