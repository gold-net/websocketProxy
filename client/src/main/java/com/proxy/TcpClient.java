package com.proxy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.proxy.handler.TcpRequestHandler;
import com.proxy.handler.WebSocketHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TcpClient {

    private static final Logger log = LoggerFactory.getLogger(TcpClient.class);

    public static final int LENGTH_FIELD_OFFSET = 8;

    private String serverHost;
    private int serverPort;

    private final String proxyHost;
    private final int proxyPort;
    private Channel proxy;
    private final NioEventLoopGroup worker;
    private final Map<String, Channel> channelMap;

    public TcpClient(String proxyHost, int proxyPort) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.worker = new NioEventLoopGroup();
        this.channelMap = new ConcurrentHashMap<>();
    }

    public TcpClient(String serverHost, int serverPort, String proxyHost, int proxyPort) {
        this(proxyHost, proxyPort);
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }


    public Channel getProxy() {
        return proxy;
    }

    public Map<String, Channel> getChannelMap() {
        return channelMap;
    }

    public static void main(String[] args) throws Exception {
        log.info("usage: mainClass [proxyHost] [proxyPort] [serverHost] [serverPort] [listenPort-default:8082] [logLevel-default:info]");
        String serverHost = "localhost";
        int serverPort = 22;
        String proxyHost = "localhost";
        int proxyPort = 80;
        int port = 8082;
        if (args.length >= 1) {
            serverHost = args[0];
        }
        if (args.length >= 2) {
            serverPort = Integer.parseInt(args[1]);
        }
        if (args.length >= 3) {
            proxyHost = args[2];
        }
        if (args.length >= 4) {
            proxyPort = Integer.parseInt(args[3]);
        }
        if (args.length >= 5) {
            port = Integer.parseInt(args[4]);
        }
        Level level = Level.INFO;
        if (args.length >= 6) {
            level = Level.toLevel(args[4]);
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLogger("root").setLevel(level);
        }
        log.info("tcp proxy config: \nserverHost: {} \nserverPort: {} \nproxyHost: {} \nproxyPort: {} \nlistenPort: {} \nlogLevel: {}",
                serverHost, serverPort, proxyHost, proxyPort, port, level);
        new TcpClient(serverHost, serverPort, proxyHost, proxyPort).startServer(port);
    }

    public void startServer(int... ports) throws Exception {
        if (connectProxy()) {
            worker.scheduleWithFixedDelay(this::sendPing, 50, 50, TimeUnit.SECONDS);
            NioEventLoopGroup boss = new NioEventLoopGroup(ports.length);
            List<ChannelFuture> futures = new ArrayList<>();
            try {
                ServerBootstrap serverBootstrap = new ServerBootstrap().group(boss, worker)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(getChannelInitializer());
                for (int port : ports) {
                    futures.add(serverBootstrap.bind(port).sync());
                    log.info("service started successfully at port: {}.", port);
                }
                for (ChannelFuture channelFuture : futures) {
                    channelFuture.channel().closeFuture().sync();
                }
            } finally {
                boss.shutdownGracefully();
                worker.shutdownGracefully();
            }
        } else {
            worker.shutdownGracefully();
        }
    }

    protected ChannelInitializer<SocketChannel> getChannelInitializer() {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.config().setKeepAlive(true);
                ch.pipeline().addLast(new TcpRequestHandler(TcpClient.this));
            }
        };
    }

    public boolean connectProxy() {
        try {
            WebSocketClientHandshaker handShaker = WebSocketClientHandshakerFactory.newHandshaker(
                    new URI("ws://" + proxyHost + ":" + proxyPort + "/temp/ws"),
                    WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), 1024 * 1024);

            WebSocketHandler clientHandler = new WebSocketHandler(handShaker, this);
            Bootstrap bootstrap = new Bootstrap().group(worker)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        protected void initChannel(SocketChannel socketChannel) {
                            ChannelPipeline p = socketChannel.pipeline();
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(1024 * 1024 * 100));
                            p.addLast(clientHandler);
                        }
                    });
            ChannelFuture future = bootstrap.connect(proxyHost, proxyPort).sync();
            if (future.isSuccess()) {
                ChannelFuture future1 = clientHandler.handshakeFuture().sync();
                if (future1.isSuccess()) {
                    this.proxy = future.channel();
                    log.info("connect to websocket server success.");
                    return true;
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        log.info("connect to websocket server failed.");
        return false;
    }

    private void sendPing() {
        if (proxy != null && proxy.isActive()) {
            proxy.writeAndFlush(new PingWebSocketFrame());
        }
    }
}
