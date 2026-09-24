package dev.reftrace.browse.page;

import dev.reftrace.testsupport.QrCodes;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class QrDecoderTest {

    private static final String LINK = "https://trusteeplus.app.link/Sp1keKey9Zx";

    @Test
    void readsACleanCode() {
        assertThat(QrDecoder.decode(QrCodes.png(LINK, 200)).text()).isEqualTo(LINK);
    }

    @Test
    void readsACodePaintedLightOnDark() throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(QrCodes.png(LINK, 200)));
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, ~image.getRGB(x, y) | 0xFF000000);
            }
        }

        assertThat(QrDecoder.decode(png(image)).text()).isEqualTo(LINK);
    }

    @Test
    void reportsADamagedCodeAsAQrCodeWithoutContent() throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(QrCodes.png(LINK, 290)));
        for (int y = 100; y < 180; y++) {
            for (int x = 100; x < 180; x++) {
                image.setRGB(x, y, ~image.getRGB(x, y) | 0xFF000000);
            }
        }

        QrDecoder.Result result = QrDecoder.decode(png(image));

        assertThat(result.text()).isNull();
        assertThat(result.structureSeen()).isTrue();
    }

    @Test
    void findsNothingInAPictureOfSomethingElse() throws IOException {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillOval(20, 20, 160, 160);
        g.dispose();

        QrDecoder.Result result = QrDecoder.decode(png(image));

        assertThat(result.text()).isNull();
        assertThat(result.structureSeen()).isFalse();
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }
}
