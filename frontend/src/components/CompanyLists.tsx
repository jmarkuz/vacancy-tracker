import { useEffect, useState } from "preact/hooks";
import { addCompanyEntry, listCompanyEntries, removeCompanyEntry, type CompanyListEntry, type ListType } from "../api/companyLists";
import { ProfileRejected, type SearchProfile } from "../api/profiles";

interface Props {
  profiles: SearchProfile[];
  /** Called after a change, so the screen can show that scores are being recalculated. */
  onChanged: (message: string) => void;
}

/** Company allowlist and blocklist, for all profiles or one (T-059, ADR-028). */
export function CompanyLists({ profiles, onChanged }: Props) {
  const [entries, setEntries] = useState<CompanyListEntry[]>([]);
  const [company, setCompany] = useState("");
  const [listType, setListType] = useState<ListType>("blocklist");
  const [scope, setScope] = useState("");
  const [error, setError] = useState<string | null>(null);

  const reload = () => listCompanyEntries().then(setEntries).catch(() => setError("Could not load the company lists."));
  useEffect(() => {
    reload();
  }, []);

  const scopeName = (id: string | null) => (id === null ? "All profiles" : profiles.find((p) => p.id === id)?.name ?? "Unknown profile");

  const add = async (e: Event) => {
    e.preventDefault();
    setError(null);
    try {
      await addCompanyEntry(company, listType, scope === "" ? null : scope);
      setCompany("");
      await reload();
      onChanged(`Added to the ${listType}. Scores are being recalculated.`);
    } catch (err: unknown) {
      setError(err instanceof ProfileRejected ? Object.values(err.errors).join(" ") || "The company was not added." : "The company was not added.");
    }
  };

  const remove = async (entry: CompanyListEntry) => {
    try {
      await removeCompanyEntry(entry.id);
      await reload();
      onChanged(`Removed "${entry.company}". Scores are being recalculated.`);
    } catch {
      setError("Could not remove the entry.");
    }
  };

  const column = (type: ListType, title: string, hint: string) => (
    <div class="company-list">
      <h3>{title}</h3>
      <p class="muted small">{hint}</p>
      {entries.filter((x) => x.listType === type).length === 0 ? (
        <p class="muted">Empty.</p>
      ) : (
        <ul aria-label={title}>
          {entries.filter((x) => x.listType === type).map((x) => (
            <li key={x.id}>
              <span>{x.company}</span> <span class="muted small">{scopeName(x.profileId)}</span>
              <button type="button" class="btn ghost" onClick={() => remove(x)} aria-label={`Remove ${x.company} from the ${type}`}>Remove</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );

  return (
    <section class="panel" aria-labelledby="company-lists-title">
      <h2 id="company-lists-title">Company lists</h2>
      <form class="toolbar" onSubmit={add}>
        <label class="field"><span>Company</span>
          <input class="input" type="text" value={company} onInput={(e) => setCompany((e.target as HTMLInputElement).value)} />
        </label>
        <label class="field"><span>List</span>
          <select class="input" value={listType} onChange={(e) => setListType((e.target as HTMLSelectElement).value as ListType)}>
            <option value="blocklist">Blocklist</option>
            <option value="allowlist">Allowlist</option>
          </select>
        </label>
        <label class="field"><span>Applies to</span>
          <select class="input" value={scope} onChange={(e) => setScope((e.target as HTMLSelectElement).value)}>
            <option value="">All profiles</option>
            {profiles.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
        <button type="submit" class="btn" disabled={company.trim() === ""}>Add</button>
      </form>
      {error && <div class="notice error" role="alert">{error}</div>}
      <div class="company-lists">
        {column("blocklist", "Blocklist", "These companies are always filtered out.")}
        {column("allowlist", "Allowlist", "These companies get a score bonus and stay in the Inbox even below the threshold. Your require and exclude rules still apply.")}
      </div>
    </section>
  );
}
