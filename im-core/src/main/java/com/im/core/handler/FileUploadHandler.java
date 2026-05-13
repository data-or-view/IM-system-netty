package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IFileStorageService;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

/**
 * 文件上传处理器。
 *
 * <p>客户端通过 WebSocket/TCP 发送文件二进制数据，服务器存储到 MinIO 并返回访问 URL。</p>
 *
 * <h3>请求 (FILE_UPLOAD=100)</h3>
 * <pre>
 * HEADERS:
 *   fileName   — 原始文件名（可选，如 "photo.jpg"）
 *   mimeType   — MIME 类型（可选，如 "image/jpeg"）
 * BODY:
 *   文件二进制内容
 * </pre>
 *
 * <h3>响应 (FILE_UPLOAD_ACK=101)</h3>
 * <pre>
 * HEADERS:
 *   status     — "OK"
 *   fileUrl    — 文件可访问 URL
 *   fileId     — 服务端生成的文件 ID
 *   fileName   — 原始文件名（回传）
 *   mimeType   — MIME 类型（回传）
 *   fileSize   — 文件大小（字节）
 * </pre>
 */
public class FileUploadHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(FileUploadHandler.class);

    /** 最大文件大小：10 MB */
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    private static final String DEFAULT_BUCKET = "im-system";

    private final IFileStorageService fileStorage;

    public FileUploadHandler(IFileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.FILE_UPLOAD);
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String fileName = msg.getHeader("fileName");
        String mimeType = msg.getHeader("mimeType");
        byte[] body = msg.getBody();

        // 校验
        if (body == null || body.length == 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "file body is empty");
        }
        if (body.length > MAX_FILE_SIZE) {
            throw new ImException(ImErrorCode.BAD_REQUEST,
                    "file too large: " + body.length + " (max " + MAX_FILE_SIZE + ")");
        }

        // 生成文件 ID 和存储路径
        String ext = extractExtension(fileName);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String objectId = "uploads/" + fileId + (ext != null ? ext : "");

        // 上传到 MinIO
        String fileUrl = fileStorage.upload(DEFAULT_BUCKET, objectId, body, mimeType);

        // 回复 ACK
        IMCommand ack = msg.createAcknowledgement(CommandType.FILE_UPLOAD_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("fileUrl", fileUrl);
        ack.putHeader("fileId", fileId);
        ack.putHeader("fileName", fileName != null ? fileName : "");
        ack.putHeader("mimeType", mimeType != null ? mimeType : "");
        ack.putHeader("fileSize", String.valueOf(body.length));
        ctx.writeAndFlush(ack);

        log.info("File uploaded: fileId={}, fileName={}, size={}, url={}",
                fileId, fileName, body.length, fileUrl);
    }

    /** 从文件名提取扩展名（含点），如 "photo.jpg" → ".jpg" */
    private static String extractExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        String ext = fileName.substring(dot).toLowerCase();
        // 仅允许常见扩展名
        if (ext.matches("\\.(jpg|jpeg|png|gif|webp|bmp|mp4|mp3|wav|ogg|pdf|doc|docx|xls|xlsx|zip|txt|json|csv)")) {
            return ext;
        }
        return null;
    }
}
