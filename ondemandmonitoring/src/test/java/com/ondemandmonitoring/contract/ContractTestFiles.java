package com.ondemandmonitoring.contract;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.mock.web.MockMultipartFile;

final class ContractTestFiles {
  static MockMultipartFile pdf() {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      document.save(output);
      return new MockMultipartFile("file", "signed.pdf", "application/pdf", output.toByteArray());
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
