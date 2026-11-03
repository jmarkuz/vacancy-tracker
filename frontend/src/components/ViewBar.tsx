import { useEffect, useState } from "preact/hooks";
import { createView, deleteView, listViews, type SavedView, updateView } from "../api/views";
import type { Criterion } from "../domain/criteria";

interface Props {
  viewId: string | null;
  criteria: Criterion[];
  sort: string | null;
  cols: string | null;
  /** Replaces the URL state with a view's state, or clears it when id is null. */
  onApply: (view: SavedView | null) => void;
}

/** Only the parts a view stores, so server-only fields (id, mode) do not count as changes. */
export function project(criteria: Criterion[]): Criterion[] {
  return criteria.map((c) => (c.value === undefined ? { field: c.field, operator: c.operator } : { field: c.field, operator: c.operator, value: c.value }));
}

function sameCriteria(a: Criterion[], b: Criterion[]): boolean {
  return JSON.stringify(project(a)) === JSON.stringify(project(b));
}

export function ViewBar({ viewId, criteria, sort, cols, onApply }: Props) {
  const [views, setViews] = useState<SavedView[]>([]);
  const [name, setName] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = () =>
    listViews()
      .then(setViews)
      .catch(() => setMessage("Could not load saved views."));

  useEffect(() => {
    reload();
  }, []);

  const active = viewId ? views.find((v) => v.id === viewId) ?? null : null;
  const viewCols = active ? active.visibleColumns.join(",") || null : null;
  const dirty =
    !!active && (!sameCriteria(active.criteria, criteria) || (active.sort ?? null) !== sort || viewCols !== cols);

  const run = async (action: () => Promise<unknown>, success: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      setMessage(success);
      await reload();
    } catch (e: unknown) {
      setMessage(e instanceof Error ? e.message : "The request failed.");
    } finally {
      setBusy(false);
    }
  };

  const saveAsNew = () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setMessage("Give the view a name first.");
      return;
    }
    run(async () => {
      const created = await createView({
        name: trimmed,
        criteria: project(criteria),
        sort,
        visibleColumns: cols ? cols.split(",") : [],
      });
      setName("");
      onApply(created);
    }, "View saved.");
  };

  const update = () => {
    if (!active) {
      return;
    }
    run(async () => {
      const updated = await updateView(active.id, {
        name: active.name,
        criteria: project(criteria),
        sort,
        visibleColumns: cols ? cols.split(",") : [],
      });
      onApply(updated);
    }, "View updated.");
  };

  const remove = () => {
    if (!active || !window.confirm(`Delete the view "${active.name}"?`)) {
      return;
    }
    run(async () => {
      await deleteView(active.id);
      onApply(null);
    }, "View deleted.");
  };

  return (
    <div class="view-bar" role="group" aria-label="Saved views">
      <label>
        <span class="sr-only">Saved view</span>
        <select
          class="input"
          value={active ? active.id : ""}
          onChange={(e) => {
            const id = (e.target as HTMLSelectElement).value;
            const view = views.find((v) => v.id === id) ?? null;
            onApply(view);
          }}
        >
          <option value="">No saved view</option>
          {views.map((v) => (
            <option key={v.id} value={v.id}>
              {v.name}
            </option>
          ))}
        </select>
      </label>

      {active && dirty && <span class="muted">Unsaved changes</span>}
      {active && dirty && (
        <button class="btn primary" data-role="green" type="button" disabled={busy} onClick={update}>
          Update view
        </button>
      )}
      {active && (
        <button class="btn" type="button" disabled={busy} onClick={remove}>
          Delete view
        </button>
      )}

      {criteria.length > 0 && !active && (
        <form
          class="save-view"
          onSubmit={(e) => {
            e.preventDefault();
            saveAsNew();
          }}
        >
          <label>
            <span class="sr-only">New view name</span>
            <input class="input" placeholder="Name this view" value={name} onInput={(e) => setName((e.target as HTMLInputElement).value)} />
          </label>
          <button class="btn" type="submit" disabled={busy}>
            Save as view
          </button>
        </form>
      )}

      {message && (
        <p class="muted" role="status">
          {message}
        </p>
      )}
    </div>
  );
}
