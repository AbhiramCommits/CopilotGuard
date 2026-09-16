package com.copilotguard.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Liveness and readiness")
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    @Operation(
            summary = "Service health",
            description = "Actuator-backed health including database connectivity.")
    @ApiResponse(responseCode = "200", description = "UP")
    @ApiResponse(responseCode = "503", description = "DOWN")
    public ResponseEntity<HealthComponent> health() {
        HealthComponent health = healthEndpoint.health();
        HttpStatus status =
                Status.UP.equals(health.getStatus())
                        ? HttpStatus.OK
                        : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(health);
    }
}
