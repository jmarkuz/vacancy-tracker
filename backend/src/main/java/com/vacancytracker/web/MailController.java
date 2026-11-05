package com.vacancytracker.web;

import com.vacancytracker.application.mail.Correspondence;
import com.vacancytracker.application.mail.CorrespondenceService;
import com.vacancytracker.application.mail.MailSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Job mail: list, check now, import an .eml file, accept or dismiss a suggested status, link to a vacancy (T-086, T-087). */
@RestController
public class MailController {

    private final CorrespondenceService mail;

    public MailController(CorrespondenceService mail) {
        this.mail = mail;
    }

    @GetMapping("/api/mail/status")
    public CorrespondenceService.Status status() {
        return mail.status();
    }

    @GetMapping("/api/mail/settings")
    public SettingsResponse settings() {
        return new SettingsResponse(mail.intervalMinutes());
    }

    /** Sets how often the mailbox is read; 400 for a value that is not one of 5, 10, 15, 30, 45, 60. */
    @PutMapping("/api/mail/settings")
    public SettingsResponse updateSettings(@RequestBody SettingsRequest request) {
        if (request == null || request.intervalMinutes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give intervalMinutes.");
        }
        if (!MailSettings.ALLOWED_MINUTES.contains(request.intervalMinutes())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The interval must be one of " + MailSettings.ALLOWED_MINUTES + " minutes.");
        }
        return new SettingsResponse(mail.setIntervalMinutes(request.intervalMinutes()));
    }

    @GetMapping("/api/mail")
    public List<Correspondence> list(@RequestParam(required = false) UUID vacancyId, @RequestParam(defaultValue = "false") boolean pending) {
        return mail.list(vacancyId, pending);
    }

    /** The applications a mail can be linked to by hand. */
    @GetMapping("/api/mail/applications")
    public List<CorrespondenceService.ApplicationOption> applications() {
        return mail.applications();
    }

    /** Starts reading the mailbox in the background; 202 when it started, 409 when one is already running. */
    @PostMapping("/api/mail/sync/start")
    public ResponseEntity<CorrespondenceService.SyncStart> startSync() {
        CorrespondenceService.SyncStart start = mail.startSync();
        return ResponseEntity.status(start.started() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(start);
    }

    /**
     * Forgets which mails were handled and reads the mailbox again in the background (same answers as sync/start).
     * Stored mail is kept and gets what newer versions read from it, such as the postings of a digest.
     */
    @PostMapping("/api/mail/reread")
    public ResponseEntity<CorrespondenceService.SyncStart> reread() {
        CorrespondenceService.SyncStart start = mail.startReread();
        return ResponseEntity.status(start.started() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(start);
    }

    @PostMapping("/api/mail/sync")
    public CorrespondenceService.SyncResult sync() {
        return mail.sync();
    }

    /** The raw .eml file as the request body. */
    @PostMapping("/api/mail/import")
    public CorrespondenceService.Outcome importEml(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(5 * 1024 * 1024 + 1);
        if (bytes.length > 5 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than 5 MB.");
        }
        return mail.importEml(bytes);
    }

    @PostMapping("/api/mail/{id}/accept")
    public AcceptResponse accept(@PathVariable UUID id) {
        return new AcceptResponse(mail.get(id).vacancyId(), mail.accept(id).getStatus().label(), mail.get(id));
    }

    @PostMapping("/api/mail/{id}/dismiss")
    public Correspondence dismiss(@PathVariable UUID id) {
        return mail.dismiss(id);
    }

    @PutMapping("/api/mail/{id}/vacancy")
    public Correspondence relink(@PathVariable UUID id, @RequestBody RelinkRequest request) {
        return mail.relink(id, request.vacancyId());
    }

    /** Deletes the chosen stored mails from the app. The mailbox is not touched. */
    @PostMapping("/api/mail/delete")
    public DeleteResponse delete(@RequestBody DeleteRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one mail.");
        }
        return new DeleteResponse(mail.delete(request.ids()));
    }

    /** Deletes the stored mails older than the given number of days. */
    @PostMapping("/api/mail/delete-old")
    public DeleteResponse deleteOld(@RequestBody DeleteOldRequest request) {
        if (request == null || request.days() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a number of days of at least 1.");
        }
        return new DeleteResponse(mail.deleteOlderThan(request.days()));
    }

    public record SettingsRequest(Integer intervalMinutes) {
    }

    public record SettingsResponse(int intervalMinutes) {
    }

    public record DeleteRequest(List<UUID> ids) {
    }

    public record DeleteOldRequest(int days) {
    }

    public record DeleteResponse(int deleted) {
    }

    public record RelinkRequest(UUID vacancyId) {
    }

    public record AcceptResponse(UUID vacancyId, String vacancyStatus, Correspondence mail) {
    }
}
