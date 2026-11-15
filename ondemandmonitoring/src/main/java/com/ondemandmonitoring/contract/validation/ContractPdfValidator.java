package com.ondemandmonitoring.contract.validation;

import com.ondemandmonitoring.common.exception.*;
import java.io.IOException;
import java.security.*;
import java.util.HexFormat;
import org.apache.pdfbox.Loader;
import org.springframework.web.multipart.MultipartFile;

public final class ContractPdfValidator {
  public static final long MAX_BYTES = 10L * 1024 * 1024;

  private ContractPdfValidator() {}

  public record ValidatedPdf(byte[] bytes, String sha256, String fileName) {}

  public static ValidatedPdf validate(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid();
    try {
      byte[] bytes = file.getBytes();
      if (bytes.length == 0
          || bytes.length > MAX_BYTES
          || bytes.length < 5
          || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
        throw invalid();
      try (var pdf = Loader.loadPDF(bytes)) {
        if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 500)
          throw invalid();
      }
      String name =
          file.getOriginalFilename() == null
              ? "contract.pdf"
              : file.getOriginalFilename().replace('\\', '/');
      name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>]", "_");
      if (name.isBlank() || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
        name = "contract.pdf";
      if (name.length() > 180) name = name.substring(0, 176) + ".pdf";
      return new ValidatedPdf(
          bytes,
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
          name);
    } catch (IOException | NoSuchAlgorithmException e) {
      throw invalid();
    }
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.CONTRACT_FILE_INVALID);
  }
}
