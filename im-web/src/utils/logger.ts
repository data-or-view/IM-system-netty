/**
 * IM Frontend 简易日志系统。
 * 对每个请求生成 traceId，结构化输出到 console，便于 AI 读取。
 */

// 伪 UUID（够用即可，不依赖 crypto 库）
let reqCounter = 0;
export function nextTraceId(): string {
  return `trc_${Date.now().toString(36)}_${String(++reqCounter).padStart(3, '0')}`;
}

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const PREFIX = '[IM]';

/**
 * 输出结构化 JSON 单行日志。
 *
 * 格式： [IM] [info] traceId=xxx event=xxx data={...}
 * 每个 key=value 空格分隔，data 为 JSON 对象。
 * AI 友好：单行、结构化、精确 key。
 */
export function log(
  level: LogLevel,
  traceId: string,
  event: string,
  data?: Record<string, unknown>,
) {
  const parts: string[] = [
    PREFIX,
    `[${level}]`,
    `traceId=${traceId}`,
    `event=${event}`,
  ];
  if (data) {
    parts.push(`data=${JSON.stringify(data)}`);
  }
  const line = parts.join(' ');
  switch (level) {
    case 'debug': console.debug(line); break;
    case 'info':  console.info(line);  break;
    case 'warn':  console.warn(line);  break;
    case 'error': console.error(line); break;
  }
}

/** 快捷方法 */
export const logDebug = (traceId: string, event: string, data?: Record<string, unknown>) =>
  log('debug', traceId, event, data);
export const logInfo = (traceId: string, event: string, data?: Record<string, unknown>) =>
  log('info', traceId, event, data);
export const logWarn = (traceId: string, event: string, data?: Record<string, unknown>) =>
  log('warn', traceId, event, data);
export const logError = (traceId: string, event: string, data?: Record<string, unknown>) =>
  log('error', traceId, event, data);
