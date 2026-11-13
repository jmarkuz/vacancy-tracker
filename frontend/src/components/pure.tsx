import { Component, h, type ComponentType, type FunctionComponent } from "preact";

/**
 * A function component that re-renders only when one of its props changed (shallow comparison). Preact has no
 * built-in memo without preact/compat, which would add to the bundle; a list row is the use case: selecting one row
 * or typing in the search box must not redraw the other 99 (UI speed review, 2026-11-13).
 */
export function pure<P extends object>(Inner: FunctionComponent<P>): ComponentType<P> {
  return class Pure extends Component<P> {
    shouldComponentUpdate(next: P): boolean {
      const now = this.props as Record<string, unknown>;
      const then = next as Record<string, unknown>;
      const keys = new Set([...Object.keys(now), ...Object.keys(then)]);
      for (const key of keys) {
        if (key !== "children" && !Object.is(now[key], then[key])) return true;
      }
      return false;
    }
    render() {
      return h(Inner as FunctionComponent<P>, this.props);
    }
  };
}
