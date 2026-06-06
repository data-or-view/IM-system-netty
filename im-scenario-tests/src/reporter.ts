export class ScenarioReporter {
  private readonly startedAt = Date.now();

  step(message: string): void {
    console.log(`[scenario] ${message}`);
  }

  metric(name: string, value: string | number): void {
    console.log(`[metric] ${name}=${value}`);
  }

  finish(): void {
    this.metric("durationMs", Date.now() - this.startedAt);
  }
}
