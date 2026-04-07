package com.filetransfer.cli.client;

import com.filetransfer.cli.config.ApiClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiClientTest {

    @Mock private WebClient mockWebClient;

    private ApiClientConfig config;
    private ApiClient apiClient;

    // Shared mock chain objects — rebuilt per test via helpers
    @Mock private WebClient.RequestBodyUriSpec postUriSpec;
    @Mock private WebClient.RequestBodySpec postBodySpec;
    @Mock private WebClient.RequestHeadersUriSpec<?> getUriSpec;
    @Mock private WebClient.RequestHeadersSpec<?> getHeadersSpec;
    @Mock private WebClient.RequestHeadersUriSpec<?> deleteUriSpec;
    @Mock private WebClient.RequestHeadersSpec<?> deleteHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        config = new ApiClientConfig();
        config.setAuthToken("test-jwt-token");
        apiClient = new ApiClient(config, mockWebClient, mockWebClient, mockWebClient, mockWebClient);
    }

    // ── Stub helpers ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubPostChain() {
        when(mockWebClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(anyString(), any(String[].class)))
                .thenAnswer(inv -> postBodySpec);
        when(postBodySpec.contentType(any(MediaType.class)))
                .thenAnswer(inv -> postBodySpec);
        when(postBodySpec.bodyValue(any()))
                .thenAnswer(inv -> postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @SuppressWarnings("unchecked")
    private void stubGetChain() {
        WebClient.RequestHeadersUriSpec rawGetSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec rawHeadersSpec = mock(WebClient.RequestHeadersSpec.class);

        when(mockWebClient.get()).thenReturn(rawGetSpec);
        when(rawGetSpec.uri(anyString())).thenReturn(rawHeadersSpec);
        when(rawHeadersSpec.header(anyString(), any(String[].class))).thenReturn(rawHeadersSpec);
        when(rawHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @SuppressWarnings("unchecked")
    private void stubDeleteChain() {
        WebClient.RequestHeadersUriSpec rawDeleteSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec rawHeadersSpec = mock(WebClient.RequestHeadersSpec.class);

        when(mockWebClient.delete()).thenReturn(rawDeleteSpec);
        when(rawDeleteSpec.uri(anyString())).thenReturn(rawHeadersSpec);
        when(rawHeadersSpec.header(anyString(), any(String[].class))).thenReturn(rawHeadersSpec);
        when(rawHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    // ── POST tests ──────────────────────────────────────────────────────

    @Test
    void post_successfulResponse_returnsBody() {
        stubPostChain();
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{\"status\":\"created\"}"));

        String result = apiClient.post(mockWebClient, "/api/accounts", Map.of("name", "acme"));

        assertThat(result).isEqualTo("{\"status\":\"created\"}");
    }

    @Test
    void post_webClientResponseException_returnsErrorString() {
        WebClientResponseException exception = WebClientResponseException.create(
                400, "Bad Request", null,
                "Invalid payload".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        stubPostChain();
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(exception));

        String result = apiClient.post(mockWebClient, "/api/accounts", Map.of("bad", "data"));

        assertThat(result).startsWith("ERROR 400 BAD_REQUEST");
        assertThat(result).contains("Invalid payload");
    }

    // ── GET tests ───────────────────────────────────────────────────────

    @Test
    void get_successfulResponse_returnsBody() {
        stubGetChain();
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("[{\"id\":1}]"));

        String result = apiClient.get(mockWebClient, "/api/accounts");

        assertThat(result).isEqualTo("[{\"id\":1}]");
    }

    @Test
    void get_webClientResponseException_returnsErrorString() {
        WebClientResponseException exception = WebClientResponseException.create(
                404, "Not Found", null,
                "Account not found".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        stubGetChain();
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(exception));

        String result = apiClient.get(mockWebClient, "/api/accounts/999");

        assertThat(result).startsWith("ERROR 404 NOT_FOUND");
        assertThat(result).contains("Account not found");
    }

    // ── DELETE tests ────────────────────────────────────────────────────

    @Test
    void delete_successfulResponse_returnsDeletedSuccessfully() {
        stubDeleteChain();
        when(responseSpec.bodyToMono(Void.class)).thenReturn(Mono.empty());

        String result = apiClient.delete(mockWebClient, "/api/accounts/1");

        assertThat(result).isEqualTo("Deleted successfully");
    }

    @Test
    void delete_webClientResponseException_returnsErrorString() {
        WebClientResponseException exception = WebClientResponseException.create(
                403, "Forbidden", null,
                "Access denied".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        stubDeleteChain();
        when(responseSpec.bodyToMono(Void.class)).thenReturn(Mono.error(exception));

        String result = apiClient.delete(mockWebClient, "/api/accounts/1");

        assertThat(result).startsWith("ERROR 403 FORBIDDEN");
        assertThat(result).contains("Access denied");
    }

    // ── Authorization header verification ───────────────────────────────

    @Test
    void post_attachesBearerTokenFromConfig() {
        stubPostChain();
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("ok"));

        apiClient.post(mockWebClient, "/api/test", "body");

        verify(postBodySpec).header("Authorization", "Bearer test-jwt-token");
    }
}
