interface Props {
  title: string;
  task: string;
  description: string;
}

/** Empty state for screens that are built by later tasks; names the task so the gap is visible. */
export function Placeholder({ title, task, description }: Props) {
  return (
    <section class="panel empty" aria-labelledby="screen-title">
      <h1 id="screen-title">{title}</h1>
      <p class="muted">{description}</p>
      <p class="subtle num">Built in {task}</p>
    </section>
  );
}
