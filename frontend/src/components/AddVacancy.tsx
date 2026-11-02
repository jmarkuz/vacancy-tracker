import { useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { createVacancy, importFromUrl } from "../api/transfer";

interface Props {
  /** Called with the new vacancy's id once it is stored. */
  onAdded: (id: string) => void;
  onClose: () => void;
}

/**
 * Adds a vacancy by hand (T-079). "From a link" reads the page's JobPosting data once, as a browser would; when the
 * page has none (or its site forbids automated reading), the form takes the title, company and link typed in.
 */
export function AddVacancy({ onAdded, onClose }: Props) {
  const [url, setUrl] = useState("");
  const [title, setTitle] = useState("");
  const [company, setCompany] = useState("");
  const [manual, setManual] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const created = manual
        ? await createVacancy({ title: title.trim(), company: company.trim(), url: url.trim() || undefined })
        : await importFromUrl(url.trim());
      onAdded(created.id);
    } catch (e: unknown) {
      const message = e instanceof ApiError ? e.message : "The vacancy could not be added.";
      setError(manual ? message : `${message} You can type the details instead.`);
      if (!manual && e instanceof ApiError && e.status === 422) {
        setManual(true);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <form
      class="panel add-vacancy"
      aria-label="Add a vacancy"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <div class="drawer-row">
        <label class="grow">
          <span>Link to the posting</span>
          <input class="input" type="url" value={url} placeholder="https://" onInput={(e) => setUrl((e.target as HTMLInputElement).value)} />
        </label>
        <label class="inline">
          <input type="checkbox" checked={manual} onChange={(e) => setManual((e.target as HTMLInputElement).checked)} /> Type the details
        </label>
      </div>
      {manual && (
        <div class="drawer-row">
          <label class="grow">
            <span>Title</span>
            <input class="input" required value={title} onInput={(e) => setTitle((e.target as HTMLInputElement).value)} />
          </label>
          <label class="grow">
            <span>Company</span>
            <input class="input" required value={company} onInput={(e) => setCompany((e.target as HTMLInputElement).value)} />
          </label>
        </div>
      )}
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}
      <div class="drawer-row end">
        <button class="btn ghost" type="button" onClick={onClose}>
          Cancel
        </button>
        <button class="btn primary" type="submit" disabled={busy || (manual ? !title.trim() || !company.trim() : !url.trim())}>
          {busy ? "Adding…" : "Add vacancy"}
        </button>
      </div>
    </form>
  );
}
