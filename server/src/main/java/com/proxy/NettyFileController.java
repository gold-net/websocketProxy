package com.proxy;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RequestMapping(Server.BASE_PATH)
public class NettyFileController {

    private final Logger log = LoggerFactory.getLogger(NettyFileController.class);

    private final Path path;

    public NettyFileController(String path) {
        this.path = Paths.get(path);
    }

    @RequestMapping(value = "/upload", method = "post")
    public FullHttpResponse upload(Channel channel, FullHttpRequest request) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(request);
        while (decoder.hasNext()) {
            InterfaceHttpData httpData = decoder.next();
            if (httpData instanceof Attribute) {
                Attribute attr = (Attribute) httpData;
            } else if (httpData instanceof FileUpload) {
                FileUpload file = (FileUpload) httpData;
                //可实际保存文件或做其他事情...
                String originalFilename = file.getFilename();
                if (originalFilename == null) {
                    originalFilename = UUID.randomUUID().toString();
                }
                Files.copy(new ByteArrayInputStream(file.get()), path.resolve(originalFilename), StandardCopyOption.REPLACE_EXISTING);
                log.info("saved file: {}", originalFilename);
            }
        }
        decoder.destroy();
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.TEMPORARY_REDIRECT);
        httpResponse.headers().add(HttpHeaderNames.LOCATION, Server.BASE_PATH);
        return httpResponse;
    }

    @RequestMapping("/")
    public FullHttpResponse list(Channel channel, FullHttpRequest request) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        Map<String, Object> map = new HashMap<>();
        map.put("files", Files.list(path).map(f -> f.toFile().getName()).collect(Collectors.toList()));
        return Template.index(map);
    }

    @RequestMapping("download")
    public FullHttpResponse download(Channel channel, FullHttpRequest request) throws IOException {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> fileList = decoder.parameters().get("file");
        Map<String, Object> map = new HashMap<>();
        if (fileList == null) {
            map.put("msg", "request error");
            return Template.error(map);
        }
        String file = fileList.get(0);
        Path filePath = path.resolve(file);
        if (Files.exists(filePath)) {
            if (filePath.toRealPath().startsWith(path.toRealPath())) {
                if (Files.isRegularFile(filePath)) {
                    FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                    httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM);
                    httpResponse.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "name=attachment;filename=" + URLEncoder.encode(file, "utf-8"));
                    InputStream inputStream = Files.newInputStream(filePath);
                    httpResponse.content().writeBytes(inputStream, inputStream.available());
                    return httpResponse;
                } else {
                    map.put("msg", file + " not a file.");
                }
            } else {
                map.put("msg", file + " not allowed.");
            }
        }
        map.put("msg", file + " not found.");
        return Template.error(map);
    }

    @RequestMapping("delete")
    public FullHttpResponse delete(Channel channel, FullHttpRequest request) throws IOException {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> fileList = decoder.parameters().get("file");
        Map<String, Object> map = new HashMap<>();
        if (fileList == null) {
            map.put("msg", "request error");
            return Template.error(map);
        }
        String file = fileList.get(0);
        Path filePath = path.resolve(file);
        if (Files.exists(filePath)) {
            if (filePath.toRealPath().startsWith(path.toRealPath())) {
                if (Files.isRegularFile(filePath)) {
                    Files.delete(filePath);
                    log.info("deleted file: {}", file);
                    FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.TEMPORARY_REDIRECT);
                    httpResponse.headers().add(HttpHeaderNames.LOCATION, Server.BASE_PATH);
                    return httpResponse;
                } else {
                    map.put("msg", file + " not a file.");
                }
            } else {
                map.put("msg", file + " not allowed.");
            }
        } else {
            map.put("msg", file + " not found.");
        }
        return Template.error(map);
    }
}
