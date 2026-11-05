import { render } from "preact";
import { watchUnauthorized } from "./api/auth";
import { App } from "./App";
import "./styles/tokens.css";
import "./styles/app.css";
import "./styles/graphite.css";
import "./styles/liquid-glass.css";

watchUnauthorized();
render(<App />, document.getElementById("app") as HTMLElement);
