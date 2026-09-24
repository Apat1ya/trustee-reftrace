package dev.reftrace.browse.page;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

final class QrDecoder {

    private static final int QUIET_ZONE = 16;

    private QrDecoder() {
    }

    record Result(@Nullable String text, boolean structureSeen) {
    }

    static Result decode(byte[] png) {
        BufferedImage flat = flatten(readPng(png));
        boolean structureSeen = false;
        for (BufferedImage attempt : new BufferedImage[]{flat, half(flat), invert(flat)}) {
            Result result = decodeOnce(pad(attempt));
            if (result.text() != null) {
                return result;
            }
            structureSeen |= result.structureSeen();
        }
        return new Result(null, structureSeen);
    }

    private static Result decodeOnce(BufferedImage image) {
        BinaryBitmap bitmap = new BinaryBitmap(new GlobalHistogramBinarizer(new BufferedImageLuminanceSource(image)));
        try {
            return new Result(new QRCodeReader().decode(bitmap, hints()).getText(), true);
        } catch (NotFoundException _) {
            return new Result(null, false);
        } catch (ChecksumException | FormatException _) {
            return new Result(null, true);
        }
    }

    private static Map<DecodeHintType, Object> hints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return hints;
    }

    private static BufferedImage readPng(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                throw new IllegalArgumentException("not a readable image");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage flatten(BufferedImage source) {
        return redraw(source, source.getWidth(), source.getHeight(), 0);
    }

    private static BufferedImage pad(BufferedImage source) {
        return redraw(source, source.getWidth(), source.getHeight(), QUIET_ZONE);
    }

    private static BufferedImage half(BufferedImage source) {
        return redraw(source, Math.max(1, source.getWidth() / 2), Math.max(1, source.getHeight() / 2), 0);
    }

    private static BufferedImage redraw(BufferedImage source, int width, int height, int margin) {
        BufferedImage out = new BufferedImage(width + 2 * margin, height + 2 * margin, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, margin, margin, width, height, null);
        g.dispose();
        return out;
    }

    private static BufferedImage invert(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                out.setRGB(x, y, ~source.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return out;
    }
}
