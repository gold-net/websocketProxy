package com.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
public class FileController {

    private final Logger log = LoggerFactory.getLogger(FileController.class);

    private final Path path;

    public FileController(@Value("${tmp.path:/opt/tmp/}") String path) {
        this.path = Paths.get(path);
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile[] files) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = UUID.randomUUID().toString();
            }
            Files.copy(file.getInputStream(), path.resolve(originalFilename), StandardCopyOption.REPLACE_EXISTING);
            log.info("saved file: {}", file.getOriginalFilename());
        }
        return "redirect:";
    }

    @RequestMapping("/")
    public String list(ModelMap map) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        map.put("files", Files.list(path).map(f -> f.toFile().getName()).collect(Collectors.toList()));
        return "index";
    }

    @RequestMapping("download")
    public ResponseEntity<?> download(@RequestParam("file") String file) throws IOException {
        Path filePath = path.resolve(file);
        if (Files.exists(filePath)) {
            if (filePath.toRealPath().startsWith(path.toRealPath())) {
                if (Files.isRegularFile(filePath)) {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                    headers.setContentDispositionFormData("attachment", URLEncoder.encode(file, "utf-8"));
                    log.info("downloaded file: {}", file);
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .headers(headers)
                            .body(new PathResource(filePath));
                } else {
                    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(file + " not a file.");
                }
            } else {
                return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(file + " not allowed.");
            }
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(file + " not found.");
    }

    @RequestMapping("delete")
    public String delete(@RequestParam("file") String file, ModelMap map) throws IOException {
        Path filePath = path.resolve(file);
        if (Files.exists(filePath)) {
            if (filePath.toRealPath().startsWith(path.toRealPath())) {
                if (Files.isRegularFile(filePath)) {
                    Files.delete(filePath);
                    log.info("deleted file: {}", file);
                    return "redirect:";
                } else {
                    map.put("msg", file + " not a file.");
                }
            } else {
                map.put("msg", file + " not allowed.");
            }
        } else {
            map.put("msg", file + " not found.");
        }
        return "error";
    }
}
