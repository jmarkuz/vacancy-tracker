import { useState } from "preact/hooks";
import { login } from "../api/auth";
import { ApiError } from "../api/client";

interface Props {
  onLoggedIn: () => void;
}

/** Asks for the access token once (T-071). The server keeps it in an HTTP-only cookie; the page never stores it. */
export function LoginScreen({ onLoggedIn }: Props) {
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: Event) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(token);
      setToken("");
      onLoggedIn();
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : "Could not reach the server.");
      setBusy(false);
    }
  };

  return (
    <main class="login">
      <form class="panel" onSubmit={submit} aria-labelledby="login-title">
        <h1 id="login-title">Vacancy Tracker</h1>
        <p class="muted">Enter the access token from your server settings.</p>
        {error && <div class="notice error" role="alert">{error}</div>}
        <label class="field"><span>Access token</span>
          <input class="input" type="password" autocomplete="current-password" value={token} autofocus
            onInput={(e) => setToken((e.target as HTMLInputElement).value)} />
        </label>
        <button type="submit" class="btn primary" disabled={busy || token === ""}>{busy ? "Checking…" : "Sign in"}</button>
      </form>
    </main>
  );
}
