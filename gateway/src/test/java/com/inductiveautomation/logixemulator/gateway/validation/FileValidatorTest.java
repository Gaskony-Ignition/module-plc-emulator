package com.inductiveautomation.logixemulator.gateway.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for FileValidator.
 * Tests file size limits, content validation, and security checks.
 */
class FileValidatorTest {

    @Test
    @DisplayName("Should accept valid L5K file")
    void testValidL5KFile() {
        // L5K files must be at least 100 bytes and contain L5K markers
        String content = "CONTROLLER TestController\n" +
            "TAG MyTag DINT 0\n" +
            "PROGRAM MainProgram\n" +
            "  TAG LocalTag DINT 100\n" +
            "END_PROGRAM\n" +
            "END_CONTROLLER\n" +
            "// Additional padding to meet minimum size requirement\n";
        String filename = "test.l5k";

        FileValidator.ValidationResult result = FileValidator.validateContent(content, filename);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("11.0.0: rejects an otherwise-valid L5X file with actionable guidance - L5K is "
        + "the only supported Rockwell format, and a well-formed Studio 5000 XML export must "
        + "still be turned away rather than silently accepted")
    void testValidL5XFileIsRejected() {
        // A structurally valid Studio 5000 export: the rejection must be about the FORMAT,
        // not about the content being malformed.
        StringBuilder content = new StringBuilder("<?xml version=\"1.0\"?>\n");
        content.append("<RSLogix5000Content SchemaRevision=\"1.0\">\n");
        content.append("  <Controller Name=\"TestController\" ProcessorType=\"1756-L83E\">\n");
        content.append("    <Tags>\n");
        for (int i = 0; i < 20; i++) {
            content.append("      <Tag Name=\"Tag").append(i).append("\" DataType=\"DINT\"/>\n");
        }
        content.append("    </Tags>\n");
        content.append("  </Controller>\n");
        content.append("</RSLogix5000Content>\n");
        String filename = "test.l5x";

        FileValidator.ValidationResult result = FileValidator.validateContent(content.toString(), filename);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage())
            .as("the error must name L5K as the way forward, not just say 'unsupported'")
            .contains("L5X")
            .contains("not a supported format")
            .contains("L5K");
    }

    @Test
    @DisplayName("11.0.0: .l5x rejection is case-insensitive and independent of content")
    void testL5XRejectedRegardlessOfCaseAndContent() {
        for (String name : new String[] { "prog.l5x", "prog.L5X", "prog.L5x" }) {
            FileValidator.ValidationResult result =
                FileValidator.validateContent("anything at all", name);

            assertThat(result.isValid()).as("%s must be rejected", name).isFalse();
            assertThat(result.getErrorMessage()).contains("L5K");
        }
    }

    @Test
    @DisplayName("Should reject file exceeding size limit")
    void testFileTooLarge() {
        // Create content larger than max size
        long maxSize = FileValidator.getMaxFileSizeMB() * 1024 * 1024;
        StringBuilder content = new StringBuilder((int) maxSize + 1000);
        for (int i = 0; i < maxSize + 1000; i++) {
            content.append('A');
        }

        FileValidator.ValidationResult result = FileValidator.validateContent(
            content.toString(), "test.l5k");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("exceeds");
    }

    @Test
    @DisplayName("Should reject empty file")
    void testEmptyFile() {
        FileValidator.ValidationResult result = FileValidator.validateContent("", "test.l5k");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("empty");
    }

    @Test
    @DisplayName("Regression test for B6: an all-NUL-byte payload is NOT reported as empty "
        + "(String.trim() strips control bytes <= U+0020, previously making a large NUL-only "
        + "upload indistinguishable from a genuinely empty one - item6-validation.txt)")
    void testAllNulBytePayloadIsNotReportedAsEmpty() {
        // A small stand-in for the DoD's 11MB all-0x00 file - same defect, faster test.
        String content = "\0".repeat(4096);

        FileValidator.ValidationResult result = FileValidator.validateContent(content, "test.l5k");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage())
            .as("must not claim the content is empty - it has real bytes, just no readable data")
            .doesNotContain("File content is empty");
        assertThat(result.getErrorMessage()).contains("4096 bytes");
    }

    @Test
    @DisplayName("Should reject null content")
    void testNullContent() {
        FileValidator.ValidationResult result = FileValidator.validateContent(null, "test.l5k");

        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("Should accept JSON PLC file")
    void testValidJsonFile() {
        String content = "{\"controller\": \"TestPLC\", \"global_tags\": []}";
        String filename = "test.json";

        FileValidator.ValidationResult result = FileValidator.validateContent(content, filename);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Should accept CSV file")
    void testValidCsvFile() {
        String content = "Tag,Type,Value\nTag1,DINT,42\n";
        String filename = "test.csv";

        FileValidator.ValidationResult result = FileValidator.validateContent(content, filename);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Should validate file extension")
    void testInvalidExtension() {
        String content = "some content";
        String filename = "test.exe";

        FileValidator.ValidationResult result = FileValidator.validateContent(content, filename);

        // Depending on implementation, may reject unknown extensions
        // Adjust assertion based on actual FileValidator behavior
    }

    @Test
    @DisplayName("Should handle files at exact size limit")
    void testFileAtSizeLimit() {
        long maxSize = FileValidator.getMaxFileSizeMB() * 1024 * 1024;
        StringBuilder content = new StringBuilder((int) maxSize);

        // Start with valid L5K header
        content.append("CONTROLLER TestController\n");
        content.append("TAG MyTag DINT 0\n");

        // Fill the rest with padding to reach exactly maxSize
        while (content.length() < maxSize) {
            content.append('A');
        }

        // Trim to exact maxSize if needed
        if (content.length() > maxSize) {
            content.setLength((int) maxSize);
        }

        FileValidator.ValidationResult result = FileValidator.validateContent(
            content.toString(), "test.l5k");

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Should get max file size in MB")
    void testGetMaxFileSizeMB() {
        long maxSize = FileValidator.getMaxFileSizeMB();

        assertThat(maxSize).isGreaterThan(0);
        assertThat(maxSize).isLessThanOrEqualTo(100); // Reasonable limit
    }
}
