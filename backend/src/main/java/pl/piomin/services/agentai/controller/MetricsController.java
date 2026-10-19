package pl.piomin.services.agentai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.agentai.dto.AgentMetricsResponse;
import pl.piomin.services.agentai.service.MetricsService;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    public AgentMetricsResponse metrics() {
        return metricsService.metrics();
    }
}
