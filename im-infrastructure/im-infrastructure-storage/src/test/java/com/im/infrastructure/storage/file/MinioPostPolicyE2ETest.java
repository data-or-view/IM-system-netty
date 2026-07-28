package com.im.infrastructure.storage.file;

import com.im.api.FileObjectStat;
import com.im.api.PresignedPostPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MinIO boundary check. It is excluded by the default Surefire E2E pattern.
 */
class MinioPostPolicyE2ETest {

    @Test
    void exactSizePostFormStoresTheExpectedObjectMetadata() throws Exception {
        String endpoint = requiredEnvironment("IM_MINIO_ENDPOINT");
        String accessKey = requiredEnvironment("IM_MINIO_ACCESS_KEY");
        String secretKey = requiredEnvironment("IM_MINIO_SECRET_KEY");
        String bucket = requiredEnvironment("IM_MINIO_BUCKET");
        MinioFileStorageService storage = new MinioFileStorageService(endpoint, accessKey, secretKey);
        String objectKey = "uploads/task4-" + UUID.randomUUID() + ".txt";
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        PresignedPostPolicy policy = storage.presignPostPolicy(bucket, objectKey, "text/plain", content.length, 60);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    postForm(policy, content), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                    () -> "POST upload failed: HTTP " + response.statusCode() + " " + response.body());

            FileObjectStat stat = storage.statObject(bucket, objectKey);
            assertEquals(content.length, stat.sizeBytes());
            assertEquals("text/plain", stat.contentType());
        } finally {
            storage.delete(bucket, objectKey);
        }
    }

    private static HttpRequest postForm(PresignedPostPolicy policy, byte[] content) {
        String boundary = "im-task4-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : policy.formFields().entrySet()) {
            write(body, "--" + boundary + "\r\n");
            write(body, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
            write(body, field.getValue() + "\r\n");
        }
        write(body, "--" + boundary + "\r\n");
        write(body, "Content-Disposition: form-data; name=\"" + policy.fileField() + "\"; filename=\"upload.txt\"\r\n");
        write(body, "Content-Type: text/plain\r\n\r\n");
        body.writeBytes(content);
        write(body, "\r\n--" + boundary + "--\r\n");
        return HttpRequest.newBuilder(URI.create(policy.uploadUrl()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private static void write(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                () -> "requires " + name + " for a real MinIO upload");
        return value;
    }
}
