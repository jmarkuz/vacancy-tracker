import { useEffect, useState } from "preact/hooks";
import { Placeholder } from "./screens/Placeholder";
import { CommandPalette } from "./components/CommandPalette";
import { isPaletteShortcut } from "./domain/palette";
import { RefreshControl } from "./components/RefreshControl";
import { type Screen, useRoute } from "./router";
import { Dashboard } from "./screens/Dashboard";
import { Pipeline } from "./screens/Pipeline";
import { Inbox } from "./screens/Inbox";
import { Profiles } from "./screens/Profiles";
import { Sources } from "./screens/Sources";
import { listProfiles } from "./api/profiles";
import { wizardSkipped } from "./domain/firstRun";
import { applyTheme, nextTheme, readTheme, type Theme } from "./theme";

/** `short` labels the bottom bar on phones, where the full names would not fit. */
const NAV: { id: Screen; label: string; short: string }[] = [
  { id: "dashboard", label: "Dashboard", short: "Home" },
  { id: "inbox", label: "Inbox", short: "Inbox" },
  { id: "pipeline", label: "Pipeline", short: "Pipeline" },
  { id: "sources", label: "Sources & Runs", short: "Sources" },
  { id: "profiles", label: "Search profiles", short: "Profiles" },
  { id: "export", label: "Export", short: "Export" },
];

const SCREEN_INFO: Record<Screen, { title: string; task: string; description: string }> = {
  dashboard: { title: "Dashboard", task: "M3 (dashboard)", description: "KPI cards and top matches appear here." },
  inbox: { title: "Inbox", task: "T-048", description: "New vacancies waiting for triage." },
  pipeline: { title: "Pipeline", task: "T-019 and T-022", description: "Table of all tracked vacancies." },
  sources: { title: "Sources & Runs", task: "T-047", description: "Source health and recent collection runs." },
  profiles: { title: "Search profiles", task: "T-055", description: "Rule builder for search profiles." },
  export: { title: "Export", task: "T-024 to T-026", description: "CSV, XLSX and JSON backup downloads." },
};

export function App() {
  const { screen, params } = useRoute();
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [paletteOpen, setPaletteOpen] = useState(false);

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
  }, []);

  const info = SCREEN_INFO[screen];
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
            <Pipeline params={params} />
          ) : screen === "sources" ? (
            <Sources />
          ) : screen === "inbox" ? (
            <Inbox params={params} />
          ) : screen === "dashboard" ? (
            <Dashboard />
          ) : screen === "profiles" ? (
            <Profiles params={params} />
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
