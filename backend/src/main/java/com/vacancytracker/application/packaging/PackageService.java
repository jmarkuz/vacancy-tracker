package com.vacancytracker.application.packaging;

import com.vacancytracker.application.documents.DocumentService;
import com.vacancytracker.application.documents.DocxWriter;
import com.vacancytracker.application.documents.TailoredDocument;
import com.vacancytracker.application.resume.ResumeRepository;
import com.vacancytracker.application.resume.ResumeService;
import com.vacancytracker.common.CanonicalUrl;
import com.vacancytracker.common.NotFoundException;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import com.vacancytracker.vacancy.VacancyStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application packages (T-084, T-085, ADR-042). Preparing one fixes which document versions go with the application
 * and where it is submitted; the ZIP holds the files and a checklist. The app opens the page and stops there: the
 * owner submits. "I submitted" then moves the vacancy to Applied (through Saved first when it was New), with the
 * package and the versions in the history note.
 */
@Service
public class PackageService {

    private final PackageRepository repository;
    private final DocumentService documents;
    private final ResumeService resumes;
    private final VacancyService vacancies;
    private final Clock clock;

    public PackageService(PackageRepository repository, DocumentService documents, ResumeService resumes, VacancyService vacancies,
                          Clock clock) {
        this.repository = repository;
        this.documents = documents;
        this.resumes = resumes;
        this.vacancies = vacancies;
        this.clock = clock;
    }

    /** The package after "I submitted", and the vacancy as it is now. */
    public record Submitted(ApplicationPackage applicationPackage, Vacancy vacancy) {
    }

    @Transactional
    public ApplicationPackage prepare(UUID vacancyId, UUID resumeDocumentId, UUID coverLetterDocumentId, String applyUrl, String note) {
        Vacancy vacancy = vacancies.get(vacancyId);
        check(resumeDocumentId, vacancyId, TailoredDocument.RESUME);
        check(coverLetterDocumentId, vacancyId, TailoredDocument.COVER_LETTER);
        if (resumeDocumentId == null && resumes.findActive().isEmpty()) {
            throw new IllegalArgumentException("Choose a resume version, or upload your resume first.");
        }
        String link = applyUrl == null || applyUrl.isBlank() ? vacancy.getUrl() : CanonicalUrl.webLink(applyUrl);
        if (applyUrl != null && !applyUrl.isBlank() && link == null) {
            throw new IllegalArgumentException("The application page must be an http:// or https:// address.");
        }
        ApplicationPackage p = new ApplicationPackage(UUID.randomUUID(), vacancyId, resumeDocumentId, coverLetterDocumentId, link,
                note == null || note.isBlank() ? null : note.strip(), ApplicationPackage.PREPARED, clock.instant(), null);
        repository.insert(p);
        return p;
    }

    private void check(UUID documentId, UUID vacancyId, String kind) {
        if (documentId == null) {
            return;
        }
        TailoredDocument doc = documents.get(documentId);
        if (!doc.vacancyId().equals(vacancyId) || !doc.kind().equals(kind)) {
            throw new IllegalArgumentException("That " + kind.replace('_', ' ') + " version belongs to another vacancy or kind.");
        }
    }

    @Transactional(readOnly = true)
    public List<ApplicationPackage> list(UUID vacancyId) {
        vacancies.get(vacancyId);
        return repository.forVacancy(vacancyId);
    }

    @Transactional(readOnly = true)
    public ApplicationPackage get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("Application package not found: " + id));
    }

    /** The owner says it was submitted: the package is marked and the vacancy moves to Applied. Idempotent. */
    @Transactional
    public Submitted markSubmitted(UUID id, String note) {
        ApplicationPackage p = get(id);
        Vacancy vacancy = vacancies.get(p.vacancyId());
        if (repository.markSubmitted(id, clock.instant())) {
            StringBuilder history = new StringBuilder("applied with package ").append(p.reference());
            if (p.resumeDocumentId() != null) {
                history.append(", resume v").append(documents.get(p.resumeDocumentId()).version());
            }
            if (p.coverLetterDocumentId() != null) {
                history.append(", cover letter v").append(documents.get(p.coverLetterDocumentId()).version());
            }
            if (note != null && !note.isBlank()) {
                history.append(": ").append(note.strip());
            }
            if (before(vacancy.getStatus())) {
                vacancy = vacancies.moveTo(p.vacancyId(), VacancyStatus.APPLIED, history.toString());
            }
        }
        return new Submitted(get(id), vacancy);
    }

    /** Statuses from which "submitted" means Applied; later stages already imply it. */
    private static boolean before(VacancyStatus status) {
        return status == VacancyStatus.NEW || status == VacancyStatus.SAVED || status.isTerminal() && status != VacancyStatus.REJECTED
                && status != VacancyStatus.WITHDRAWN;
    }

    /** The ZIP the owner uploads from: the chosen documents, the vacancy and a checklist. */
    @Transactional(readOnly = true)
    public byte[] zip(UUID id) {
        ApplicationPackage p = get(id);
        Vacancy vacancy = vacancies.get(p.vacancyId());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            String resumeFile;
            if (p.resumeDocumentId() != null) {
                TailoredDocument resume = documents.get(p.resumeDocumentId());
                resumeFile = resume.fileName(vacancy.getCompany(), "docx");
                put(zip, resumeFile, DocxWriter.write(resume.content()));
                put(zip, resume.fileName(vacancy.getCompany(), "md"), resume.content().getBytes(StandardCharsets.UTF_8));
            } else {
                ResumeRepository.Original original = resumes.original();
                resumeFile = "resume-original-" + (original.fileName() == null ? "resume.txt" : original.fileName().replaceAll("[\\\\/]", "_"));
                put(zip, resumeFile, original.bytes());
            }
            String letterFile = null;
            if (p.coverLetterDocumentId() != null) {
                TailoredDocument letter = documents.get(p.coverLetterDocumentId());
                letterFile = letter.fileName(vacancy.getCompany(), "txt");
                put(zip, letterFile, letter.content().getBytes(StandardCharsets.UTF_8));
                put(zip, letter.fileName(vacancy.getCompany(), "docx"), DocxWriter.write(letter.content()));
            }
            put(zip, "vacancy.txt", vacancyText(vacancy).getBytes(StandardCharsets.UTF_8));
            put(zip, "checklist.md", checklist(p, vacancy, resumeFile, letterFile).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String vacancyText(Vacancy v) {
        return v.getTitle() + "\n" + v.getCompany() + (v.getLocation() == null ? "" : "\n" + v.getLocation())
                + (v.getUrl() == null ? "" : "\n" + v.getUrl()) + "\n\n" + (v.getDescription() == null ? "" : v.getDescription()) + "\n";
    }

    private static String checklist(ApplicationPackage p, Vacancy v, String resumeFile, String letterFile) {
        return "# Application: " + v.getTitle() + " at " + v.getCompany() + "\n\n"
                + "Package " + p.reference() + ", prepared " + p.createdAt() + ".\n\n"
                + "- [ ] Open the application page: " + (p.applyUrl() == null ? "(no link: find it on the company's site)" : p.applyUrl()) + "\n"
                + "- [ ] Upload the resume: " + resumeFile + "\n"
                + (letterFile == null ? "" : "- [ ] Paste or upload the cover letter: " + letterFile + "\n")
                + "- [ ] Check every field the form asks for, then submit it yourself\n"
                + "- [ ] Back in Vacancy Tracker, press \"I submitted\" so the vacancy moves to Applied\n";
    }
}
