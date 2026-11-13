import { useEffect, useRef, useState } from "preact/hooks";
import type { MailCounts } from "../api/mail";
import {
  type LinkedFilter,
  MAIL_CATEGORIES,
  type MailFilters,
  hasMailFilters,
  toggleCategory,
} from "../domain/mailFilter";
import { Icon } from "./Icon";

/** The search text is sent to the URL (and the server) this long after the last key. */
export const SEARCH_DEBOUNCE_MS = 300;

const LINKED_CHOICES: { id: LinkedFilter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "linked", label: "Linked" },
  { id: "unlinked", label: "Not linked" },
];

/**
 * Filters of the Mail list: a search field, the kinds of mail as toggle chips (none chosen = all, counts from the
 * server) and linked / not linked. It only reports changes; the screen keeps them in the URL.
 */
export function MailFilterBar({
  filters,
  counts,
  onChange,
}: {
  filters: MailFilters;
  counts: MailCounts | null;
  onChange: (next: MailFilters) => void;
}) {
  const [text, setText] = useState(filters.q);
  // The text last sent up: a change of `filters.q` that is only the echo of it must not overwrite what is being typed.
  const sent = useRef(filters.q);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latest = useRef({ filters, onChange });
  latest.current = { filters, onChange };

  // Clear filters, Back or an edited link change the query from outside: show it in the field.
  useEffect(() => {
    if (filters.q !== sent.current) {
      sent.current = filters.q;
      cancelTyping();
      setText(filters.q);
    }
  }, [filters.q]);

  useEffect(
    () => () => {
      if (timer.current) {
        clearTimeout(timer.current);
      }
    },
    [],
  );

  const send = (q: string) => {
    sent.current = q;
    latest.current.onChange({ ...latest.current.filters, q });
  };

  const type = (value: string) => {
    setText(value);
    if (timer.current) {
      clearTimeout(timer.current);
    }
    timer.current = setTimeout(() => {
      timer.current = null;
      send(value);
    }, SEARCH_DEBOUNCE_MS);
  };

  const cancelTyping = () => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  };

  const clearText = () => {
    cancelTyping();
    setText("");
    send("");
  };

  const clearAll = () => {
    cancelTyping();
    setText("");
    sent.current = "";
    onChange({ categories: [], linked: "all", q: "" });
  };

  return (
    <div class="mail-filters">
      <div class="mail-search">
        <label class="sr-only" for="mail-search">
          Search mail by subject, sender or text
        </label>
        <span class="mail-search-icon" aria-hidden="true">
          <Icon name="search" />
        </span>
        <input
          id="mail-search"
          class="input"
          type="search"
          autoComplete="off"
          placeholder="Search subject, sender, text"
          maxLength={200}
          value={text}
          onInput={(e) => type((e.target as HTMLInputElement).value)}
        />
        {text !== "" && (
          <button class="mail-search-clear" type="button" aria-label="Clear search" onClick={clearText}>
            <Icon name="close" />
          </button>
        )}
      </div>
      <div class="mail-chips" role="group" aria-label="Kind of mail">
        {MAIL_CATEGORIES.map((c) => (
          <button
            key={c.id}
            class="chip"
            type="button"
            aria-pressed={filters.categories.includes(c.id)}
            onClick={() => onChange({ ...filters, categories: toggleCategory(filters.categories, c.id) })}
          >
            {c.label}
            {counts && <span class="chip-count num">{counts.categories[c.id] ?? 0}</span>}
          </button>
        ))}
      </div>
      <div class="mail-chips" role="group" aria-label="Linked to an application">
        {LINKED_CHOICES.map((c) => (
          <button
            key={c.id}
            class="chip"
            type="button"
            aria-pressed={filters.linked === c.id}
            onClick={() => onChange({ ...filters, linked: c.id })}
          >
            {c.label}
          </button>
        ))}
        {hasMailFilters(filters) && (
          <button class="btn mail-filters-clear" type="button" onClick={clearAll}>
            Clear filters
          </button>
        )}
      </div>
    </div>
  );
}
