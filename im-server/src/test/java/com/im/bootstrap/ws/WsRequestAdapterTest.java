package com.im.bootstrap.ws;

import com.im.bootstrap.RequestAdmission;
import com.im.bootstrap.RequestScope;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.InfrastructureException;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

class WsRequestAdapterTest {

    @Test
    void requestIdFrameFieldIsExposedAsRequestAttribute() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        dispatcher.registerHandler(com.im.api.Operation.HEARTBEAT, req -> {
            assertEquals("req-ws-1", req.attribute("_requestId"));
            assertEquals(Integer.valueOf(7), req.attribute("_wsSeq"));
            return java.util.Map.of("ok", true);
        });
        EmbeddedChannel channel = new EmbeddedChannel(new WsRequestAdapter(dispatcher, new DirectExecutorService()));

        assertFalse(channel.writeInbound(new TextWebSocketFrame("{\"op\":\"heartbeat\",\"seq\":7,\"_requestId\":\"req-ws-1\"}")));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("\"requestId\":\"req-ws-1\""));
    }

    @Test
    void rejectedDispatchReturnsServiceUnavailableAck() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new WsRequestAdapter(dispatcher, new RejectingExecutorService()));

        assertFalse(channel.writeInbound(new TextWebSocketFrame("{\"op\":\"heartbeat\",\"seq\":7}")));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("\"op\":\"heartbeat_ack\""));
        assertTrue(response.text().contains("\"seq\":7"));
        assertTrue(response.text().contains("\"code\":503"));
    }

    @Test
    void closedAdmissionReturnsServiceUnavailableAck() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new WsRequestAdapter(
                dispatcher, new DirectExecutorService(), new ClosedAdmission()));

        assertFalse(channel.writeInbound(new TextWebSocketFrame("{\"op\":\"heartbeat\",\"seq\":7}")));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("\"op\":\"heartbeat_ack\""));
        assertTrue(response.text().contains("\"seq\":7"));
        assertTrue(response.text().contains("\"code\":503"));
    }


    @Test
    void httpOnlyOperationIsRejectedOnWebSocketBeforeDispatch() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new WsRequestAdapter(dispatcher, new RejectingExecutorService()));

        assertFalse(channel.writeInbound(new TextWebSocketFrame("{\"op\":\"user.info\",\"seq\":9,\"Authorization\":\"token\"}")));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("\"op\":\"user.info_ack\""));
        assertTrue(response.text().contains("\"seq\":9"));
        assertTrue(response.text().contains("only supports HTTP"));
    }

    private static class DirectExecutorService extends AbstractExecutorService {
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
            command.run();
        }
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

    private static class ClosedAdmission implements RequestAdmission {
        @Override
        public RequestScope enter() {
            throw new InfrastructureException(ImErrorCode.MQ_UNAVAILABLE, "closed");
        }

        @Override
        public void open() {
        }

        @Override
        public void closeAndDrain(java.time.Duration timeout) {
        }

        @Override
        public boolean isOpen() {
            return false;
        }
    }
}
