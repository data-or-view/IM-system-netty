package com.im.bootstrap.ws;

import com.im.core.dispatcher.ApiDispatcher;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsRequestAdapterTest {

    @Test
    void rejectedDispatchReturnsServiceUnavailableAck() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new WsRequestAdapter(dispatcher, new RejectingExecutorService()));

        assertFalse(channel.writeInbound(new TextWebSocketFrame("{\"op\":\"user.info\",\"seq\":7,\"Authorization\":\"token\"}")));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("\"op\":\"user.info_ack\""));
        assertTrue(response.text().contains("\"seq\":7"));
        assertTrue(response.text().contains("\"code\":503"));
    }

    private static class RejectingExecutorService extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("executor stopped");
        }
    }
}
