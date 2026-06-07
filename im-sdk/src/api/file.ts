import { IMError, type FileUploadResult } from "../types.js";
import type { HttpTransport } from "../transport/http.js";

export interface MultipartInitResult {
  uploadId: string;
  objectId: string;
}

export interface MultipartUploadResult {
  objectId: string;
  fileUrl?: string;
}

/**
 * 文件模块 API。
 */
export class FileAPI {
  constructor(private transport?: HttpTransport) {}

  /**
   * 上传小文件。
   *
   * 当前后端 WS 只消费文本帧，这里暂时只提交文件元数据；
   * 真正的二进制上传需要后续接入 HTTP upload transport。
   */
  upload(fileName: string, fileData: Uint8Array | Blob, mimeType = "application/octet-stream"): Promise<FileUploadResult> {
    const http = this.transport;
    return http ? http.uploadFile(fileName, fileData, mimeType) : this.missingHttp();
  }

  /** 初始化分片上传 */
  multipartInit(fileName: string, fileSize: number, mimeType?: string): Promise<MultipartInitResult> {
    const http = this.transport;
    if (!http) return this.missingHttp();
    return http.multipartInit(fileName, fileSize, mimeType ?? "application/octet-stream")
      .then((r) => ({
        uploadId: r.uploadId,
        objectId: r.objectId ?? r.fileId ?? "",
      }));
  }

  /** 上传分片 */
  multipartUpload(uploadId: string, partNumber: number, data: Uint8Array): Promise<string> {
    const http = this.transport;
    return http ? http.uploadPart(uploadId, partNumber, data) : this.missingHttp();
  }

  /** 完成分片上传 */
  multipartComplete(uploadId: string, parts: Array<{ partNumber: number; etag: string }>): Promise<MultipartUploadResult> {
    const http = this.transport;
    if (!http) return this.missingHttp();
    return http.multipartComplete(uploadId, parts)
      .then((r) => ({
        objectId: r.fileId,
        fileUrl: r.fileUrl,
      }));
  }

  /** 取消分片上传 */
  multipartAbort(uploadId: string): Promise<void> {
    const http = this.transport;
    return http ? http.multipartAbort(uploadId) : this.missingHttp();
  }

  private missingHttp<T>(): Promise<T> {
    return Promise.reject(new IMError(-1, "File API requires httpUrl"));
  }
}
