import { loadScenarioConfig } from "../src/config.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const user = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Smoke ${suffix}`,
});

try {
  reporter.step("registering one user through HTTP");
  const userId = await user.register();
  reporter.metric("userId", userId);

  reporter.step("connecting websocket and logging in");
  await user.connectAndLogin();

  reporter.step("fetching current user profile through authenticated HTTP");
  const info = await user.http.get<{ userId: string }>("/api/user/info", { userId });
  if (info.userId !== userId) {
    throw new Error(`user.info returned ${info.userId}, expected ${userId}`);
  }

  reporter.finish();
} finally {
  user.close();
}
