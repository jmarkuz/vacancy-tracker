import { useEffect, useState } from "preact/hooks";
import { type AiStatus, disableAi, enableAi, getAiStatus } from "../api/ai";
import { ApiError } from "../api/client";
import { formatDate } from "./columns";
import { applyGlass, applyTextSize, type Glass, readGlass, readTextSize, TEXT_SIZES, type TextSize, type Theme } from "../theme";

const PROVIDER_LABEL: Record<string, string> = {
  off: "Not configured",
  cli: "Claude Code CLI under your own subscription",
  api: "Claude API with your API key",
  fake: "Fake provider for tests (nothing is sent anywhere)",
};

/**
 * Settings (T-082): the AI opt-in. AI is off by default; nothing is sent to Claude before the owner reads the notice,
 * ticks the box and switches it on. Switching it off takes effect at once. Appearance (Beacon, T-101): the theme and
 * the base text size (18, 20 or 22 px), kept in this browser.
 */
export function Settings({ theme, onTheme }: { theme: Theme; onTheme: (theme: Theme) => void }) {
  const [textSize, setTextSize] = useState<TextSize>(readTextSize);
  const [glass, setGlass] = useState<Glass>(readGlass);
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [agreed, setAgreed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    getAiStatus(controller.signal)
      .then(setStatus)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : "Could not load the settings.");
        }
      });
    return () => controller.abort();
  }, []);

  const change = async (action: () => Promise<AiStatus>) => {
    setBusy(true);
    setError(null);
    try {
      setStatus(await action());
      setAgreed(false);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "The change failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="settings-title" class="stack">
      <div class="toolbar">
        <h1 id="settings-title">Settings</h1>
      </div>
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}

      <div class="panel stack" data-role="amber">
        <h2>Appearance</h2>
        <fieldset class="choice-group">
          <legend>Theme</legend>
          {(["light", "dark", "system"] as Theme[]).map((t) => (
            <label key={t} class="choice">
              <input type="radio" name="theme" value={t} checked={theme === t} onChange={() => onTheme(t)} />{" "}
              {t === "light" ? "Light" : t === "dark" ? "Dark" : "Follow the system"}
            </label>
          ))}
        </fieldset>
        <fieldset class="choice-group">
          <legend>Text size</legend>
          {TEXT_SIZES.map((size) => (
            <label key={size} class="choice">
              <input
                type="radio"
                name="text-size"
                value={size}
                checked={textSize === size}
                onChange={() => {
                  setTextSize(size);
                  applyTextSize(size);
                }}
              />{" "}
              {size} px{size === "18" ? " (standard)" : ""}
            </label>
          ))}
        </fieldset>
        <fieldset class="choice-group">
          <legend>Glass effects</legend>
          {(["on", "off"] as Glass[]).map((g) => (
            <label key={g} class="choice">
              <input
                type="radio"
                name="glass"
                value={g}
                checked={glass === g}
                onChange={() => {
                  setGlass(g);
                  applyGlass(g);
                }}
              />{" "}
              {g === "on" ? "On (translucent, translucent glass)" : "Off (solid surfaces)"}
            </label>
          ))}
        </fieldset>
        <p class="muted small">
          Every screen follows these at once. Your browser's own zoom works too, up to 200%. Glass also turns solid by itself when your
          system asks for reduced transparency or more contrast.
        </p>
      </div>

      <div class="panel stack">
        <h2>AI assistance (Claude)</h2>
        {!status && <p class="muted">Loading…</p>}
        {status && (
          <>
            <dl class="facts">
              <dt>Provider</dt>
              <dd>{PROVIDER_LABEL[status.provider] ?? status.provider}</dd>
              <dt>State</dt>
              <dd>
                <span class={`state-badge ${status.enabled ? "on" : "off"}`}>{status.enabled ? "On" : "Off"}</span>
                {status.enabled && status.consentedAt && <span class="muted small"> since {formatDate(status.consentedAt)}</span>}
              </dd>
            </dl>
            <p>{status.notice}</p>
            {!status.configured && (
              <p class="muted">
                To use AI, set <code>APP_AI_PROVIDER=cli</code> (needs Claude Code installed and signed in on this computer) or{" "}
                <code>APP_AI_PROVIDER=api</code> with <code>ANTHROPIC_API_KEY</code>, then restart the app.
              </p>
            )}
            {status.configured && !status.enabled && (
              <div class="stack">
                <label class="inline">
                  <input type="checkbox" checked={agreed} onChange={(e) => setAgreed((e.target as HTMLInputElement).checked)} /> I
                  have read this and agree that my resume and the vacancies I choose are sent to Claude when I press Generate.
                </label>
                <div>
                  <button class="btn primary" data-role="purple" type="button" disabled={!agreed || busy} onClick={() => change(enableAi)}>
                    Switch AI on
                  </button>
                </div>
              </div>
            )}
            {status.enabled && (
              <div>
                <button class="btn danger" type="button" disabled={busy} onClick={() => change(disableAi)}>
                  Switch AI off
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </section>
  );
}
