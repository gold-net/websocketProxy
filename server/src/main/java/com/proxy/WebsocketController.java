package com.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

@RequestMapping(Server.BASE_PATH)
public class WebsocketController {

    WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(null, null, false);

    @RequestMapping("/ws")
    public void handshake(Channel channel, FullHttpRequest req) {
        //要求Upgrade为websocket，过滤掉get/Post
        if (!req.decoderResult().isSuccess() || (!"websocket".equals(req.headers().get("Upgrade")))) {
            //若不是websocket方式，则创建BAD_REQUEST的req，返回给客户端
            sendResponse(channel, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        WebSocketServerHandshaker handshake = wsFactory.newHandshaker(req);
        if (handshake == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel);
        } else {
            handshake.handshake(channel, req);
        }
    }

    /**
     * 拒绝不合法的请求，并返回错误信息
     */
    private void sendResponse(Channel channel, FullHttpRequest req, FullHttpResponse resp) {
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        HttpUtil.setKeepAlive(req, keepAlive);
        if (!keepAlive) {
            channel.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

}
