package com.im.bootstrap.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.PushEvent;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ChannelHandler.Sharable
public class WsPushEventEncoder extends MessageToMessageEncoder<PushEvent> {

    private static final Logger log = LoggerFactory.getLogger(WsPushEventEncoder.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    @Override
    protected void encode(ChannelHandlerContext ctx, PushEvent event, List<Object> out) {
        try {
            out.add(new TextWebSocketFrame(MAPPER.writeValueAsString(event.toEnvelope())));
        } catch (Exception e) {
            log.error("Failed to encode WS push event: op={}", event.op(), e);
        }
    }
}
