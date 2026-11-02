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
import { applyTheme, nextTheme, readTheme, type Theme } from "./theme";

/** `short` labels the bottom bar on phones, where the full names would not fit. */
const NAV: { id: Screen; label: string; short: string }[] = [
  { id: "dashboard", label: "Dashboard", short: "Home" },
  { id: "inbox", label: "Inbox", short: "Inbox" },
  { id: "pipeline", label: "Pipeline", short: "Pipeline" },
  { id: "mail", label: "Mail", short: "Mail" },
  { id: "resume", label: "Resume", short: "Resume" },
  { id: "presets", label: "Presets", short: "Presets" },
  { id: "sources", label: "Sources & Runs", short: "Sources" },
  { id: "profiles", label: "Search profiles", short: "Profiles" },
  { id: "export", label: "Export & import", short: "Export" },
  { id: "settings", label: "Settings", short: "Settings" },
];

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
      <aside class="sidebar" aria-label="Primary">
        <div class="logo">Vacancy Tracker</div>
        <nav>
          {NAV.map((item) => (
            <a
              key={item.id}
              href={`#/${item.id}`}
              class="nav-item"
              aria-current={screen === item.id ? "page" : undefined}
            >
              {item.label}
            </a>
          ))}
        </nav>
        <button class="btn ghost theme-toggle" onClick={() => setTheme(nextTheme(theme))}>
          Theme: {theme}
        </button>
        {tokenRequired && (
          <button class="btn ghost" type="button" onClick={() => { logout().then(() => setSession("login"), () => setSession("login")); }}>Sign out</button>
        )}
      </aside>

      <div class="main">
        <header class="header">
          <span class="header-title">{info.title}</span>
          <button type="button" class="btn palette-open" onClick={() => setPaletteOpen(true)} aria-label="Search and commands, Ctrl K">
            Search <kbd class="kbd">Ctrl K</kbd>
          </button>
          <RefreshControl />
        </header>
        <main class="content">
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
            <Settings />
          ) : screen === "mail" ? (
            <MailScreen dataVersion={dataVersion} />
          ) : (
            <Placeholder title={info.title} task={info.task} description={info.description} />
          )}
        </main>
      </div>

      {paletteOpen && <CommandPalette onClose={() => setPaletteOpen(false)} />}

      <nav class="mobile-nav" aria-label="Primary mobile">
        {NAV.map((item) => (
          <a
            key={item.id}
            href={`#/${item.id}`}
            aria-current={screen === item.id ? "page" : undefined}
          >
            {item.short}
          </a>
        ))}
      </nav>
    </div>
  );
}
