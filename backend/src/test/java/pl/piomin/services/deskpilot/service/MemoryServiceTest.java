package pl.piomin.services.deskpilot.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.model.MemoryFact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({TestcontainersConfiguration.class, MemoryService.class})
class MemoryServiceTest {

    @Autowired
    private MemoryService memoryService;

    @Test
    void savesAndRecallsByKeyword() {
        memoryService.save("alice", "Prefers answers in French");
        memoryService.save("alice", "Works on a MacBook Pro");

        assertThat(memoryService.recall("alice", "which laptop: macbook or windows?"))
                .extracting(MemoryFact::getContent)
                .containsExactly("Works on a MacBook Pro");
    }

    @Test
    void doesNotDuplicateTheSameFact() {
        MemoryFact first = memoryService.save("alice", "Prefers answers in French");
        MemoryFact second = memoryService.save("alice", "  prefers answers in french ");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(memoryService.findAll("alice")).hasSize(1);
    }

    @Test
    void blankQueryReturnsRecentFacts() {
        memoryService.save("alice", "Prefers answers in French");

        assertThat(memoryService.recall("alice", " ")).hasSize(1);
    }

    @Test
    void doesNotLeakOtherUsersFacts() {
        memoryService.save("alice", "Works on a MacBook Pro");

        assertThat(memoryService.recall("bob", "macbook")).isEmpty();
    }

    @Test
    void rejectsBlankFact() {
        assertThatThrownBy(() -> memoryService.save("alice", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
