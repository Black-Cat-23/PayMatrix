package io.github.sye1321.paylab.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.sye1321.paylab.conformance.ConformanceEvaluation;
import io.github.sye1321.paylab.conformance.ConformanceService;
import io.github.sye1321.paylab.conformance.JUnitXmlReportRenderer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-runs")
@Tag(name = "Test Runs", description = "Scenario configuration, evidence, conformance, and reports")
public class TestRunController {

    private final JdbcTestRunStore store;
    private final JdbcRunEventStore events;
    private final WebhookUrlValidator webhookUrls;
    private final ConformanceService conformance;
    private final JUnitXmlReportRenderer junitReports;

    public TestRunController(JdbcTestRunStore store, JdbcRunEventStore events, WebhookUrlValidator webhookUrls,
            ConformanceService conformance, JUnitXmlReportRenderer junitReports) {
        this.store = store;
        this.events = events;
        this.webhookUrls = webhookUrls;
        this.conformance = conformance;
        this.junitReports = junitReports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a test run", description = """
            Creates a test run for one PayLab scenario. Webhook scenarios (ASYNC_SUCCESS, DUPLICATE_WEBHOOK,
            WEBHOOK_RETRY, INVALID_SIGNATURE, OUT_OF_ORDER_WEBHOOK) require webhookUrl and reject
            responseDelayMillis. Timeout scenarios (TIMEOUT_BEFORE_COMMIT, TIMEOUT_AFTER_COMMIT) require
            responseDelayMillis from 1 through 30000 and reject webhookUrl. Idempotency and concurrency scenarios
            (SAME_KEY_RETRY, KEY_REUSE_DIFFERENT_PAYLOAD, CONCURRENT_DUPLICATE_CREATE) accept neither setting.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Test run created",
                    content = @Content(schema = @Schema(implementation = TestRunResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_RUN_REQUEST",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class)))
    })
    public TestRunResponse create(@Valid @RequestBody CreateTestRunRequest request) {
        String webhookUrl;
        Integer responseDelayMillis;
        switch (request.scenario()) {
            case ASYNC_SUCCESS, DUPLICATE_WEBHOOK, WEBHOOK_RETRY, INVALID_SIGNATURE, OUT_OF_ORDER_WEBHOOK -> {
                if (request.webhookUrl() == null || request.webhookUrl().isBlank()
                        || request.responseDelayMillis() != null) {
                    throw new IllegalArgumentException("Invalid webhook scenario configuration");
                }
                webhookUrl = webhookUrls.validate(request.webhookUrl());
                responseDelayMillis = null;
            }
            case TIMEOUT_BEFORE_COMMIT, TIMEOUT_AFTER_COMMIT -> {
                if (request.webhookUrl() != null || request.responseDelayMillis() == null
                        || request.responseDelayMillis() <= 0 || request.responseDelayMillis() > 30_000) {
                    throw new IllegalArgumentException("Invalid timeout scenario configuration");
                }
                webhookUrl = null;
                responseDelayMillis = request.responseDelayMillis();
            }
            case SAME_KEY_RETRY, KEY_REUSE_DIFFERENT_PAYLOAD, CONCURRENT_DUPLICATE_CREATE -> {
                if (request.webhookUrl() != null || request.responseDelayMillis() != null) {
                    throw new IllegalArgumentException("Invalid idempotency scenario configuration");
                }
                webhookUrl = null;
                responseDelayMillis = null;
            }
            default -> throw new IllegalArgumentException("Unsupported scenario");
        }
        return TestRunResponse.from(store.create(request.scenario(), webhookUrl, responseDelayMillis));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Retrieve a test run",
            description = "Retrieves the run configuration and lifecycle state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Test run",
                    content = @Content(schema = @Schema(implementation = TestRunResponse.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_RUN_REQUEST",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class))),
            @ApiResponse(responseCode = "404", description = "TEST_RUN_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class)))
    })
    public TestRunResponse get(@PathVariable UUID runId) {
        return TestRunResponse.from(store.require(new TestRunId(runId)));
    }

    @GetMapping("/{runId}/events")
    @Operation(summary = "Retrieve run evidence",
            description = "Retrieves provider-boundary evidence events for the run.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Run evidence events",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = RunEventResponse.class)))),
            @ApiResponse(responseCode = "400", description = "INVALID_RUN_REQUEST",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class))),
            @ApiResponse(responseCode = "404", description = "TEST_RUN_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class)))
    })
    public List<RunEventResponse> events(@PathVariable UUID runId) {
        TestRunId id = new TestRunId(runId);
        store.require(id);
        return events.findByRun(id).stream().map(RunEventResponse::from).toList();
    }

    @GetMapping("/{runId}/conformance")
    @Operation(summary = "Evaluate run conformance", description = "Evaluates a supported open run from currently "
            + "available provider-boundary evidence, or returns its persisted result after finalization. "
            + "Automatic evaluation supports all 10 PayLab scenarios; it does not certify merchant-internal behavior.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conformance evaluation",
                    content = @Content(schema = @Schema(implementation = ConformanceEvaluation.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_RUN_REQUEST or UNSUPPORTED_CONFORMANCE_SCENARIO",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class))),
            @ApiResponse(responseCode = "404", description = "TEST_RUN_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class)))
    })
    public ConformanceEvaluation conformance(@PathVariable UUID runId) {
        return conformance.evaluate(new TestRunId(runId));
    }

    @GetMapping(value = "/{runId}/report/junit.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Export finalized conformance as JUnit XML",
            description = "Exports the frozen conformance result of a finalized run. The run must already be finalized.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JUnit XML report",
                    content = @Content(mediaType = MediaType.APPLICATION_XML_VALUE,
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "INVALID_RUN_REQUEST",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class))),
            @ApiResponse(responseCode = "404", description = "TEST_RUN_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class))),
            @ApiResponse(responseCode = "409", description = "TEST_RUN_NOT_FINALIZED",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class)))
    })
    public String junitReport(@PathVariable UUID runId) {
        return junitReports.render(conformance.requireFinalized(new TestRunId(runId)));
    }

    @PostMapping("/{runId}/finalize")
    @Operation(summary = "Finalize run conformance", description = "Evaluates a run and persists its "
            + "immutable conformance snapshot. Repeated finalization returns the persisted result. "
            + "Automatic evaluation supports all 10 PayLab scenarios.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Immutable conformance evaluation",
                    content = @Content(schema = @Schema(implementation = ConformanceEvaluation.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_RUN_REQUEST or UNSUPPORTED_CONFORMANCE_SCENARIO",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class))),
            @ApiResponse(responseCode = "404", description = "TEST_RUN_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = TestRunHttpErrors.ApiError.class)))
    })
    public ConformanceEvaluation finalizeRun(@PathVariable UUID runId) {
        return conformance.finalizeRun(new TestRunId(runId));
    }

    public record CreateTestRunRequest(
            @NotNull ScenarioId scenario,
            @Schema(description = "Required only for webhook scenarios; rejected by all other scenarios",
                    example = "http://localhost:8081/webhooks/paylab")
            String webhookUrl,
            @Schema(description = "Required only for timeout scenarios; integer from 1 through 30000",
                    minimum = "1", maximum = "30000", example = "1000")
            Integer responseDelayMillis) {
    }

    public record TestRunResponse(UUID runId, ScenarioId scenario, int scenarioVersion,
            Instant createdAt, String webhookUrl, Integer responseDelayMillis, Instant finalizedAt) {
        static TestRunResponse from(TestRun run) {
            return new TestRunResponse(run.runId().value(), run.scenario(), run.scenarioVersion(), run.createdAt(),
                    run.webhookUrl(), run.responseDelayMillis(), run.finalizedAt());
        }
    }

    public record RunEventResponse(long eventId, UUID runId, RunEventType eventType, Instant occurredAt,
            String idempotencyKey, String requestFingerprint, UUID webhookEventId,
            Integer httpStatus, String outcome, String paymentId, Integer responseDelayMillis) {
        static RunEventResponse from(RunEvent event) {
            return new RunEventResponse(event.eventId(), event.runId().value(), event.eventType(),
                    event.occurredAt(), event.idempotencyKey(), event.requestFingerprint(),
                    event.webhookEventId(), event.httpStatus(), event.outcome(),
                    event.paymentId() == null ? null : event.paymentId().value(), event.responseDelayMillis());
        }
    }
}
