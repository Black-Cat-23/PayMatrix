package io.github.sye1321.paylab.conformance;

import java.util.ArrayList;
import java.util.List;

import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentStatus;
import io.github.sye1321.paylab.run.JdbcRunEventStore;
import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.RunEvent;
import io.github.sye1321.paylab.run.RunEventType;
import io.github.sye1321.paylab.run.ScenarioId;
import io.github.sye1321.paylab.run.TestRun;
import io.github.sye1321.paylab.run.TestRunId;
import org.springframework.stereotype.Service;

@Service
public class TimeoutBeforeCommitConformanceEvaluator {

    private static final String ASSERTION_ID = "PRE_COMMIT_RECOVERY";
    private static final String INVARIANT_ID = "INV-02";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public TimeoutBeforeCommitConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events,
                                                  JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.TIMEOUT_BEFORE_COMMIT) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);

        RunEvent injection = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PRE_COMMIT_TIMEOUT_INJECTED)
                .findFirst()
                .orElse(null);

        if (injection == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "No pre-commit timeout fault injection was observed.", List.of());
        }

        // Verify no commit happened before or at the injection
        boolean invalidPriorCommit = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .anyMatch(event -> event.eventId() < injection.eventId());

        if (invalidPriorCommit) {
            return result(run, ConformanceVerdict.FAIL,
                    "A payment was committed prior to the pre-commit timeout injection.",
                    eventIds(injection));
        }

        // Look for subsequent retry request
        RunEvent retryRequest = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() > injection.eventId())
                .filter(event -> injection.idempotencyKey() != null && injection.idempotencyKey().equals(event.idempotencyKey()))
                .findFirst()
                .orElse(null);

        if (retryRequest == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Pre-commit timeout was injected, but no subsequent retry with the same idempotency key has been observed.",
                    eventIds(injection));
        }

        RunEvent retryResolution = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_REQUEST_RESOLVED)
                .filter(event -> event.eventId() > retryRequest.eventId())
                .filter(event -> retryRequest.idempotencyKey().equals(event.idempotencyKey()))
                .findFirst()
                .orElse(null);

        RunEvent retryCommit = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .filter(event -> event.eventId() > retryRequest.eventId())
                .findFirst()
                .orElse(null);

        if (retryResolution == null || retryCommit == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Retry request was observed, but provider resolution or commit evidence is incomplete.",
                    eventIds(injection, retryRequest));
        }

        Payment payment = payments.findById(runId, retryResolution.paymentId()).orElse(null);
        if (payment == null || payment.status() != PaymentStatus.SUCCEEDED) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Authoritative succeeded provider state is unavailable for the retried payment.",
                    eventIds(injection, retryRequest, retryResolution, retryCommit));
        }

        return result(run, ConformanceVerdict.PASS,
                "Safe retry executed after pre-commit timeout; resolved to one authoritative SUCCEEDED payment.",
                eventIds(injection, retryRequest, retryResolution, retryCommit));
    }

    private static ConformanceEvaluation result(TestRun run, ConformanceVerdict verdict, String explanation,
                                                List<Long> evidenceEventIds) {
        AssertionEvaluation assertion = new AssertionEvaluation(
                ASSERTION_ID, INVARIANT_ID, verdict, explanation, evidenceEventIds);
        return new ConformanceEvaluation(run.runId().value(), run.scenario(), run.scenarioVersion(), verdict,
                List.of(assertion));
    }

    private static List<Long> eventIds(RunEvent... events) {
        List<Long> ids = new ArrayList<>();
        for (RunEvent event : events) {
            if (event != null && !ids.contains(event.eventId())) {
                ids.add(event.eventId());
            }
        }
        ids.sort(Long::compareTo);
        return List.copyOf(ids);
    }
}

