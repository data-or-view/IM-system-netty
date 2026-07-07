import { useRef } from "react";
import { Paperclip, Send } from "lucide-react";

interface MessageComposerProps {
  value: string;
  uploading: boolean;
  disabled: boolean;
  canAttach: boolean;
  onChange: (value: string) => void;
  onSend: () => void;
  onFileSelected: (file: File) => void;
}

export default function MessageComposer({
  value,
  uploading,
  disabled,
  canAttach,
  onChange,
  onSend,
  onFileSelected,
}: MessageComposerProps) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      onSend();
    }
  };

  return (
    <div className="border-t border-slate-200/80 bg-white px-4 py-3 md:px-5">
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) onFileSelected(file);
          if (fileInputRef.current) fileInputRef.current.value = "";
        }}
      />
      <div className="mx-auto flex max-w-3xl items-center gap-2">
        <button
          type="button"
          aria-label={uploading ? "正在上传文件" : "发送文件"}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 disabled:cursor-not-allowed disabled:opacity-40"
          disabled={!canAttach || uploading}
          onClick={() => fileInputRef.current?.click()}
          title={uploading ? "正在上传文件" : "发送文件"}
        >
          <Paperclip className="h-4 w-4" />
        </button>

        <div className="flex flex-1 items-center rounded-full border border-slate-200 bg-slate-50 px-4 transition-all focus-within:border-blue-300 focus-within:bg-white focus-within:shadow-sm">
          <input
            className="flex-1 bg-transparent py-2.5 text-sm text-slate-800 outline-none placeholder:text-slate-400"
            placeholder={disabled ? "系统通知不可回复" : "输入消息…"}
            value={value}
            onChange={(event) => onChange(event.target.value)}
            onKeyDown={handleKeyDown}
            disabled={disabled}
          />
        </div>

        <button
          type="button"
          aria-label="发送消息"
          onClick={onSend}
          disabled={!value.trim() || disabled}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-blue-600 text-white shadow-sm transition-all hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <Send className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
