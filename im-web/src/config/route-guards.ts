import { APP_ROUTES, getRedirectTarget } from "@/config/routes";

export interface AuthRouteInput {
  authenticated: boolean;
  pathname: string;
  search: string;
  hash: string;
}

export type AuthRouteDecision =
  | { kind: "show-login"; redirectTarget: string }
  | { kind: "show-app"; redirectTarget: string }
  | { kind: "redirect"; to: string };

export function resolveAuthRoute(input: AuthRouteInput): AuthRouteDecision {
  const redirectTarget = getRedirectTarget(input.search);
  const isLoginRoute = input.pathname === APP_ROUTES.login;

  if (!input.authenticated) {
    if (isLoginRoute) {
      return { kind: "show-login", redirectTarget };
    }
    return { kind: "redirect", to: APP_ROUTES.loginWithRedirect(currentPath(input)) };
  }

  if (isLoginRoute) {
    return { kind: "redirect", to: redirectTarget };
  }

  return { kind: "show-app", redirectTarget };
}

function currentPath(input: Pick<AuthRouteInput, "pathname" | "search" | "hash">): string {
  return `${input.pathname}${input.search}${input.hash}`;
}
