package com.ocrengine.app.service;

import com.ocrengine.app.exception.OcrProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Image preprocessing pipeline for OCR quality improvement.
 *
 * Pipeline order (optimised for scanned/photographed documents):
 * 1. Upscale — ensure minimum dimensions for reliable OCR
 * 2. Grayscale conversion (luminance-weighted)
 * 3. Contrast normalization (percentile-based histogram stretching)
 * 4. Deskew — CONSERVATIVE rotation correction (max ±5°)
 * 5. Denoise — gentle Gaussian blur to reduce scan noise
 * 6. Sharpen — mild unsharp-mask to restore edge crispness
 * 7. Binarize — ADAPTIVE local thresholding (handles uneven lighting)
 * 8. Morphological cleanup — close small gaps in text strokes
 */
@Service
public class ImagePreprocessingService {

    private static final Logger log = LoggerFactory.getLogger(ImagePreprocessingService.class);

    // Minimum pixel dimensions — images smaller than this get upscaled
    private static final int MIN_DIMENSION_WARNING = 800;

    // Target minimum dimension for upscaling — ensures text chars are ~30px+ tall
    private static final int UPSCALE_TARGET_MIN = 2500;

    // Adaptive threshold block size — must be ODD.
    // 71px at 300 DPI ≈ 0.24 inches — handles gradual shadow from book curvature.
    private static final int ADAPTIVE_BLOCK_SIZE = 71;

    // Adaptive threshold constant — subtracted from the local mean.
    // 12 is slightly gentler than 15 — preserves thin strokes better.
    private static final int ADAPTIVE_C = 12;

    // ─────────────────────────────────────────────────────────────────────────

