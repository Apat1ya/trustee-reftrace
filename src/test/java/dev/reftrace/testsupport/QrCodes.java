package dev.reftrace.testsupport;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

public final class QrCodes {

    private QrCodes() {
    }

    public static String svg(String text, int size) {
        BitMatrix matrix = matrix(text, 0);
        int modules = matrix.getWidth();
        String id = "qr-" + Integer.toHexString(text.hashCode());
        StringBuilder clip = new StringBuilder();
        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                if (matrix.get(x, y)) {
                    clip.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                            .append("\" width=\"1\" height=\"1\"/>");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + size + "\" height=\"" + size
                + "\" viewBox=\"0 0 " + modules + " " + modules + "\" shape-rendering=\"crispEdges\">"
                + "<defs><clipPath id=\"" + id + "\">" + clip + "</clipPath></defs>"
                + "<rect width=\"" + modules + "\" height=\"" + modules + "\" fill=\"#ffffff\"/>"
                + "<rect width=\"" + modules + "\" height=\"" + modules + "\" fill=\"#101010\" clip-path=\"url(#"
                + id + ")\"/></svg>";
    }

    public static byte[] png(String text, int size) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(matrix(text, size), "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BitMatrix matrix(String text, int size) {
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalArgumentException("cannot encode " + text, e);
        }
    }
}
