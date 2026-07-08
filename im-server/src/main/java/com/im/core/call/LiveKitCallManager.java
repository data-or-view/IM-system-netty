package com.im.core.call;

import com.im.api.ICallManager;
import com.im.api.RoomInformation;
import com.im.common.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * LiveKit 通话管理实现。
 *
 * LiveKit（https://github.com/livekit/livekit）
 * 开源 WebRTC SFU，Go 语言开发，MIT 协议，28k+ ⭐
 *
 * 本实现仅负责签发 LiveKit room token（JWT 格式）。
 * 实际媒体转发由 LiveKit Server 处理。
 *
 * LiveKit 启动（一行命令）：
 *   # docker run -d -p 7880:7880 -p 7881:7881
 *   #   -e LIVEKIT_KEYS="devkey: <API_SECRET>"
 *   #   livekit/livekit-server --node-ip=<公网IP>
 *
 * Token 格式（标准 LiveKit JWT）：
 *   header:  {"alg":"HS256","typ":"JWT"}
 *   payload: {
 *     "iss": "<API_KEY>",
 *     "sub": "<userId>",
 *     "video": {"room":"<roomId>","roomJoin":true}
 *   }
 *   signature: HMAC-SHA256( header + "." + payload )
 */
public class LiveKitCallManager implements ICallManager {

    private static final Logger log = LoggerFactory.getLogger(LiveKitCallManager.class);

    private final String apiKey;
    private final String apiSecret;
    private final String sfuEndpoint;

    public LiveKitCallManager(String apiKey, String apiSecret, String sfuEndpoint) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.sfuEndpoint = sfuEndpoint;
        log.info("LiveKitCallManager initialized: endpoint={}, apiKeyConfigured={}",
                sfuEndpoint, hasText(apiKey));
    }

    @Override
    public RoomInformation createRoom(String callerId, String calleeId, String roomId) {
        if (roomId == null || roomId.isBlank()) {
            roomId = IdGenerator.roomId();
        }

        String callerToken = signLiveKitToken(callerId, roomId);
        // Group calls do not have a fixed callee at room creation time; members
        // receive their own token later through issueToken when they join.
        String calleeToken = hasText(calleeId) ? signLiveKitToken(calleeId, roomId) : null;

        log.info("Room created: roomId={}, caller={}, callee={}",
                roomId, callerId, calleeId);

        return new RoomInformation(roomId, sfuEndpoint, callerToken, calleeToken);
    }

    @Override
    public String issueToken(String userId, String roomId) {
        return signLiveKitToken(userId, roomId);
    }

    @Override
    public String getProviderName() {
        return "LiveKit (https://github.com/livekit/livekit)";
    }

    @Override
    public String getSfuEndpoint() {
        return sfuEndpoint;
    }

    // ========== LiveKit JWT 签名 ==========

    /**
     * 签发 LiveKit room token。
     *
     * LiveKit 的 token 是标准 JWT（HMAC-SHA256），
     * payload 中携带 video 权限字段。
     */
    private String signLiveKitToken(String userId, String roomId) {
        if (!hasText(apiKey)) {
            throw new IllegalStateException("LiveKit apiKey is required");
        }
        if (!hasText(apiSecret)) {
            throw new IllegalStateException("LiveKit apiSecret is required");
        }
        if (!hasText(userId)) {
            throw new IllegalArgumentException("LiveKit token subject userId is required");
        }
        if (!hasText(roomId)) {
            throw new IllegalArgumentException("LiveKit token roomId is required");
        }
        try {
            // ── header ──
            String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String headerEncoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(header.getBytes(StandardCharsets.UTF_8));

            // ── payload ──
            long now = System.currentTimeMillis() / 1000;
            String payload = "{\"iss\":\"" + escapeJson(apiKey)
                    + "\",\"sub\":\"" + escapeJson(userId)
                    + "\",\"exp\":" + (now + 3600) // 1 hour TTL
                    + ",\"nbf\":" + now
                    + ",\"video\":{\"room\":\"" + escapeJson(roomId)
                    + "\",\"roomJoin\":true,\"canPublish\":true,\"canSubscribe\":true}}";
            String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

            // ── signature ──
            String signingInput = headerEncoded + "." + payloadEncoded;
            byte[] signature = hmacSha256(signingInput, apiSecret);
            String signatureEncoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signature);

            return headerEncoded + "." + payloadEncoded + "." + signatureEncoded;

        } catch (Exception e) {
            log.error("Failed to sign LiveKit token", e);
            throw new RuntimeException("Failed to sign LiveKit token", e);
        }
    }

    private static byte[] hmacSha256(String data, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /** JSON 字符串转义（防止 userId/roomId 含双引号破坏 JSON） */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
