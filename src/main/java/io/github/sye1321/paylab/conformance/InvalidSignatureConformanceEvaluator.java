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
public class InvalidSignatureConformanceEvaluator {

    private static final String ASSERTION_ID = "WEBHOOK_AUTHENTICITY_VERIFICATION";
    private static final String INVARIANT_ID = "INV-05";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;

    public InvalidSignatureConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events) {
        this.runs = runs;
        this.events = events;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.INVALID_SIGNATURE) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);

        RunEvent scheduled = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.WEBHOOK_SCHEDULED)
                .findFirst()
                .orElse(null);

        RunEvent responseObserved = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.WEBHOOK_RESPONSE_OBSERVED)
                .findFirst()
                .orElse(null);

        if (responseObserved == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Invalid signature webhook was scheduled, but no response from merchant callback has been observed.",
                    eventIds(scheduled));
        }

        Integer statusCode = responseObserved.httpStatus();
        if (statusCode != null && statusCode >= 200 && statusCode < 300) {
            return result(run, ConformanceVerdict.FAIL,
                    "Merchant acknowledged callback carrying an invalid signature with HTTP " + statusCode
                            + "; unauthenticated callbacks must be rejected.",
                    eventIds(scheduled, responseObserved));
        }

        return result(run, ConformanceVerdict.PASS,
                "Merchant correctly rejected untrusted callback evidence with HTTP "
                        + (statusCode != null ? statusCode : "transport error") + ".",
                eventIds(scheduled, responseObserved));
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

