package com.proxy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Server {

    static Logger log = LoggerFactory.getLogger(Server.class);

    public static final String BASE_PATH = "/temFile";

    public static void main(String[] args) {
        log.info("usage: mainClass [listenPort] [logLevel]");
        int port = 8081;
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        Level level = Level.INFO;
        if (args.length >= 2) {
            level = Level.toLevel(args[1]);
        }
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger("root").setLevel(level);
        new Server().start(port);
    }

    private final List<Object> controllerList;

    public Server() {
        this.controllerList = new ArrayList<>();
        this.controllerList.add(new NettyFileController("/opt/tmp/"));
        this.controllerList.add(new WebsocketController());
    }

    private void start(int port) {
        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap().group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(2048 * 2048));
                            ch.pipeline().addLast(new ChunkedWriteHandler());
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
                                    URI uri = new URI(request.uri());
                                    FullHttpResponse httpResponse = invoke(uri.getPath(), request.method().name(), ctx.channel(), request);
                                    if (httpResponse != null) {
                                        httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
                                        ch.writeAndFlush(httpResponse);
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    log.error("native request error", cause.getCause() == null ? cause : cause.getCause());
                                    Map<String, Object> data = new HashMap<>();
                                    data.put("error", cause.getCause().toString());
                                    ctx.channel().writeAndFlush(Template.error(data));
                                    ctx.channel().close();
                                }
                            });
                            ch.pipeline().addLast(new WebSocketHandler());
                        }
                    });
            ChannelFuture channelFuture = serverBootstrap.bind(port);
            log.info("service started successfully at port: {}.", port);
            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    public FullHttpResponse invoke(String uri, String method, Channel channel, FullHttpRequest request) throws Exception {
        for (Object obj : controllerList) {
            Class<?> clazz = obj.getClass();
            RequestMapping mapping = clazz.getAnnotation(RequestMapping.class);
            String mappingUri = "";
            if (mapping != null) {
                mappingUri = fixUri(mapping.value());
            }
            for (Method actionMethod : clazz.getMethods()) {
                RequestMapping subMapping = actionMethod.getAnnotation(RequestMapping.class);
                if (subMapping != null) {
                    if (!"".equals(subMapping.method()) && !subMapping.method().equalsIgnoreCase(method)) {
                        continue;
                    }
                    String subMappingUri = fixUri(subMapping.value());
                    if (fixUri(uri).equalsIgnoreCase(mappingUri + subMappingUri)) {
                        return (FullHttpResponse) actionMethod.invoke(obj, channel, request);
                    }
                }
            }
        }
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
    }

    private String fixUri(String uri) {
        StringBuilder builder = new StringBuilder(uri);
        if (builder.indexOf("/") != 0) {
            builder.insert(0, "/");
        }
        if (builder.lastIndexOf("/") == builder.length() - 1) {
            builder.delete(builder.length() - 1, builder.length());
        }
        return builder.toString();
    }
}
