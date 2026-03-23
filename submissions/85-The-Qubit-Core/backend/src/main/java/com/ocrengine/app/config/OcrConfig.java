package com.ocrengine.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class OcrConfig {

    private static final Logger log = LoggerFactory.getLogger(OcrConfig.class);

    @Value("${ocr.tessdata.path:./tessdata}")
    private String tessdataPath;

    @Value("${ocr.language:eng}")
    private String language;

    @Value("${ocr.upload.max-size-mb:20}")
    private int maxUploadSizeMb;

    @Value("${ocr.upload.temp-dir:./uploads/temp}")
    private String tempUploadDir;

    /**
     * Returns the ABSOLUTE path to tessdata.
     * Tess4J on Windows requires an absolute path — relative paths silently fail.
     * Resolution order:
     *   1. If already absolute → use as-is
     *   2. Relative to working directory (where the JAR is launched from)
     *   3. Relative to the JAR file location
     */
    public String getTessdataPath() {
        File f = new File(tessdataPath);

        // Already absolute and exists
        if (f.isAbsolute() && f.exists()) {
            log.info("Tessdata path (absolute): {}", f.getAbsolutePath());
            return f.getAbsolutePath();
        }

        // Try relative to working directory
        File workDir = new File(System.getProperty("user.dir"), tessdataPath);
        if (workDir.exists()) {
            log.info("Tessdata path (working dir): {}", workDir.getAbsolutePath());
            return workDir.getAbsolutePath();
        }

        // Try relative to JAR location
        try {
            String jarPath = OcrConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File jarDir = new File(jarPath).getParentFile();
            // Go up from BOOT-INF/classes or target/ to the backend root
            File jarRelative = new File(jarDir, tessdataPath);
            if (jarRelative.exists()) {
                log.info("Tessdata path (jar-relative): {}", jarRelative.getAbsolutePath());
                return jarRelative.getAbsolutePath();
            }
            // Try going up 2 levels (from BOOT-INF/classes)
            File twoUp = new File(jarDir.getParentFile().getParentFile(), tessdataPath);
            if (twoUp.exists()) {
                log.info("Tessdata path (jar 2-up): {}", twoUp.getAbsolutePath());
                return twoUp.getAbsolutePath();
            }
        } catch (Exception e) {
            log.warn("Could not resolve JAR path: {}", e.getMessage());
        }

        // Fallback — return working dir version and let Tesseract report the error clearly
        String fallback = workDir.getAbsolutePath();
        log.warn("Tessdata path not found at expected locations. Using: {} — " +
                 "If OCR fails, ensure eng.traineddata is in: {}", fallback, fallback);
        return fallback;
    }

    public String getLanguage()        { return language; }
    public int getMaxUploadSizeMb()    { return maxUploadSizeMb; }
    public String getTempUploadDir()   { return tempUploadDir; }
    public String getRawTessdataPath() { return tessdataPath; }
}