    public PreprocessResult preprocess(File imageFile) throws IOException {
        BufferedImage original = ImageIO.read(imageFile);
        if (original == null) {
            throw new OcrProcessingException(
                "Cannot read image file — it may be corrupt or an unsupported format.");
        }

        boolean lowRes = original.getWidth() < MIN_DIMENSION_WARNING
                      || original.getHeight() < MIN_DIMENSION_WARNING;
        double brightnessMean = computeBrightnessMean(original);

        log.info("Preprocessing [{}] {}x{} brightness={} lowRes={}",
                imageFile.getName(), original.getWidth(), original.getHeight(),
                String.format("%.1f", brightnessMean), lowRes);

        BufferedImage processed = original;

        // ── Step 1: Upscale if too small ──────────────────────────────────
        processed = upscaleIfNeeded(processed);

        // ── Step 2: Grayscale ─────────────────────────────────────────────
        processed = toGrayscale(processed);

        // ── Step 3: Percentile-based contrast normalization ──────────────
        processed = normalizeContrast(processed, brightnessMean);

        // ── Step 4: Deskew (CONSERVATIVE — max ±5°) ─────────────────────
        double skewAngle = detectSkewAngle(processed);
        if (Math.abs(skewAngle) > 0.8 && Math.abs(skewAngle) <= 5.0) {
            log.info("Deskewing by {}°", String.format("%.2f", skewAngle));
            processed = rotateImage(processed, -skewAngle);
        } else if (Math.abs(skewAngle) > 5.0) {
            log.info("Skew angle {}° exceeds safe limit (5°) — skipping deskew to avoid damage",
                     String.format("%.2f", skewAngle));
            skewAngle = 0; // Reset — we didn't apply it
        }

        // ── Step 5: Denoise (light Gaussian blur) ────────────────────────
        processed = denoise(processed);

        // ── Step 6: Sharpen (gentle unsharp mask) ────────────────────────
        processed = sharpen(processed);

        // ── Step 7: Adaptive binarization ────────────────────────────────
        processed = adaptiveThreshold(processed, ADAPTIVE_BLOCK_SIZE, ADAPTIVE_C);

        // ── Step 8: Morphological cleanup ────────────────────────────────
        processed = morphologicalClose(processed);

        // Write to temp file
        File out = Files.createTempFile("prep_", ".png").toFile();
        out.deleteOnExit();
        ImageIO.write(processed, "PNG", out);

        return new PreprocessResult(out, lowRes, brightnessMean, skewAngle);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — Upscale small images
    //
    // Tesseract works best when text characters are 30-40+ pixels tall.
    // Phone photos of book pages may have small text. Upscaling to at least
    // 2500px on the shorter side ensures reliable character recognition.
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage upscaleIfNeeded(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int minDim = Math.min(w, h);

        if (minDim >= UPSCALE_TARGET_MIN) return src;

        double scale = (double) UPSCALE_TARGET_MIN / minDim;
        // Cap upscale factor at 3x to avoid creating massive images
        scale = Math.min(scale, 3.0);

        int newW = (int)(w * scale);
        int newH = (int)(h * scale);

        log.info("Upscaling {}x{} -> {}x{} (scale {}x)", w, h, newW, newH, String.format("%.2f", scale));

        BufferedImage scaled = new BufferedImage(newW, newH, src.getType());
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — Grayscale (luminance-weighted for accurate text contrast)
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage toGrayscale(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8)  & 0xFF;
                int b =  rgb        & 0xFF;
                // ITU-R BT.601 luminance
                int lum = (int)(0.299 * r + 0.587 * g + 0.114 * b);
                gray.getRaster().setSample(x, y, 0, lum);
            }
        }
        return gray;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3 — Percentile-based contrast normalization
    //
    // Clips the bottom and top percentile of pixel values to prevent
    // outliers from compressing the useful dynamic range.
    // For dark images, uses more aggressive clipping.
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage normalizeContrast(BufferedImage src, double brightnessMean) {
        int w = src.getWidth(), h = src.getHeight();
        int totalPixels = w * h;

        // Build histogram
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                hist[src.getRaster().getSample(x, y, 0)]++;
            }
        }

        // Use more aggressive clipping for dark or overexposed images
        double lowPct  = brightnessMean < 80 ? 0.02 : 0.01;
        double highPct = brightnessMean > 200 ? 0.98 : 0.99;

        int lowClip = 0, highClip = 255;
        int cumulative = 0;
        int lowTarget  = (int)(totalPixels * lowPct);
        int highTarget = (int)(totalPixels * highPct);

        for (int i = 0; i < 256; i++) {
            cumulative += hist[i];
            if (cumulative >= lowTarget && lowClip == 0) {
                lowClip = i;
            }
            if (cumulative >= highTarget) {
                highClip = i;
                break;
            }
        }

        if (highClip <= lowClip) return src;

        float range = highClip - lowClip;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRaster().getSample(x, y, 0);
                int clamped = Math.max(lowClip, Math.min(highClip, p));
                int stretched = Math.round(((clamped - lowClip) / range) * 255);
                out.getRaster().setSample(x, y, 0, Math.max(0, Math.min(255, stretched)));
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4 — Deskew using Projection Profile Method
    //
    // VERY CONSERVATIVE: Only searches ±5° in 0.5° steps.
    // Phone photos of curved book pages can fool wider searches because
    // the text follows the page curvature, not a straight line. Searching
    // ±15° often locks onto a wrong angle and destroys the text.
    // ─────────────────────────────────────────────────────────────────────────

    public double detectSkewAngle(BufferedImage src) {
        // Work on a downsampled version for speed
        int scale = Math.max(1, src.getWidth() / 600);
        BufferedImage small = scaleDown(src, scale);
        BufferedImage binary = quickBinarize(small);

        // Coarse scan: -5° to +5° in 0.5° steps
        double bestAngle = 0;
        double bestScore = -1;

        for (double deg = -5.0; deg <= 5.0; deg += 0.5) {
            double score = projectionVariance(binary, deg);
            if (score > bestScore) {
                bestScore = score;
                bestAngle = deg;
            }
        }

        // Fine scan: ±1° around best coarse angle in 0.1° steps
        double fineStart = bestAngle - 1.0;
        double fineEnd   = bestAngle + 1.0;
        for (double deg = fineStart; deg <= fineEnd; deg += 0.1) {
            double score = projectionVariance(binary, deg);
            if (score > bestScore) {
                bestScore = score;
                bestAngle = deg;
            }
        }

        log.info("Detected skew angle: {}°", String.format("%.2f", bestAngle));
        return bestAngle;
    }

    private double projectionVariance(BufferedImage src, double angleDeg) {
        BufferedImage rotated = rotateImage(src, -angleDeg);
        int w = rotated.getWidth(), h = rotated.getHeight();

        // Horizontal projection — count dark pixels per row
        double[] projection = new double[h];
        for (int y = 0; y < h; y++) {
            int count = 0;
            for (int x = 0; x < w; x++) {
                int p = (rotated.getRGB(x, y) & 0xFF);
                if (p < 128) count++;
            }
            projection[y] = count;
        }

        double mean = 0;
        for (double v : projection) mean += v;
        mean /= projection.length;

        double variance = 0;
        for (double v : projection) variance += (v - mean) * (v - mean);
        return variance / projection.length;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4b — Rotate image by given angle
    // ─────────────────────────────────────────────────────────────────────────

    BufferedImage rotateImage(BufferedImage src, double angleDeg) {
        double rad = Math.toRadians(angleDeg);
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));

        int w = src.getWidth(), h = src.getHeight();
        int newW = (int) Math.floor(w * cos + h * sin);
        int newH = (int) Math.floor(h * cos + w * sin);

        BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newW, newH);

        AffineTransform at = new AffineTransform();
        at.translate((newW - w) / 2.0, (newH - h) / 2.0);
        at.rotate(rad, w / 2.0, h / 2.0);
        g.drawRenderedImage(src, at);
        g.dispose();
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 5 — Denoise (Gaussian blur, radius 1)
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage denoise(BufferedImage src) {
        float[] kernel = {
            1/16f, 2/16f, 1/16f,
            2/16f, 4/16f, 2/16f,
            1/16f, 2/16f, 1/16f
        };
        ConvolveOp op = new ConvolveOp(
                new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null);
        return op.filter(src, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 6 — Sharpen (gentle unsharp mask)
    //
    // Reduced from center weight 2.0 to 1.75 to minimize haloing artifacts
    // around text strokes, which can confuse character segmentation.
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage sharpen(BufferedImage src) {
        float[] kernel = {
             0f,   -0.2f,   0f,
            -0.2f,  1.8f,  -0.2f,
             0f,   -0.2f,   0f
        };
        ConvolveOp op = new ConvolveOp(
                new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null);
        return op.filter(src, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 7 — Adaptive local thresholding
    //
    // Uses integral image (summed area table) for O(1) mean computation.
    // Block size 71 and C=12 are tuned for book photos with page curvature.
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage adaptiveThreshold(BufferedImage src, int blockSize, int C) {
        int w = src.getWidth(), h = src.getHeight();

        if (blockSize % 2 == 0) blockSize++;
        int half = blockSize / 2;

        // Build integral image
        long[][] integral = new long[h + 1][w + 1];
        for (int y = 1; y <= h; y++) {
            long rowSum = 0;
            for (int x = 1; x <= w; x++) {
                rowSum += src.getRaster().getSample(x - 1, y - 1, 0);
                integral[y][x] = rowSum + integral[y - 1][x];
            }
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - half);
                int y1 = Math.max(0, y - half);
                int x2 = Math.min(w - 1, x + half);
                int y2 = Math.min(h - 1, y + half);

                int count = (x2 - x1 + 1) * (y2 - y1 + 1);

                long sum = integral[y2 + 1][x2 + 1]
                         - integral[y1][x2 + 1]
                         - integral[y2 + 1][x1]
                         + integral[y1][x1];

                double localMean = (double) sum / count;
                int pixel = src.getRaster().getSample(x, y, 0);

                out.getRaster().setSample(x, y, 0, pixel < (localMean - C) ? 0 : 255);
            }
        }

        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 8 — Morphological closing (dilate then erode)
    //
    // After binarization, thin text strokes may have small gaps from noise
    // or compression artifacts. Closing (dilate→erode) fills these gaps
    // without significantly thickening the text, improving character
    // recognition for thin fonts.
    //
    // Uses a small 3×3 cross-shaped structuring element.
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage morphologicalClose(BufferedImage src) {
        // Dilate: if ANY neighbor in cross pattern is black → pixel becomes black
        BufferedImage dilated = morphOp(src, true);
        // Erode: if ANY neighbor in cross pattern is white → pixel becomes white
        return morphOp(dilated, false);
    }

    private BufferedImage morphOp(BufferedImage src, boolean isDilate) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);

        // 3×3 cross structuring element offsets: center, up, down, left, right
        int[][] se = {{0,0}, {-1,0}, {1,0}, {0,-1}, {0,1}};

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int val = src.getRaster().getSample(x, y, 0);

                if (isDilate) {
                    // Dilate: if any SE neighbor is 0 (black), output is 0
                    int result = 255;
                    for (int[] offset : se) {
                        int nx = x + offset[0], ny = y + offset[1];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            if (src.getRaster().getSample(nx, ny, 0) == 0) {
                                result = 0;
                                break;
                            }
                        }
                    }
                    out.getRaster().setSample(x, y, 0, result);
                } else {
                    // Erode: if any SE neighbor is 255 (white), output is 255
                    int result = 0;
                    for (int[] offset : se) {
                        int nx = x + offset[0], ny = y + offset[1];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            if (src.getRaster().getSample(nx, ny, 0) == 255) {
                                result = 255;
                                break;
                            }
                        }
                    }
                    out.getRaster().setSample(x, y, 0, result);
                }
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private BufferedImage quickBinarize(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRaster().getSample(x, y, 0);
                int v = p < 128 ? 0 : 255;
                out.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        return out;
    }

    private BufferedImage scaleDown(BufferedImage src, int scale) {
        if (scale <= 1) return src;
        int newW = src.getWidth() / scale;
        int newH = src.getHeight() / scale;
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }

    private double computeBrightnessMean(BufferedImage img) {
        long sum = 0;
        int w = img.getWidth(), h = img.getHeight();
        int count = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int gv = (rgb >> 8) & 0xFF;
                int b  =  rgb       & 0xFF;
                sum += (r + gv + b) / 3;
                count++;
            }
        }
        return count > 0 ? (double) sum / count : 128;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result
    // ─────────────────────────────────────────────────────────────────────────

    public static class PreprocessResult {
        public final File processedFile;
        public final boolean lowResolution;
        public final double originalBrightnessMean;
        public final double deskewAngle;

        public PreprocessResult(File processedFile, boolean lowResolution,
                                double originalBrightnessMean, double deskewAngle) {
            this.processedFile        = processedFile;
            this.lowResolution        = lowResolution;
            this.originalBrightnessMean = originalBrightnessMean;
            this.deskewAngle          = deskewAngle;
        }

        public boolean isDarkImage()        { return originalBrightnessMean < 60; }
        public boolean isOverexposedImage() { return originalBrightnessMean > 220; }
        public boolean wasSkewed()          { return Math.abs(deskewAngle) > 1.0; }
    }
}
