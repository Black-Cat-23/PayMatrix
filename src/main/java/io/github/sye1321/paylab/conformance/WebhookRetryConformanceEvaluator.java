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
public class WebhookRetryConformanceEvaluator {

    private static final String ASSERTION_ID = "DELIVERY_FAILURE_RECOVERY";
    private static final String INVARIANT_ID = "INV-08";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public WebhookRetryConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events,
                                           JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.WEBHOOK_RETRY) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);

        RunEvent commit = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .findFirst()
                .orElse(null);

        if (commit == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Provider payment commit evidence has not been recorded.", List.of());
        }

        Payment payment = payments.findById(runId, commit.paymentId()).orElse(null);
        if (payment == null || payment.status() != PaymentStatus.SUCCEEDED) {
            return result(run, ConformanceVerdict.FAIL,
                    "Provider payment did not achieve SUCCEEDED status.", eventIds(commit));
        }

        List<RunEvent> responses = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.WEBHOOK_RESPONSE_OBSERVED)
                .toList();

        if (responses.isEmpty()) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Webhook callback delivery has not yet produced observed responses.",
                    eventIds(commit));
        }

        RunEvent firstFailure = responses.stream()
                .filter(event -> event.httpStatus() == null || event.httpStatus() >= 300)
                .findFirst()
                .orElse(null);

        RunEvent laterSuccess = responses.stream()
                .filter(event -> event.httpStatus() != null && event.httpStatus() >= 200 && event.httpStatus() < 300)
                .findFirst()
                .orElse(null);

        if (firstFailure != null && laterSuccess != null && laterSuccess.eventId() > firstFailure.eventId()) {
            return result(run, ConformanceVerdict.PASS,
                    "Callback transport failure was recovered through retry and acknowledged with HTTP "
                            + laterSuccess.httpStatus() + " without altering provider payment state.",
                    eventIds(commit, firstFailure, laterSuccess));
        }

        if (firstFailure != null && responses.size() > 1 && laterSuccess == null) {
            return result(run, ConformanceVerdict.FAIL,
                    "Callback retry was attempted but remained unacknowledged or failed.",
                    eventIds(commit, responses.get(0), responses.get(responses.size() - 1)));
        }

        return result(run, ConformanceVerdict.INCONCLUSIVE,
                "Webhook retry scenario is in progress; waiting for retry delivery attempt.",
                eventIds(commit, responses.get(0)));
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

