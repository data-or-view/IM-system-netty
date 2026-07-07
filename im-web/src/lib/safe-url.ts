const SAFE_PROTOCOLS = new Set(["http:", "https:", "blob:"]);
const SCHEME_RE = /^[a-zA-Z][a-zA-Z\d+.-]*:/;

export function safeExternalUrl(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (!trimmed || hasUnsafeControlChars(trimmed)) return undefined;
  try {
    const parsed = new URL(trimmed, defaultBaseUrl());
    if (!SAFE_PROTOCOLS.has(parsed.protocol)) return undefined;
    return isRelativeUrl(trimmed) ? trimmed : parsed.href;
  } catch {
    return undefined;
  }
}

export function safeMediaUrl(value: unknown): string | undefined {
  return safeExternalUrl(value);
}

function isRelativeUrl(value: string): boolean {
  return !SCHEME_RE.test(value) && !value.startsWith("//");
}

function defaultBaseUrl(): string {
  return globalThis.location?.origin ?? "http://localhost";
}

function hasUnsafeControlChars(value: string): boolean {
  return /[\u0000-\u001f\u007f]/.test(value);
}
