package com.im.core.delivery;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.ClusterCommandType;
import com.im.api.ClusterMessageKind;
import com.im.api.ClusterMessageTopics;
import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisClusterMessageBusTest {

    @Test
    void serializesAndDeserializesClusterCommand() throws Exception {
        RedisClusterMessageBus bus = new RedisClusterMessageBus(null, "node-a");
        ClusterMessage original = ClusterMessage.fromCommand(
                "node-a",
                ClusterCommand.kickSession("u1", PlatformID.IOS, "s1", "SAME_TERM_KICK"));

        ClusterMessage decoded = bus.deserialize(bus.serialize(original));

        assertEquals(ClusterMessageKind.CLUSTER_COMMAND, decoded.getKind());
        assertEquals(ClusterMessageTopics.CLUSTER_COMMAND, decoded.getTopic());
        assertEquals(ClusterCommandType.KICK_SESSION, decoded.getCommand().type());
        assertEquals("u1", decoded.getCommand().userId());
        assertEquals(PlatformID.IOS, decoded.getCommand().platformId());
        assertEquals("s1", decoded.getCommand().sessionId());
    }

    @Test
    void serializesExactTargetBindingIdentity() throws Exception {
        RedisClusterMessageBus bus = new RedisClusterMessageBus(null, "node-a");
        ClusterMessage original = ClusterMessage.fromMessage(
                "node-a",
                com.im.api.Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1),
                new RouteBinding("u2", "node-b", PlatformID.IOS, "s1", 123L,
                        "lease-b", "generation-b"));

        ClusterMessage decoded = bus.deserialize(bus.serialize(original));

        assertEquals("lease-b", decoded.getTargetNodeIncarnation());
        assertEquals("generation-b", decoded.getTargetGeneration());
    }
}
