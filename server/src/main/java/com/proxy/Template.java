package com.proxy;

import io.netty.handler.codec.http.*;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Template {

    private static final TemplateEngine templateEngine = new TemplateEngine();

    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        templateEngine.setTemplateResolver(resolver);
    }

    public static String process(String template, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        context.setVariable("basePath", Server.BASE_PATH);
        return templateEngine.process(template, context);
    }

    public static FullHttpResponse error(Map<String, Object> data) {
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML);
        httpResponse.content().writeBytes(Template.process("error", data).getBytes(StandardCharsets.UTF_8));
        return httpResponse;
    }

    public static FullHttpResponse index(Map<String, Object> data) {
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML);
        httpResponse.content().writeBytes(Template.process("index", data).getBytes(StandardCharsets.UTF_8));
        return httpResponse;
    }
}
