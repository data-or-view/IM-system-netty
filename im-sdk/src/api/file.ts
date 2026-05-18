import { OP } from "../types.js";
import type { WsTransport } from "../transport/ws.js";

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
  constructor(private transport: WsTransport) {}

  /** 上传小文件（通过 WS 二进制帧） */
  upload(fileName: string, fileData: Uint8Array, mimeType?: string): Promise<string> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FILE_UPLOAD, {
      fileName,
      ...(mimeType ? { mimeType } : {}),
    });

    // 发送 JSON header frame，后跟二进制 body
    // 注意：当前后端只接收 TextWebSocketFrame，二进制 body 需要额外处理
    // v1: 只发送 JSON header，body 为空
    this.transport.send(frame);
    return promise.then((r) => r.data as string);
  }

  /** 初始化分片上传 */
  multipartInit(fileName: string, fileSize: number, mimeType?: string): Promise<MultipartInitResult> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FILE_MULTIPART_INIT, {
      fileName,
      fileSize,
      ...(mimeType ? { mimeType } : {}),
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as MultipartInitResult);
  }

  /** 上传分片 */
  multipartUpload(uploadId: string, partNumber: number, data: Uint8Array): Promise<string> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FILE_MULTIPART_UPLOAD, {
      uploadId,
      partNumber,
      // data 通过 HTTP multipart 或 base64 传输
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as string);
  }

  /** 完成分片上传 */
  multipartComplete(uploadId: string, parts: Array<{ partNumber: number; etag: string }>): Promise<MultipartUploadResult> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FILE_MULTIPART_COMPLETE, {
      uploadId,
      parts,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as MultipartUploadResult);
  }

  /** 取消分片上传 */
  multipartAbort(uploadId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FILE_MULTIPART_ABORT, {
      uploadId,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }
}
