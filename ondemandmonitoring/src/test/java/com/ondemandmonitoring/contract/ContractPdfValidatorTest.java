package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.contract.validation.ContractPdfValidator;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ContractPdfValidatorTest {
  @Test
  void validPdfGetsItsActualSha256AndByteSize() {
    var file = ContractTestFiles.pdf();
    var result = ContractPdfValidator.validate(file);
    assertThat(result.bytes()).hasSize((int) file.getSize());
    assertThat(result.sha256()).hasSize(64);
    assertThat(ContractPdfValidator.validate(file).sha256()).isEqualTo(result.sha256());
  }

  @Test
  void filenameAndMimeDoNotMakeArbitraryBytesAPdf() {
    assertThatThrownBy(
            () ->
                ContractPdfValidator.validate(
                    new MockMultipartFile(
                        "file", "test.pdf", "application/pdf", "not a pdf".getBytes())))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                ContractPdfValidator.validate(
                    new MockMultipartFile(
                        "file", "test.pdf", "application/pdf", "%PDF-1.7 invalid".getBytes())))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void emptyAndOversizedFilesAreRejected() {
    assertThatThrownBy(() -> ContractPdfValidator.validate(null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> ContractPdfValidator.validate(new MockMultipartFile("file", new byte[0])))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                ContractPdfValidator.validate(
                    new MockMultipartFile(
                        "file", new byte[(int) ContractPdfValidator.MAX_BYTES + 1])))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void encryptedDocumentsAreRejected() throws Exception {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      document.protect(
          new StandardProtectionPolicy(
              "owner-password", "reader-password", new AccessPermission()));
      document.save(output);
      assertThatThrownBy(
              () ->
                  ContractPdfValidator.validate(
                      new MockMultipartFile(
                          "file", "protected.pdf", "application/pdf", output.toByteArray())))
          .isInstanceOf(ApiException.class);
    }
  }

  @Test
  void pathAndControlCharactersDoNotBecomeDownloadNames() throws Exception {
    var file = ContractTestFiles.pdf();
    var result =
        ContractPdfValidator.validate(
            new MockMultipartFile(
                "file", "../../signed.pdf", "application/octet-stream", file.getBytes()));
    assertThat(result.fileName()).isEqualTo("signed.pdf");
  }
}
