import { Fragment } from "preact";
import { useEffect, useState } from "preact/hooks";
import { Placeholder } from "./screens/Placeholder";
import { CommandPalette } from "./components/CommandPalette";
import { isPaletteShortcut } from "./domain/palette";
import { DATA_CHANGED_EVENT, RefreshControl } from "./components/RefreshControl";
import { type Screen, useRoute } from "./router";
import { Dashboard } from "./screens/Dashboard";
import { Pipeline } from "./screens/Pipeline";
import { Inbox } from "./screens/Inbox";
import { Profiles } from "./screens/Profiles";
import { Sources } from "./screens/Sources";
import { Transfer } from "./screens/Transfer";
import { ResumeScreen } from "./screens/ResumeScreen";
import { PresetsScreen } from "./screens/PresetsScreen";
import { Settings } from "./screens/Settings";
import { MailScreen } from "./screens/MailScreen";
import { getAuthStatus, logout, UNAUTHORIZED_EVENT } from "./api/auth";
import { LoginScreen } from "./components/LoginScreen";
import { listProfiles } from "./api/profiles";
import { wizardSkipped } from "./domain/firstRun";
import { applyTextSize, applyTheme, nextTheme, readTextSize, readTheme, type Theme } from "./theme";
import { LogoMark } from "./components/LogoMark";

/** Color roles of Beacon (ADR-052): blue looks for work, green moves it forward, purple is you and AI, amber sets up. */
export type Role = "blue" | "green" | "purple" | "amber";

/** The sections and their roles; "My profile" groups what describes the owner and how the app works for them. */
const NAV: { id: Screen; label: string; role: Role; group?: string }[] = [
  { id: "dashboard", label: "Dashboard", role: "blue" },
  { id: "inbox", label: "Inbox", role: "blue" },
  { id: "pipeline", label: "Pipeline", role: "green" },
  { id: "mail", label: "Mail", role: "blue" },
  { id: "resume", label: "Resume", role: "purple", group: "My profile" },
  { id: "presets", label: "Presets", role: "purple" },
  { id: "sources", label: "Sources & Runs", role: "amber" },
  { id: "profiles", label: "Search profiles", role: "amber" },
  { id: "export", label: "Export & import", role: "amber" },
  { id: "settings", label: "Settings", role: "amber" },
];

const THEME_LABEL: Record<Theme, string> = { light: "Light theme", dark: "Dark theme", system: "System theme" };

const SCREEN_INFO: Record<Screen, { title: string; task: string; description: string }> = {
  dashboard: { title: "Dashboard", task: "M3 (dashboard)", description: "KPI cards and top matches appear here." },
  inbox: { title: "Inbox", task: "T-048", description: "New vacancies waiting for triage." },
  pipeline: { title: "Pipeline", task: "T-019 and T-022", description: "Table of all tracked vacancies." },
  mail: { title: "Mail", task: "T-086", description: "Answers of employers and job mail." },
  resume: { title: "Resume", task: "T-080", description: "Your resume and the keywords the fit score uses." },
  presets: { title: "Presets", task: "T-093", description: "AI presets: goal, weights, prompts and base documents." },
  sources: { title: "Sources & Runs", task: "T-047", description: "Source health and recent collection runs." },
  profiles: { title: "Search profiles", task: "T-055", description: "Rule builder for search profiles." },
  export: { title: "Export & import", task: "T-079", description: "CSV, XLSX and JSON backup downloads and imports." },
  settings: { title: "Settings", task: "T-082", description: "AI opt-in and mail tracking." },
};

