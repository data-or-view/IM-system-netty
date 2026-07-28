import { IMConfigError, type FileUploadResult } from "../types.js";
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
    return this.multipartMigrationRequired();
  }

  /** 上传分片 */
  multipartUpload(uploadId: string, partNumber: number, data: Uint8Array): Promise<string> {
    return this.multipartMigrationRequired();
  }

  /** 完成分片上传 */
  multipartComplete(uploadId: string, parts: Array<{ partNumber: number; etag: string }>): Promise<MultipartUploadResult> {
    return this.multipartMigrationRequired();
  }

  /** 取消分片上传 */
  multipartAbort(uploadId: string): Promise<void> {
    const http = this.transport;
    return http ? http.multipartAbort(uploadId) : this.missingHttp();
  }

  private missingHttp<T>(): Promise<T> {
    return Promise.reject(new IMConfigError("File API requires httpUrl"));
  }

  private multipartMigrationRequired<T>(): Promise<T> {
    return Promise.reject(new IMConfigError("Multipart uploads are disabled during POST upload migration"));
  }
}
