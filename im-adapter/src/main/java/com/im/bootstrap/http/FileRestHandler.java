package com.im.bootstrap.http;

import com.im.api.IFileStorageService;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * 文件上传域 REST 控制器。
 *
 * <p>处理 /api/file/upload 路由：multipart 文件上传到对象存储。</p>
 */
public class FileRestHandler implements RestController {

    private static final Logger log = LoggerFactory.getLogger(FileRestHandler.class);

    private final IFileStorageService fileStorage;

    public FileRestHandler(IFileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public void register(HttpRestHandler router) {
        router.post("/api/file/upload", this::handleUpload);
    }

    private Object handleUpload(FullHttpRequest req, ChannelHandlerContext ctx) {
        if (!req.method().equals(HttpMethod.POST)) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "POST required for file upload");
        }

        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(
                new DefaultHttpDataFactory(false), req);
        String fileName = "";
        String mimeType = "";
        byte[] fileData = null;

        try {
            for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                if (data instanceof FileUpload upload) {
                    fileName = upload.getFilename();
                    mimeType = upload.getContentType();
                    fileData = upload.get();
                } else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                    var attr = (io.netty.handler.codec.http.multipart.Attribute) data;
                    if ("fileName".equals(attr.getName())) fileName = attr.getValue();
                    else if ("mimeType".equals(attr.getName())) mimeType = attr.getValue();
                }
            }
        } catch (Exception e) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "file upload error: " + e.getMessage());
        }

        if (fileData == null || fileData.length == 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "file body is empty");
        }

        String ext = extractExtension(fileName);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String objectId = "uploads/" + fileId + (ext != null ? ext : "");
        String fileUrl = fileStorage.upload("im-system", objectId, fileData, mimeType);

        log.info("File uploaded: fileId={}, fileName={}, size={}", fileId, fileName, fileData.length);
        return Map.of("fileId", fileId, "fileUrl", fileUrl, "fileName", fileName,
                "mimeType", mimeType, "fileSize", fileData.length, "status", "OK");
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot).toLowerCase();
    }
}