export function App() {
  const { screen, params } = useRoute();
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [paletteOpen, setPaletteOpen] = useState(false);
  // Bumped when a refresh has stored new data, so the screen on show loads again.
  const [dataVersion, setDataVersion] = useState(0);
  useEffect(() => {
    const onChanged = () => setDataVersion((v) => v + 1);
    window.addEventListener(DATA_CHANGED_EVENT, onChanged);
    return () => window.removeEventListener(DATA_CHANGED_EVENT, onChanged);
  }, []);
  // "checking" until the server says whether a token is needed; "login" shows the sign-in page.
  const [session, setSession] = useState<"checking" | "login" | "open">("checking");
  const [tokenRequired, setTokenRequired] = useState(false);

  useEffect(() => {
    getAuthStatus()
      .then((s) => {
        setTokenRequired(s.required);
        setSession(s.authenticated ? "open" : "login");
      })
      .catch(() => setSession("open"));
    const lost = () => setSession("login");
    window.addEventListener(UNAUTHORIZED_EVENT, lost);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, lost);
  }, []);

  useEffect(() => applyTheme(theme), [theme]);
  // The base text size chosen in Settings (18, 20 or 22 px); Settings applies changes itself.
  useEffect(() => applyTextSize(readTextSize()), []);

  // Ctrl/Cmd+K works from anywhere, including inside a text box, and replaces the browser's own binding.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isPaletteShortcut(e)) {
        e.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // First run: with no profile yet, a bare address leads to the wizard. A deep link or an earlier skip is left alone.
  useEffect(() => {
    if (session !== "open") {
      return;
    }
    if (!/^#?\/?(dashboard)?$/.test(window.location.hash) || wizardSkipped()) {
      return;
    }
    listProfiles()
      .then((profiles) => {
        if (profiles.length === 0) {
          window.location.hash = "#/profiles?wizard=1";
        }
      })
      .catch(() => undefined);
  }, [session]);

  const info = SCREEN_INFO[screen];
  if (session === "checking") {
    return <p class="muted" role="status">Loading…</p>;
  }
  if (session === "login") {
    return <LoginScreen onLoggedIn={() => { setTokenRequired(true); setSession("open"); }} />;
  }
  return (
    <div class="app">
      <a class="skip-link" href="#content" onClick={(e) => { e.preventDefault(); document.getElementById("content")?.focus(); }}>
        Skip to content
      </a>
      <header class="header">
        <a class="logo" href="#/dashboard" aria-label="Vacancy Tracker, Dashboard">
          <LogoMark />
          <span>Vacancy Tracker</span>
        </a>
        <span class="grow" />
        <button type="button" class="btn palette-open" data-role="blue" onClick={() => setPaletteOpen(true)} aria-label="Search and commands, Ctrl K">
          Search <kbd class="kbd">Ctrl K</kbd>
        </button>
        <RefreshControl />
        <button class="btn theme-toggle" type="button" data-role="amber" onClick={() => setTheme(nextTheme(theme))}
          aria-label={`${THEME_LABEL[theme]}; switch to ${THEME_LABEL[nextTheme(theme)].toLowerCase()}`}>
          {THEME_LABEL[theme]}
        </button>
        {tokenRequired && (
          <button class="btn" type="button" data-role="amber" onClick={() => { logout().then(() => setSession("login"), () => setSession("login")); }}>Sign out</button>
        )}
      </header>

      <aside class="sidebar" aria-label="Sections">
        <nav class="nav">
          {NAV.map((item) => (
            <Fragment key={item.id}>
              {item.group && (
                <p class="nav-group">
                  {item.group}
                </p>
              )}
              <a
                href={`#/${item.id}`}
                class="nav-item"
                data-role={item.role}
                aria-current={screen === item.id ? "page" : undefined}
              >
                {item.label}
              </a>
            </Fragment>
          ))}
        </nav>
      </aside>

      <div class="main">
        <main class="content" id="content" tabIndex={-1} aria-label={info.title}>
          {screen === "pipeline" ? (
            <Pipeline dataVersion={dataVersion} params={params} />
          ) : screen === "sources" ? (
            <Sources dataVersion={dataVersion} />
          ) : screen === "inbox" ? (
            <Inbox dataVersion={dataVersion} params={params} />
          ) : screen === "dashboard" ? (
            <Dashboard dataVersion={dataVersion} />
          ) : screen === "profiles" ? (
            <Profiles params={params} />
          ) : screen === "export" ? (
            <Transfer />
          ) : screen === "resume" ? (
            <ResumeScreen />
          ) : screen === "presets" ? (
            <PresetsScreen params={params} />
          ) : screen === "settings" ? (
            <Settings theme={theme} onTheme={setTheme} />
          ) : screen === "mail" ? (
            <MailScreen dataVersion={dataVersion} />
          ) : (
            <Placeholder title={info.title} task={info.task} description={info.description} />
          )}
        </main>
      </div>

      {paletteOpen && <CommandPalette onClose={() => setPaletteOpen(false)} />}

    </div>
  );
}
