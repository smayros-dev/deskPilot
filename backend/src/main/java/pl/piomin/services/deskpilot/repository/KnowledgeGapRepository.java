package pl.piomin.services.deskpilot.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.KnowledgeGap;
import pl.piomin.services.deskpilot.model.KnowledgeGapStatus;

public interface KnowledgeGapRepository extends JpaRepository<KnowledgeGap, Long> {

    List<KnowledgeGap> findByStatusIn(Collection<KnowledgeGapStatus> statuses);
}
