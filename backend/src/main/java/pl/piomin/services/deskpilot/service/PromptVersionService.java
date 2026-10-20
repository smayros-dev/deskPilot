package pl.piomin.services.deskpilot.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.PromptVersion;
import pl.piomin.services.deskpilot.repository.ExperimentRepository;
import pl.piomin.services.deskpilot.repository.PromptVersionRepository;

/** Named versions of the system prompt (12m). A version is immutable; save a new one to change it. */
@Service
@Transactional(readOnly = true)
public class PromptVersionService {

    private final PromptVersionRepository repository;
    private final ExperimentRepository experimentRepository;

    public PromptVersionService(PromptVersionRepository repository, ExperimentRepository experimentRepository) {
        this.repository = repository;
        this.experimentRepository = experimentRepository;
    }

    public List<PromptVersion> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public PromptVersion get(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Prompt version " + id + " not found"));
    }

    /** @throws ConflictException if the name is taken */
    @Transactional
    public PromptVersion create(String name, String content, String actor) {
        String trimmed = name.strip();
        if (repository.existsByName(trimmed)) {
            throw new ConflictException("A prompt version named '" + trimmed + "' already exists");
        }
        return repository.save(new PromptVersion(trimmed, content.strip(), actor));
    }

    /** @throws ConflictException if an experiment uses the version (its results must stay explainable) */
    @Transactional
    public PromptVersion delete(Long id) {
        PromptVersion version = get(id);
        if (experimentRepository.usesPrompt(id)) {
            throw new ConflictException("Prompt version '" + version.getName() + "' is used by an experiment");
        }
        repository.delete(version);
        return version;
    }
}
