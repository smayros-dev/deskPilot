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

    private static final int DIMENSIONS = 256;

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
        return DIMENSIONS;
    }

    private static float[] vectorize(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        return vector;
    }
}
