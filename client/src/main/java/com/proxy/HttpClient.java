package com.proxy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.proxy.handler.HttpRequestHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpClient extends TcpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    public HttpClient(String proxyHost, int proxyPort) {
        super(proxyHost, proxyPort);
    }

    public static void main(String[] args) throws Exception {
        log.info("usage: mainClass [listenPort] [serverHost] [serverPort] [logLevel]");
        String proxyHost = "localhost";
        int proxyPort = 80;
        int port = 8082;
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            proxyHost = args[1];
        }
        if (args.length >= 3) {
            proxyPort = Integer.parseInt(args[2]);
        }
        Level level = Level.INFO;
        if (args.length >= 4) {
            level = Level.toLevel(args[3]);
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLogger("root").setLevel(level);
        }
        log.info("http proxy config: \nlistenPort: {} \nserverHost: {} \nserverPort: {} \nlogLevel: {}",
                port, proxyHost, proxyPort, level);
        new HttpClient(proxyHost, proxyPort).startServer(port);
    }

    protected ChannelInitializer<SocketChannel> getChannelInitializer() {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.config().setKeepAlive(true);
                ch.pipeline().addLast(new HttpRequestHandler(HttpClient.this));
            }
        };
    }

}
