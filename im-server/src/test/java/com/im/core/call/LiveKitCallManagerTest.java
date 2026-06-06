package com.im.core.call;

import com.im.api.RoomInformation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveKitCallManagerTest {

    @Test
    void createRoomSupportsGroupCallWithoutCallee() {
        LiveKitCallManager manager = new LiveKitCallManager(
                "devkey", "im-system-livekit-secret-2024", "ws://localhost:7880");

        RoomInformation room = manager.createRoom("caller-1", null, "room-group-1");

        assertEquals("room-group-1", room.getRoomId());
        assertNotNull(room.getCallerToken());
        assertNull(room.getCalleeToken());
    }

    @Test
    void createRoomSignsCalleeTokenForSingleCall() {
        LiveKitCallManager manager = new LiveKitCallManager(
                "devkey", "im-system-livekit-secret-2024", "ws://localhost:7880");

        RoomInformation room = manager.createRoom("caller-1", "callee-1", "room-single-1");

        assertNotNull(room.getCallerToken());
        assertNotNull(room.getCalleeToken());
    }
}
