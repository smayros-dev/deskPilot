package pl.piomin.services.deskpilot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.SpecialistDefinition;

public interface SpecialistRepository extends JpaRepository<SpecialistDefinition, String> {

    List<SpecialistDefinition> findAllByOrderBySortOrderAscIdAsc();
}
