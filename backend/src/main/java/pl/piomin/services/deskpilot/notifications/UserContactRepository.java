package pl.piomin.services.deskpilot.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserContactRepository extends JpaRepository<UserContact, String> {
}
