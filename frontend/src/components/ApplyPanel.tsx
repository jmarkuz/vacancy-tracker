import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  type ApplicationPackage,
  KIND_LABEL,
  listDocuments,
  listPackages,
  packageUrl,
  preparePackage,
  type TailoredDocument,
} from "../api/ai";
import { safeLink } from "../domain/links";
import { formatDate } from "../screens/columns";

interface Props {
  vacancyId: string;
  vacancyUrl: string | null;
  /** Grows when a document version was saved, so the choices load again. */
  docsVersion: number;
  /** Marks the package submitted and reloads the vacancy (status Applied). */
  onSubmitted: (packageId: string, note: string) => Promise<void>;
}

/**
 * Apply (T-084, T-085): choose the versions, prepare the package (ZIP download) and open the application page in a
 * new tab. The app stops there: the owner fills in and submits the form, then presses "I submitted".
 */
export function ApplyPanel({ vacancyId, vacancyUrl, docsVersion, onSubmitted }: Props) {
  const [docs, setDocs] = useState<TailoredDocument[]>([]);
  const [packages, setPackages] = useState<ApplicationPackage[]>([]);
  const [resumeId, setResumeId] = useState("");
  const [letterId, setLetterId] = useState("");
  const [applyUrl, setApplyUrl] = useState(vacancyUrl ?? "");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    listDocuments(vacancyId, controller.signal)
      .then((list) => {
        setDocs(list);
        // Default to the newest version of each kind.
        setResumeId((current) => current || (list.find((d) => d.kind === "resume")?.id ?? ""));
        setLetterId((current) => current || (list.find((d) => d.kind === "cover_letter")?.id ?? ""));
      })
      .catch(() => undefined);
    listPackages(vacancyId, controller.signal).then(setPackages).catch(() => undefined);
    return () => controller.abort();
  }, [vacancyId, docsVersion, reload]);

  const resumes = docs.filter((d) => d.kind === "resume");
  const letters = docs.filter((d) => d.kind === "cover_letter");
  const open = packages.find((p) => p.status === "prepared");

  const prepare = async () => {
    setBusy(true);
    setError(null);
    try {
      const p = await preparePackage(vacancyId, {
        resumeDocumentId: resumeId || null,
        coverLetterDocumentId: letterId || null,
        applyUrl: applyUrl.trim() || undefined,
      });
      setPackages((list) => [p, ...list]);
      // The files first, then the page where the owner submits them.
      const download = document.createElement("a");
      download.href = packageUrl(p.id);
      download.download = "";
      download.click();
      const link = safeLink(p.applyUrl);
      if (link) {
        window.open(link, "_blank", "noopener,noreferrer");
      }
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "The package could not be prepared.");
    } finally {
      setBusy(false);
    }
  };

  const submitted = async (p: ApplicationPackage) => {
    if (!window.confirm("Did you submit the application on the company's page? The vacancy moves to Applied.")) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSubmitted(p.id, note);
      setNote("");
      setReload((n) => n + 1);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "Could not record the submission.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section class="drawer-section" aria-labelledby="apply-title">
      <h3 id="apply-title">Apply</h3>
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}
      <div class="stack">
        <label>
          <span class="small">{KIND_LABEL.resume}</span>
          <select class="input" aria-label="Resume version" value={resumeId} onChange={(e) => setResumeId((e.target as HTMLSelectElement).value)}>
            <option value="">Your uploaded resume as it is</option>
            {resumes.map((d) => (
              <option key={d.id} value={d.id}>
                Version {d.version} ({d.origin === "ai" ? "AI draft" : "by hand"}, {formatDate(d.createdAt)})
              </option>
            ))}
          </select>
        </label>
        <label>
          <span class="small">{KIND_LABEL.cover_letter}</span>
          <select class="input" aria-label="Cover letter version" value={letterId} onChange={(e) => setLetterId((e.target as HTMLSelectElement).value)}>
            <option value="">No cover letter</option>
            {letters.map((d) => (
              <option key={d.id} value={d.id}>
                Version {d.version} ({d.origin === "ai" ? "AI draft" : "by hand"}, {formatDate(d.createdAt)})
              </option>
            ))}
          </select>
        </label>
        <label>
          <span class="small">Application page</span>
          <input class="input" type="url" value={applyUrl} placeholder="https://" onInput={(e) => setApplyUrl((e.target as HTMLInputElement).value)} />
        </label>
        <div class="drawer-row end">
          <button class="btn primary" type="button" disabled={busy} onClick={prepare}>
            Prepare package and open the page
          </button>
        </div>
        <p class="muted small">You submit the form yourself. The app only prepares the files and opens the page.</p>
      </div>

      {open && (
        <div class="notice" role="status">
          <span>
            Package {open.id.slice(0, 8)} is ready ·{" "}
            <a href={packageUrl(open.id)} download>
              download again
            </a>
            {safeLink(open.applyUrl) && (
              <>
                {" · "}
                <a href={safeLink(open.applyUrl) ?? undefined} target="_blank" rel="noopener noreferrer">
                  open the page
                </a>
              </>
            )}
          </span>
          <input class="input" value={note} placeholder="Note (optional)" aria-label="Note about the submission" maxLength={1000}
            onInput={(e) => setNote((e.target as HTMLInputElement).value)} />
          <button class="btn primary" type="button" disabled={busy} onClick={() => submitted(open)}>
            I submitted
          </button>
        </div>
      )}

      {packages.some((p) => p.status === "submitted") && (
        <ul class="history">
          {packages
            .filter((p) => p.status === "submitted")
            .map((p) => (
              <li key={p.id}>
                Submitted <span class="num">{formatDate(p.submittedAt)}</span> · package {p.id.slice(0, 8)} ·{" "}
                <a href={packageUrl(p.id)} download>
                  files
                </a>
              </li>
            ))}
        </ul>
      )}
    </section>
  );
}
