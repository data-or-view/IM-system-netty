import { Component, type ErrorInfo, type ReactNode } from "react";
import { Button } from "@/components/ui/button";

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export default class RouteErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("route render failed:", error, info);
  }

  render() {
    if (!this.state.error) {
      return this.props.children;
    }

    return (
      <div className="flex h-full min-h-screen items-center justify-center bg-slate-50 px-4">
        <div className="w-full max-w-md rounded-lg border bg-white p-5 text-center shadow-sm">
          <div className="text-base font-semibold text-slate-900">页面显示异常</div>
          <div className="mt-2 text-sm leading-6 text-slate-500">
            前端渲染遇到问题，当前登录状态不会被清除。可以先返回聊天页，或刷新页面重新加载。
          </div>
          <div className="mt-5 flex justify-center gap-2">
            <Button
              variant="outline"
              onClick={() => {
                this.setState({ error: null });
                window.history.pushState(null, "", "/chat");
                window.dispatchEvent(new PopStateEvent("popstate"));
              }}
            >
              返回聊天页
            </Button>
            <Button onClick={() => window.location.reload()}>重新加载</Button>
          </div>
        </div>
      </div>
    );
  }
}
