package com.ondemandmonitoring.refund.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ondemandmonitoring.common.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class RefundReceiptValidatorTest {
    @Test
    void acceptsDecodedPngAndDeterminesContentFromImageBytes() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);

        var image = RefundReceiptValidator.validate(output.toByteArray(), 1024);

        assertEquals("image/png", image.contentType());
        assertEquals("png", image.extension());
    }

    @Test
    void rejectsUnsupportedOrOversizedImageContent() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] png = output.toByteArray();

        assertThrows(ApiException.class, () -> RefundReceiptValidator.validate(new byte[] {1, 2, 3}, 1024));
        assertThrows(ApiException.class, () -> RefundReceiptValidator.validate(png, png.length - 1));
    }
}
