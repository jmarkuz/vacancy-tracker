package com.vacancytracker.application.packaging;

import com.vacancytracker.application.documents.DocumentService;
import com.vacancytracker.application.documents.ResumePdf;
import com.vacancytracker.application.documents.ResumeTooLongException;
import com.vacancytracker.application.documents.DocxWriter;
import com.vacancytracker.application.documents.TailoredDocument;
import com.vacancytracker.application.resume.Resume;
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

    /** The name of the copy kept for a pasted resume, which has no file of its own. */
    static final String PASTED_RESUME = "resume.txt";

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
        PackageRepository.ResumeCopy copy = resumeDocumentId == null ? copyOfActiveResume() : null;
        String link = applyUrl == null || applyUrl.isBlank() ? vacancy.getUrl() : CanonicalUrl.webLink(applyUrl);
        if (applyUrl != null && !applyUrl.isBlank() && link == null) {
            throw new IllegalArgumentException("The application page must be an http:// or https:// address.");
        }
        ApplicationPackage p = new ApplicationPackage(UUID.randomUUID(), vacancyId, resumeDocumentId, coverLetterDocumentId, link,
                note == null || note.isBlank() ? null : note.strip(), ApplicationPackage.PREPARED, clock.instant(), null,
                copy == null ? null : copy.fileName());
        repository.insert(p, copy);
        return p;
    }

    /**
     * Without a tailored version the package keeps its own copy of the resume as it is now, so replacing the resume
     * later does not change what this application was sent with. A pasted resume has no file: its text is the copy.
     */
    private PackageRepository.ResumeCopy copyOfActiveResume() {
        Resume active = resumes.findActive()
                .orElseThrow(() -> new IllegalArgumentException("Choose a resume version, or upload your resume first."));
        if (active.fileName() == null) {
            return new PackageRepository.ResumeCopy(PASTED_RESUME, "text/plain", active.text().getBytes(StandardCharsets.UTF_8),
                    pdfOf(active.text()));
        }
        ResumeRepository.Original original = resumes.original();
        if (DocumentService.isPdf(original)) {
            // An uploaded PDF goes into the package as it is, but only when it has one or two pages (T-097).
            int pages = ResumePdf.pages(original.bytes());
            if (pages > ResumePdf.MAX_PAGES) {
                throw new ResumeTooLongException(pages, "Your resume PDF has " + pages + " pages; at most " + ResumePdf.MAX_PAGES
                        + " go into a package. Choose a tailored version, or upload a shorter resume.");
            }
            return new PackageRepository.ResumeCopy(original.fileName(), original.contentType(), original.bytes());
        }
        return new PackageRepository.ResumeCopy(original.fileName(), original.contentType(), original.bytes(), pdfOf(active.text()));
    }

    /** The PDF made from the resume text; a resume that does not fit two pages cannot go into a package. */
    private static byte[] pdfOf(String text) {
        ResumePdf.Rendered pdf = ResumePdf.render(text);
        if (pdf.pages() > ResumePdf.MAX_PAGES) {
            throw new ResumeTooLongException(pdf.pages(), "Your resume makes a PDF of " + pdf.pages() + " pages; at most "
                    + ResumePdf.MAX_PAGES + " go into a package. Choose a tailored version, or shorten the resume.");
        }
        return pdf.bytes();
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
                resumeFile = resume.fileName(vacancy.getCompany(), "pdf");
                put(zip, resumeFile, documents.pdf(resume));
                put(zip, resume.fileName(vacancy.getCompany(), "docx"), DocxWriter.write(resume.content()));
                put(zip, resume.fileName(vacancy.getCompany(), "md"), resume.content().getBytes(StandardCharsets.UTF_8));
            } else {
                PackageRepository.ResumeCopy copy = repository.resumeCopy(id)
                        .orElseThrow(() -> new NotFoundException("This package has no resume copy; prepare a new package."));
                if (PASTED_RESUME.equals(copy.fileName())) {
                    String text = new String(copy.bytes(), StandardCharsets.UTF_8);
                    // A package prepared before V22 has no PDF: make it from the kept text when it fits two pages.
                    byte[] pdf = copy.pdf();
                    if (pdf == null) {
                        ResumePdf.Rendered rendered = ResumePdf.render(text);
                        pdf = rendered.pages() <= ResumePdf.MAX_PAGES ? rendered.bytes() : null;
                    }
                    resumeFile = pdf == null ? "resume.docx" : "resume.pdf";
                    if (pdf != null) {
                        put(zip, resumeFile, pdf);
                    }
                    put(zip, "resume.docx", DocxWriter.write(text));
                    put(zip, PASTED_RESUME, copy.bytes());
                } else {
                    String originalFile = "resume-original-" + copy.fileName().replaceAll("[\\\\/]", "_");
                    put(zip, originalFile, copy.bytes());
                    resumeFile = originalFile;
                    if (copy.pdf() != null) {
                        resumeFile = "resume.pdf";
                        put(zip, resumeFile, copy.pdf());
                    }
                }
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
