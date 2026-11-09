package dev.purse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** Starts the real web server on a random port and calls it over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.admin-api-key=test-admin-key")
class ApiTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("field %s in %s", name, json).isTrue();
        return m.group(1);
    }

    @Test
    void healthProbesAreUp() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void prometheusMetricsAreExposed() throws Exception {
        post("/api/wallets", "{\"name\":\"Metric\",\"email\":\"m@example.com\"}");
        HttpResponse<String> r = get("/actuator/prometheus");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("purse_wallets_opened_total");
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        HttpResponse<String> created = post("/api/wallets", "{\"name\":\"Ngozi\",\"email\":\"ngozi@example.com\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = field(created.body(), "id");
        String token = field(created.body(), "token");

        HttpResponse<String> other = post("/api/wallets", "{\"name\":\"Tunde\",\"email\":\"tunde@example.com\"}");
        String otherId = field(other.body(), "id");

        assertThat(get("/api/wallets/" + id).statusCode()).isEqualTo(401);
        assertThat(get("/api/wallets/" + id, "X-Wallet-Token", token).statusCode()).isEqualTo(200);

        HttpResponse<String> dep = post("/api/wallets/" + id + "/deposits", "{\"amount\":100000}",
                "X-Wallet-Token", token);
        assertThat(dep.statusCode()).isEqualTo(201);

        String transfer = "{\"toWallet\":\"" + otherId + "\",\"amount\":40000,\"note\":\"Rent share\"}";
        HttpResponse<String> t1 = post("/api/wallets/" + id + "/transfers", transfer,
                "X-Wallet-Token", token, "Idempotency-Key", "http-test-key-1");
        HttpResponse<String> t2 = post("/api/wallets/" + id + "/transfers", transfer,
                "X-Wallet-Token", token, "Idempotency-Key", "http-test-key-1");
        assertThat(t1.statusCode()).isEqualTo(201);
        assertThat(t2.statusCode()).isEqualTo(200);
        assertThat(t2.headers().firstValue("Idempotent-Replay")).hasValue("true");

        HttpResponse<String> broke = post("/api/wallets/" + id + "/transfers",
                "{\"toWallet\":\"" + otherId + "\",\"amount\":999999}", "X-Wallet-Token", token);
        assertThat(broke.statusCode()).isEqualTo(409);

        HttpResponse<String> wallet = get("/api/wallets/" + id, "X-Wallet-Token", token);
        assertThat(wallet.body()).contains("\"balance\":60000");
    }

    @Test
    void adminSummaryNeedsTheKey() throws Exception {
        assertThat(get("/api/admin/summary").statusCode()).isEqualTo(401);
        assertThat(get("/api/admin/summary", "X-Admin-Key", "wrong").statusCode()).isEqualTo(401);
        HttpResponse<String> ok = get("/api/admin/summary", "X-Admin-Key", "test-admin-key");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"booksBalance\":true");
    }

    @Test
    void adminListsAreProtectedAndNeverLeakTokens() throws Exception {
        post("/api/wallets", "{\"name\":\"Listed\",\"email\":\"listed@example.com\"}");
        assertThat(get("/api/admin/wallets").statusCode()).isEqualTo(401);
        assertThat(get("/api/admin/transactions").statusCode()).isEqualTo(401);

        HttpResponse<String> list = get("/api/admin/wallets", "X-Admin-Key", "test-admin-key");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("listed@example.com");
        assertThat(list.body()).doesNotContain("token");

        assertThat(get("/api/admin/transactions", "X-Admin-Key", "test-admin-key").statusCode()).isEqualTo(200);
        assertThat(get("/admin.html").statusCode()).isEqualTo(200);
    }

    @Test
    void badJsonIsA400NotA500() throws Exception {
        assertThat(post("/api/wallets", "{not json").statusCode()).isEqualTo(400);
    }

    @Test
    void infoAndWebsiteAreServed() throws Exception {
        HttpResponse<String> info = get("/api/info");
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.body()).contains("\"adminEnabled\":true");
        assertThat(get("/").statusCode()).isEqualTo(200);
    }
}
