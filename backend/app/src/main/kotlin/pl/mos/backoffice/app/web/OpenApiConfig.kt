package pl.mos.backoffice.app.web

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Back Office API")
                .version("1.0.0")
                .description("SDLC control room: projects & pipelines, model evaluation. Contract source: docs/api/openapi.yaml"),
        )
        .components(
            Components()
                .addSecuritySchemes(
                    "Session",
                    SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.COOKIE).name("BO_SESSION")
                        .description("Set by the OIDC login; unsafe methods also need header X-XSRF-TOKEN = cookie XSRF-TOKEN"),
                )
                .addSecuritySchemes(
                    "PersonalToken",
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").description("Personal access token bo_pat_..."),
                ),
        )
        .addSecurityItem(SecurityRequirement().addList("Session"))
        .addSecurityItem(SecurityRequirement().addList("PersonalToken"))
}
