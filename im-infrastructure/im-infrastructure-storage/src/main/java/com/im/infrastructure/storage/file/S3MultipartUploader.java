package com.im.infrastructure.storage.file;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * S3 REST API 分片上传工具（AWS Signature V4）。
 *
 * <p>MinIO Java SDK 8.5.x 不暴露分片上传操作，故直接调用 S3 REST API。</p>
 */
public class S3MultipartUploader {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private final String endpoint;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient httpClient;

    public S3MultipartUploader(String endpoint, String region, String accessKey, String secretKey) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /**
     * 初始化分片上传。
     *
     * @return uploadId
     */
    public String initiateMultipartUpload(String bucket, String objectId) throws Exception {
        String url = endpoint + "/" + bucket + "/" + objectId + "?uploads";
        String now = nowDatetime();
        String today = nowDate();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", hostHeader());
        headers.put("x-amz-date", now);

        String auth = sign("POST", "/" + bucket + "/" + objectId, "uploads=", headers, today, now);
        headers.put("Authorization", auth);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("host", hostHeader())
                .header("x-amz-date", now)
                .header("Authorization", auth)
                .method("POST", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("InitiateMultipartUpload failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        return extractXmlTag(resp.body(), "UploadId");
    }

    /**
     * 上传分片。
     *
     * @return ETag
     */
    public String uploadPart(String bucket, String objectId, String uploadId,
                             int partNumber, byte[] data) throws Exception {
        String query = "partNumber=" + partNumber + "&uploadId=" + uploadId;
        String url = endpoint + "/" + bucket + "/" + objectId + "?" + query;
        String now = nowDatetime();
        String today = nowDate();

        String auth = sign("PUT", "/" + bucket + "/" + objectId, query, Map.of(
                "host", hostHeader(),
                "x-amz-date", now,
                "content-length", String.valueOf(data.length)
        ), today, now);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("host", hostHeader())
                .header("x-amz-date", now)
                .header("Authorization", auth)
                .header("content-length", String.valueOf(data.length))
                .method("PUT", HttpRequest.BodyPublishers.ofByteArray(data))
                .build();

        HttpResponse<Void> resp = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 200) {
            String body = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).method("GET", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            throw new RuntimeException("UploadPart failed: HTTP " + resp.statusCode());
        }
        return resp.headers().firstValue("ETag")
                .orElseThrow(() -> new RuntimeException("No ETag in UploadPart response"));
    }

    /**
     * 完成分片上传。
     */
    public void completeMultipartUpload(String bucket, String objectId, String uploadId,
                                        List<PartInfo> parts) throws Exception {
        String query = "uploadId=" + uploadId;
        String url = endpoint + "/" + bucket + "/" + objectId + "?" + query;

        String xml = buildCompleteXml(parts);
        String now = nowDatetime();
        String today = nowDate();

        String auth = sign("POST", "/" + bucket + "/" + objectId, query, Map.of(
                "host", hostHeader(),
                "x-amz-date", now,
                "content-type", "application/xml"
        ), today, now);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("host", hostHeader())
                .header("x-amz-date", now)
                .header("Authorization", auth)
                .header("content-type", "application/xml")
                .method("POST", HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("CompleteMultipartUpload failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
    }

    /**
     * 中止分片上传。
     */
    public void abortMultipartUpload(String bucket, String objectId, String uploadId) throws Exception {
        String query = "uploadId=" + uploadId;
        String url = endpoint + "/" + bucket + "/" + objectId + "?" + query;
        String now = nowDatetime();
        String today = nowDate();

        String auth = sign("DELETE", "/" + bucket + "/" + objectId, query, Map.of(
                "host", hostHeader(),
                "x-amz-date", now
        ), today, now);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("host", hostHeader())
                .header("x-amz-date", now)
                .header("Authorization", auth)
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code != 204 && code != 200) {
            throw new RuntimeException("AbortMultipartUpload failed: HTTP " + code + " " + resp.body());
        }
    }

    // ── AWS Signature V4 ──

    private String sign(String method, String canonicalUri, String query,
                        Map<String, String> headers, String today, String now) {
        String signedHeaders = String.join(";", headers.keySet());

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + query + "\n"
                + headers.entrySet().stream()
                .map(e -> e.getKey().toLowerCase() + ":" + e.getValue().trim() + "\n")
                .collect(Collectors.joining())
                + "\n"
                + signedHeaders + "\n"
                + UNSIGNED_PAYLOAD;

        String credentialScope = today + "/" + region + "/" + "s3" + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + now + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] signingKey = getSigningKey(secretKey, today, region, "s3");
        String signature = hex(hmacSha256(signingKey, stringToSign));

        return ALGORITHM + " Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private byte[] getSigningKey(String secretKey, String date, String region, String service) {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ── 工具 ──

    private String hostHeader() {
        URI uri = URI.create(endpoint);
        int port = uri.getPort();
        return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
    }

    private String nowDatetime() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DATETIME_FMT);
    }

    private String nowDate() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DATE_FMT);
    }

    private static String extractXmlTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) throw new RuntimeException("XML tag <" + tag + "> not found: " + xml);
        start += open.length();
        int end = xml.indexOf(close, start);
        if (end < 0) throw new RuntimeException("XML tag </" + tag + "> not found: " + xml);
        return xml.substring(start, end);
    }

    private static String buildCompleteXml(List<PartInfo> parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<CompleteMultipartUpload xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        for (PartInfo p : parts) {
            sb.append("<Part><PartNumber>").append(p.partNumber()).append("</PartNumber>");
            sb.append("<ETag>").append(p.etag()).append("</ETag></Part>");
        }
        sb.append("</CompleteMultipartUpload>");
        return sb.toString();
    }

    /**
     * 分片信息。
     */
    public record PartInfo(int partNumber, String etag) {}
}
