package io.github.sye1321.paylab.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
public class ConcurrentDuplicateCreateConformanceEvaluator {

    private static final String ASSERTION_ID = "CONCURRENT_RACE_CONVERGENCE";
    private static final String INVARIANT_ID = "INV-04";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public ConcurrentDuplicateCreateConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events,
                                                        JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.CONCURRENT_DUPLICATE_CREATE) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);

        List<RunEvent> observedRequests = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .toList();

        List<RunEvent> resolvedEvents = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_REQUEST_RESOLVED)
                .filter(event -> event.paymentId() != null)
                .toList();

        if (resolvedEvents.isEmpty()) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "No payment request resolution has been recorded.", List.of());
        }

        if (observedRequests.size() < 2) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Concurrent duplicate creation requires at least two concurrent create requests; only "
                            + observedRequests.size() + " was observed.",
                    eventIds(resolvedEvents.toArray(RunEvent[]::new)));
        }

        // Check if different payment IDs were resolved for same key
        Set<String> resolvedPaymentIds = resolvedEvents.stream()
                .map(event -> event.paymentId().value())
                .collect(Collectors.toSet());

        if (resolvedPaymentIds.size() > 1) {
            return result(run, ConformanceVerdict.FAIL,
                    "Concurrent requests with the same idempotency key resolved different logical provider payments.",
                    eventIds(resolvedEvents.toArray(RunEvent[]::new)));
        }

        String winningPaymentId = resolvedPaymentIds.iterator().next();

        // Check committed events
        List<RunEvent> commits = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .toList();

        if (commits.size() > 1) {
            return result(run, ConformanceVerdict.FAIL,
                    "Multiple commit events occurred under a concurrent same-key race.",
                    eventIds(commits.toArray(RunEvent[]::new)));
        }

        if (commits.isEmpty()) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Payment was resolved but no commit event was recorded.",
                    eventIds(resolvedEvents.toArray(RunEvent[]::new)));
        }

        Payment payment = payments.findById(runId, resolvedEvents.get(0).paymentId()).orElse(null);
        if (payment == null || payment.status() != PaymentStatus.SUCCEEDED) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Authoritative succeeded provider state is unavailable for the winning payment.",
                    eventIds(commits.get(0)));
        }

        List<RunEvent> relevantEvents = new ArrayList<>();
        relevantEvents.addAll(observedRequests);
        relevantEvents.addAll(resolvedEvents);
        relevantEvents.addAll(commits);

        return result(run, ConformanceVerdict.PASS,
                "Concurrent duplicate requests converged to a single authoritative SUCCEEDED payment.",
                eventIds(relevantEvents.toArray(RunEvent[]::new)));
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

