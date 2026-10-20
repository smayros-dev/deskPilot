package pl.piomin.services.deskpilot.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import pl.piomin.services.deskpilot.model.DocumentFormat;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void markdownSectionsBecomeChunksTitledWithTheirHeading() {
        String markdown = """
                # Docking stations

                Plug the dock into the USB-C port on the left side of the laptop.

                # Monitors

                Each desk has two monitors; ask the IT desk on floor 2 for a third one.
                """;

        List<DocumentParser.Chunk> chunks = parse(markdown, "office.md", DocumentFormat.MARKDOWN);

        assertThat(chunks).extracting(DocumentParser.Chunk::title)
                .containsExactly("Office guide: Docking stations", "Office guide: Monitors");
        assertThat(chunks.get(1).content()).contains("floor 2");
    }

    @Test
    void pdfPagesBecomeChunksTitledWithTheirPageNumber() {
        byte[] pdf = TestDocuments.pdf(List.of("Badges are printed at the reception.", "Lost badges are blocked within an hour."));

        List<DocumentParser.Chunk> chunks = parser.parse(pdf, "badges.pdf", DocumentFormat.PDF, "Badges");

        assertThat(chunks).extracting(DocumentParser.Chunk::title).containsExactly("Badges: Page 1", "Badges: Page 2");
        assertThat(chunks.get(1).content()).contains("blocked within an hour");
    }

    @Test
    void longSectionsAreSplitAndEachPartRepeatsTheEndOfThePreviousOne() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            text.append("Sentence number ").append(i).append(" explains one more step of the VPN setup. ");
        }

        List<DocumentParser.Chunk> chunks = parse(text.toString(), "vpn.txt", DocumentFormat.TEXT);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().title()).isEqualTo("Office guide (part 1/" + chunks.size() + ")");
        String endOfFirst = chunks.get(0).content().substring(chunks.get(0).content().length() - 40);
        assertThat(chunks.get(1).content()).startsWith("…").contains(endOfFirst.strip());
    }

    @Test
    void htmlKeepsTheVisibleTextOnly() {
        String html = "<html><head><title>x</title><script>alert(1)</script></head>"
                + "<body><h1>Printers</h1><p>Use PRN-3-02 on floor 3.</p></body></html>";

        List<DocumentParser.Chunk> chunks = parse(html, "printers.html", DocumentFormat.HTML);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.title()).isEqualTo("Office guide");
            assertThat(chunk.content()).contains("PRN-3-02").doesNotContain("alert(1)");
        });
    }

    @Test
    void recognisesFormatsByExtension() {
        assertThat(DocumentFormat.fromFileName("Guide.PDF")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.fromFileName("notes.markdown")).contains(DocumentFormat.MARKDOWN);
        assertThat(DocumentFormat.fromFileName("page.htm")).contains(DocumentFormat.HTML);
        assertThat(DocumentFormat.fromFileName("setup.exe")).isEmpty();
        assertThat(DocumentFormat.fromFileName("README")).isEmpty();
    }

    private List<DocumentParser.Chunk> parse(String text, String fileName, DocumentFormat format) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8), fileName, format, "Office guide");
    }
}
