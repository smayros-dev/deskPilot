package pl.piomin.services.deskpilot.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

interface ApprovalEscalationRepository extends JpaRepository<ApprovalEscalation, String> {
}
