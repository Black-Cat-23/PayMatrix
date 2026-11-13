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
public class AsyncSuccessConformanceEvaluator {

    private static final String ASSERTION_ID = "ASYNC_PAYMENT_COMPLETION";
    private static final String INVARIANT_ID = "INV-01";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public AsyncSuccessConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events,
                                           JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.ASYNC_SUCCESS) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);

        RunEvent commit = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .findFirst()
                .orElse(null);

        if (commit == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Payment commit evidence is not yet recorded.", List.of());
        }

        Payment payment = payments.findById(runId, commit.paymentId()).orElse(null);
        if (payment == null || payment.status() != PaymentStatus.SUCCEEDED) {
            return result(run, ConformanceVerdict.FAIL,
                    "Provider payment did not achieve SUCCEEDED status.", eventIds(commit));
        }

        RunEvent delivered = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.WEBHOOK_DELIVERED)
                .findFirst()
                .orElse(null);

        RunEvent responseObserved = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.WEBHOOK_RESPONSE_OBSERVED)
                .findFirst()
                .orElse(null);

        if (delivered != null) {
            return result(run, ConformanceVerdict.PASS,
                    "Normal asynchronous payment succeeded and callback was acknowledged with HTTP "
                            + delivered.httpStatus() + ".",
                    eventIds(commit, delivered));
        }

        if (responseObserved != null && (responseObserved.httpStatus() == null || responseObserved.httpStatus() >= 300)) {
            return result(run, ConformanceVerdict.FAIL,
                    "Payment succeeded, but webhook callback failed with outcome: " + responseObserved.outcome(),
                    eventIds(commit, responseObserved));
        }

        return result(run, ConformanceVerdict.INCONCLUSIVE,
                "Payment succeeded, but signed webhook delivery has not yet completed.",
                eventIds(commit));
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

