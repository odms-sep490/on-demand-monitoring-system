package com.ondemandmonitoring.refund.validation;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class RefundReceiptValidator {
    private static final long MAX_PIXELS = 25_000_000L;

    private RefundReceiptValidator() {
    }

    public static ValidatedImage validate(byte[] bytes, long maxBytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes) {
            throw invalid("Receipt image must be non-empty and within the configured size limit");
        }

        String contentType;
        String extension;
        if (hasPngSignature(bytes)) {
            contentType = "image/png";
            extension = "png";
        } else if (hasJpegSignature(bytes)) {
            contentType = "image/jpeg";
            extension = "jpg";
        } else {
            throw invalid("Only valid JPEG or PNG receipt images are supported");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw invalid("Receipt image could not be read");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Receipt image content is invalid");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw invalid("Receipt image dimensions are invalid or too large");
                }
                if (reader.read(0) == null) throw invalid("Receipt image content is invalid");
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw invalid("Receipt image content is invalid");
        }

        return new ValidatedImage(contentType, extension, bytes.length);
    }

    private static boolean hasPngSignature(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) return false;
        }
        return true;
    }

    private static boolean hasJpegSignature(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                && (bytes[2] & 255) == 255 && (bytes[bytes.length - 2] & 255) == 255
                && (bytes[bytes.length - 1] & 255) == 217;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    public record ValidatedImage(String contentType, String extension, long byteSize) {
    }
}
