package pl.ksef.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // TODO: allowedOrigins jest hardcoded na localhost:3000 (środowisko deweloperskie).
        //       W produkcji spowoduje to odrzucenie wszystkich żądań z właściwej domeny.
        //       Przenieść origin do zmiennej środowiskowej (np. CORS_ALLOWED_ORIGINS)
        //       i wczytać przez @Value("${cors.allowed-origins:http://localhost:3000}").
        //       Przykład w application.yml: cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
