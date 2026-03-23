package com.example.aikb.util;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class TikaParserUtil {

    private final Tika tika = new Tika();

    public String parseText(MultipartFile multipartFile) {
        try (InputStream inputStream = multipartFile.getInputStream()) {
            return tika.parseToString(inputStream);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse file: " + multipartFile.getOriginalFilename(), exception);
        }
    }

    public String parseText(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return tika.parseToString(inputStream);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse file: " + path.getFileName(), exception);
        }
    }
}
