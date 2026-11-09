package dev.purse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    private final Dtos.Info info;

    public InfoController(@Value("${app.version:dev}") String version,
                          @Value("${app.env:local}") String environment,
                          @Value("${spring.datasource.url:}") String url,
                          @Value("${app.admin-api-key:}") String adminKey) {
        String database = url.startsWith("jdbc:postgresql") ? "postgresql" : "in-memory (h2)";
        this.info = new Dtos.Info("purse", version, environment, database, !adminKey.isBlank(), "NGN");
    }

    @GetMapping("/api/info")
    public Dtos.Info info() {
        return info;
    }
}
