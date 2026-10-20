package pl.piomin.services.deskpilot.service;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic bag-of-words embeddings for tests: texts sharing words get similar vectors,
 * which is enough to exercise ranking and thresholds without Ollama.
 */
class FakeEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    FakeEmbeddingModel() {
        this(256);
    }

    /** @param dimensions 768 to match the pgvector table of the application context */
    FakeEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        return new EmbeddingResponse(IntStream.range(0, texts.size())
                .mapToObj(i -> new Embedding(vectorize(texts.get(i)), i))
                .toList());
    }

    @Override
    public float[] embed(Document document) {
        return vectorize(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] vectorize(String text) {
        float[] vector = new float[dimensions];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                vector[Math.floorMod(word.hashCode(), dimensions)] += 1f;
            }
        }
        return vector;
    }
}
