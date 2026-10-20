package pl.piomin.services.deskpilot.cloud;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CloudProviderRepository extends JpaRepository<CloudProvider, String> {
}
