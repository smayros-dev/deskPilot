package pl.piomin.services.deskpilot.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.model.DocumentFormat;

/**
 * Turns an uploaded file into titled chunks for the knowledge base: Spring AI readers extract the text (pages of a
 * PDF, sections of a Markdown file, the body of an HTML page), {@link TokenTextSplitter} cuts long sections, and each
 * chunk after the first in a section starts with the end of the previous one, so a sentence cut at a boundary is
 * still found with its context.
 */
@Component
public class DocumentParser {

    /** Target chunk size in tokens: a few paragraphs, small enough to keep search results focused. */
    static final int CHUNK_TOKENS = 350;
    /** Characters of the previous chunk repeated at the start of the next one in the same section. */
    static final int OVERLAP_CHARS = 200;

    private static final Pattern MULTIPLE_SPACES = Pattern.compile("[ \t]{2,}");

    public record Chunk(String title, String content) {
    }

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(CHUNK_TOKENS)
            .withMinChunkSizeChars(200)
            .withMinChunkLengthToEmbed(10)
            .withKeepSeparator(true)
            .build();

    public List<Chunk> parse(byte[] content, String fileName, DocumentFormat format, String documentTitle) {
        Resource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        List<Document> sections = reader(resource, format).get();
        // Sections with the same label (e.g. several paragraphs under one heading) are kept together before splitting.
        Map<String, StringBuilder> bySection = new LinkedHashMap<>();
        for (Document section : sections) {
            String text = section.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (format == DocumentFormat.PDF) {
                // The PDF reader keeps the page layout: collapse the runs of spaces it uses to position words.
                text = MULTIPLE_SPACES.matcher(text).replaceAll(" ");
            }
            bySection.computeIfAbsent(sectionLabel(section, format), k -> new StringBuilder())
                    .append(text.strip()).append("\n\n");
        }
        List<Chunk> chunks = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> section : bySection.entrySet()) {
            List<String> parts = splitter.split(List.of(new Document(section.getValue().toString().strip()))).stream()
                    .map(Document::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .toList();
            for (int i = 0; i < parts.size(); i++) {
                String text = i == 0 ? parts.get(i) : "…" + tail(parts.get(i - 1)) + "\n" + parts.get(i);
                chunks.add(new Chunk(title(documentTitle, section.getKey(), i, parts.size()), text.strip()));
            }
        }
        return chunks;
    }

    private static DocumentReader reader(Resource resource, DocumentFormat format) {
        return switch (format) {
            case PDF -> new PagePdfDocumentReader(resource);
            case MARKDOWN -> new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withIncludeCodeBlock(true)
                    .withIncludeBlockquote(true)
                    .build());
            case HTML -> new JsoupDocumentReader(resource);
            case TEXT -> new TextReader(resource);
        };
    }

    private static String sectionLabel(Document section, DocumentFormat format) {
        Map<String, Object> metadata = section.getMetadata();
        return switch (format) {
            case PDF -> metadata.get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER) instanceof Number page
                    ? "Page " + page : "";
            case MARKDOWN -> metadata.get("title") instanceof String heading && !heading.isBlank() ? heading.strip() : "";
            case HTML, TEXT -> "";
        };
    }

    private static String title(String documentTitle, String section, int part, int parts) {
        String title = section.isEmpty() ? documentTitle : documentTitle + ": " + section;
        if (parts > 1) {
            title += " (part " + (part + 1) + "/" + parts + ")";
        }
        return title.length() <= 200 ? title : title.substring(0, 197) + "...";
    }

    /** The last {@link #OVERLAP_CHARS} characters, starting at a word boundary. */
    private static String tail(String text) {
        if (text.length() <= OVERLAP_CHARS) {
            return text;
        }
        String tail = text.substring(text.length() - OVERLAP_CHARS);
        int space = tail.indexOf(' ');
        return space > 0 ? tail.substring(space + 1) : tail;
    }
}
