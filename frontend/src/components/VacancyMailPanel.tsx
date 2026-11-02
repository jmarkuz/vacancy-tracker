import { useEffect, useState } from "preact/hooks";
import { listMail, type Mail } from "../api/mail";
import { MailList } from "./MailList";

/** The mail linked to this vacancy, with any suggested status (T-087). Shown in the drawer. */
export function VacancyMailPanel({ vacancyId, onStatusChanged }: { vacancyId: string; onStatusChanged: () => void }) {
  const [items, setItems] = useState<Mail[]>([]);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    listMail({ vacancyId }, controller.signal).then(setItems).catch(() => undefined);
    return () => controller.abort();
  }, [vacancyId, reload]);

  if (items.length === 0) {
    return null;
  }
  return (
    <section class="drawer-section" aria-labelledby="vacancy-mail-title">
      <h3 id="vacancy-mail-title">Mail about this application</h3>
      <MailList
        items={items}
        onChanged={() => {
          setReload((n) => n + 1);
          onStatusChanged();
        }}
      />
    </section>
  );
}
