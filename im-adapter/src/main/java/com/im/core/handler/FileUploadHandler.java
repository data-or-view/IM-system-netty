package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.core.usecase.FileUploadUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class FileUploadHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(FileUploadHandler.class);

    private final FileUploadUseCase fileUploadUseCase;

    public FileUploadHandler(FileUploadUseCase fileUploadUseCase) {
        this.fileUploadUseCase = fileUploadUseCase;
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

        FileUploadUseCase.FileUploadResult result = fileUploadUseCase.execute(fileName, mimeType, body);

        IMCommand ack = msg.createAcknowledgement(CommandType.FILE_UPLOAD_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("fileUrl", result.fileUrl());
        ack.putHeader("fileId", result.fileId());
        ack.putHeader("fileName", result.fileName());
        ack.putHeader("mimeType", result.mimeType());
        ack.putHeader("fileSize", String.valueOf(result.fileSize()));
        ctx.writeAndFlush(ack);

        log.info("File uploaded: fileId={}, fileName={}, size={}, url={}",
                result.fileId(), result.fileName(), result.fileSize(), result.fileUrl());
    }
}
