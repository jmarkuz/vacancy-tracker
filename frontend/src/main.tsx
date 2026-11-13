import { render } from "preact";
import { watchUnauthorized } from "./api/auth";
import { App } from "./App";
import { installGlassMotion } from "./domain/glassMotion";
import "./styles/tokens.css";
import "./styles/app.css";
import "./styles/graphite.css";
import "./styles/liquid-glass.css";

watchUnauthorized();
installGlassMotion();
render(<App />, document.getElementById("app") as HTMLElement);
