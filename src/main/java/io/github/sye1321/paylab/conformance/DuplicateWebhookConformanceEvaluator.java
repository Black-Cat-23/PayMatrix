package io.github.sye1321.paylab.conformance;

import java.util.ArrayList;
import java.util.List;

import io.github.sye1321.paylab.run.JdbcRunEventStore;
import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.RunEvent;
import io.github.sye1321.paylab.run.RunEventType;
import io.github.sye1321.paylab.run.ScenarioId;
import io.github.sye1321.paylab.run.TestRun;
import io.github.sye1321.paylab.run.TestRunId;
import org.springframework.stereotype.Service;

@Service
public class DuplicateWebhookConformanceEvaluator {

    private static final String ASSERTION_ID = "AT_LEAST_ONCE_DELIVERY";
    private static final String INVARIANT_ID = "INV-06";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;

    public DuplicateWebhookConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events) {
        this.runs = runs;
        this.events = events;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.DUPLICATE_WEBHOOK) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);

        List<RunEvent> acknowledgements = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.WEBHOOK_RESPONSE_OBSERVED)
                .filter(event -> event.httpStatus() != null && event.httpStatus() >= 200 && event.httpStatus() < 300)
                .toList();

        List<RunEvent> failures = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.WEBHOOK_RESPONSE_OBSERVED)
                .filter(event -> event.httpStatus() == null || event.httpStatus() >= 300)
                .toList();

        if (!failures.isEmpty()) {
            return result(run, ConformanceVerdict.FAIL,
                    "A duplicate webhook delivery attempt failed or was rejected by the merchant.",
                    eventIds(failures.toArray(RunEvent[]::new)));
        }

        if (acknowledgements.size() < 2) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Duplicate delivery requires 2 acknowledged delivery attempts; observed: "
                            + acknowledgements.size() + ".",
                    eventIds(acknowledgements.toArray(RunEvent[]::new)));
        }

        // Per INV-06 and INV-09: provider observed 2 deliveries, but cannot verify internal merchant deduplication
        return result(run, ConformanceVerdict.INCONCLUSIVE,
                "Both duplicate webhook deliveries were acknowledged with HTTP 2xx, but merchant-internal "
                        + "deduplication cannot be established from provider-boundary observations alone (INV-09).",
                eventIds(acknowledgements.toArray(RunEvent[]::new)));
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

