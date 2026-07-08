export type LogLevel = "debug" | "info" | "warn" | "error" | "silent";
export type LogContext = Record<string, unknown>;

export interface LogEntry {
  level: Exclude<LogLevel, "silent">;
  namespace: string;
  message: string;
  context?: LogContext;
  error?: unknown;
  timestamp: string;
}

export type LogSink = (entry: LogEntry) => void;

export interface LoggerConfig {
  level?: LogLevel;
  sink?: LogSink;
}

export interface AppLogger {
  debug(message: string, context?: LogContext): void;
  info(message: string, context?: LogContext): void;
  warn(message: string, context?: LogContext): void;
  error(message: string, context?: LogContext): void;
}

const LEVEL_WEIGHT: Record<LogLevel, number> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
  silent: 50,
};

const DEFAULT_LEVEL: LogLevel = "info";

let activeLevel: LogLevel = DEFAULT_LEVEL;
let activeSink: LogSink = consoleSink;

export function configureLogger(config: LoggerConfig): () => void {
  const previousLevel = activeLevel;
  const previousSink = activeSink;
  if (config.level) {
    activeLevel = config.level;
  }
  if (config.sink) {
    activeSink = config.sink;
  }
  return () => {
    activeLevel = previousLevel;
    activeSink = previousSink;
  };
}

export function createLogger(namespace: string): AppLogger {
  const normalizedNamespace = normalizeNamespace(namespace);
  return {
    debug: (message, context) => writeLog("debug", normalizedNamespace, message, context),
    info: (message, context) => writeLog("info", normalizedNamespace, message, context),
    warn: (message, context) => writeLog("warn", normalizedNamespace, message, context),
    error: (message, context) => writeLog("error", normalizedNamespace, message, context),
  };
}

function writeLog(
  level: Exclude<LogLevel, "silent">,
  namespace: string,
  message: string,
  context?: LogContext,
): void {
  if (LEVEL_WEIGHT[level] < LEVEL_WEIGHT[activeLevel]) {
    return;
  }
  activeSink({
    level,
    namespace,
    message,
    context,
    error: context?.error,
    timestamp: new Date().toISOString(),
  });
}

function normalizeNamespace(namespace: string): string {
  const normalized = namespace.trim().replace(/\s+/g, ".").replace(/\.+/g, ".");
  return normalized || "app";
}

function consoleSink(entry: LogEntry): void {
  const prefix = `[${entry.namespace}] ${entry.message}`;
  const payload = entry.context ? [prefix, entry.context] : [prefix];
  switch (entry.level) {
    case "debug":
      console.debug(...payload);
      break;
    case "info":
      console.info(...payload);
      break;
    case "warn":
      console.warn(...payload);
      break;
    case "error":
      console.error(...payload);
      break;
  }
}
