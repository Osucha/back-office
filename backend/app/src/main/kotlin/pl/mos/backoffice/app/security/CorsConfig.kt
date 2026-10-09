package pl.mos.backoffice.app.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * CORS for a UI served from another origin (development without the Angular proxy). The one
 * `SecurityFilterChain` lives in identity-adapters (`IdentitySecurityConfiguration`) and picks this bean up
 * through `cors(withDefaults())`. Credentials (the `BO_SESSION` cookie) are allowed only for these origins.
 */
@Configuration
class CorsConfig(private val props: SecurityProperties) {

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = props.corsAllowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("Location", "X-Correlation-Id")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }
}
