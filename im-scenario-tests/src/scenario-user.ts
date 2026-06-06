import { ScenarioHttpClient } from "./http-client.js";
import { ScenarioWsClient } from "./ws-client.js";
import type { RegisterResult, TokenPair } from "./types.js";

export interface ScenarioUserOptions {
  httpUrl: string;
  wsUrl: string;
  requestTimeoutMs: number;
  password: string;
  nickname: string;
}

export class ScenarioUser {
  readonly http: ScenarioHttpClient;
  readonly ws: ScenarioWsClient;
  userId?: string;
  token?: string;
  refreshToken?: string;

  constructor(private readonly options: ScenarioUserOptions) {
    this.http = new ScenarioHttpClient({
      baseUrl: options.httpUrl,
      requestTimeoutMs: options.requestTimeoutMs,
      getToken: () => this.token,
    });
    this.ws = new ScenarioWsClient(options.wsUrl, {
      requestTimeoutMs: options.requestTimeoutMs,
      getToken: () => this.token,
    });
  }

  async register(): Promise<string> {
    // Registration is intentionally HTTP-only here so scenarios follow the same public contract as web clients.
    const result = await this.http.post<RegisterResult>("/api/user/register", {
      password: this.options.password,
      nickname: this.options.nickname,
    });
    this.userId = result.userId;
    return result.userId;
  }

  async connectAndLogin(): Promise<void> {
    if (!this.userId) throw new Error("register must be called before login");
    await this.ws.connect();
    const response = await this.ws.request<TokenPair>("login", {
      userId: this.userId,
      password: this.options.password,
    });
    this.token = response.data?.token;
    this.refreshToken = response.data?.refreshToken;
    if (!this.token) {
      throw new Error(`login did not return token for ${this.userId}`);
    }
  }

  close(): void {
    this.ws.close();
  }
}
