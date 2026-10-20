package pl.piomin.services.deskpilot.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.dto.UserDataExport;
import pl.piomin.services.deskpilot.service.PrivacyService;

/** The caller's own data: export it, delete memories, delete everything (GDPR access and erasure). */
@RestController
@RequestMapping("/api/me")
public class PrivacyController {

    private final PrivacyService privacyService;

    public PrivacyController(PrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    @DeleteMapping("/memories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMemory(@PathVariable long id, Authentication authentication) {
        privacyService.deleteMemory(id, authentication.getName());
    }

    @DeleteMapping("/memories")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMemories(Authentication authentication) {
        privacyService.deleteMemories(authentication.getName());
    }

    /** Everything stored about the caller, as a JSON file to download. */
    @GetMapping("/export")
    public ResponseEntity<UserDataExport> export(Authentication authentication) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"deskpilot-my-data.json\"")
                .body(privacyService.export(authentication.getName()));
    }

    /** Deletes all the caller's conversations and memories; tickets stay with the helpdesk. */
    @DeleteMapping("/data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(Authentication authentication) {
        privacyService.deleteAllData(authentication.getName());
    }
}
