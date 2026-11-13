package io.github.sye1321.paylab.conformance;

import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.TestRun;
import io.github.sye1321.paylab.run.TestRunId;
import io.github.sye1321.paylab.run.TestRunNotFinalizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConformanceService {

    private final JdbcTestRunStore runs;
    private final JdbcFinalizedConformanceStore finalizedResults;

    private final TimeoutAfterCommitConformanceEvaluator timeoutAfterCommit;
    private final SameKeyRetryConformanceEvaluator sameKeyRetry;
    private final KeyReuseDifferentPayloadConformanceEvaluator keyReuseDifferentPayload;
    private final ConcurrentDuplicateCreateConformanceEvaluator concurrentDuplicateCreate;
    private final TimeoutBeforeCommitConformanceEvaluator timeoutBeforeCommit;
    private final InvalidSignatureConformanceEvaluator invalidSignature;
    private final AsyncSuccessConformanceEvaluator asyncSuccess;
    private final DuplicateWebhookConformanceEvaluator duplicateWebhook;
    private final OutOfOrderWebhookConformanceEvaluator outOfOrderWebhook;
    private final WebhookRetryConformanceEvaluator webhookRetry;

    public ConformanceService(
            JdbcTestRunStore runs,
            JdbcFinalizedConformanceStore finalizedResults,
            TimeoutAfterCommitConformanceEvaluator timeoutAfterCommit,
            SameKeyRetryConformanceEvaluator sameKeyRetry,
            KeyReuseDifferentPayloadConformanceEvaluator keyReuseDifferentPayload,
            ConcurrentDuplicateCreateConformanceEvaluator concurrentDuplicateCreate,
            TimeoutBeforeCommitConformanceEvaluator timeoutBeforeCommit,
            InvalidSignatureConformanceEvaluator invalidSignature,
            AsyncSuccessConformanceEvaluator asyncSuccess,
            DuplicateWebhookConformanceEvaluator duplicateWebhook,
            OutOfOrderWebhookConformanceEvaluator outOfOrderWebhook,
            WebhookRetryConformanceEvaluator webhookRetry) {
        this.runs = runs;
        this.finalizedResults = finalizedResults;
        this.timeoutAfterCommit = timeoutAfterCommit;
        this.sameKeyRetry = sameKeyRetry;
        this.keyReuseDifferentPayload = keyReuseDifferentPayload;
        this.concurrentDuplicateCreate = concurrentDuplicateCreate;
        this.timeoutBeforeCommit = timeoutBeforeCommit;
        this.invalidSignature = invalidSignature;
        this.asyncSuccess = asyncSuccess;
        this.duplicateWebhook = duplicateWebhook;
        this.outOfOrderWebhook = outOfOrderWebhook;
        this.webhookRetry = webhookRetry;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.finalizedAt() != null) {
            return requireFinalizedResult(run);
        }
        return evaluateLive(run);
    }

    public ConformanceEvaluation requireFinalized(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.finalizedAt() == null) {
            throw new TestRunNotFinalizedException(runId);
        }
        return requireFinalizedResult(run);
    }

    @Transactional
    public ConformanceEvaluation finalizeRun(TestRunId runId) {
        TestRun run = runs.requireForUpdate(runId);
        if (run.finalizedAt() != null) {
            return requireFinalizedResult(run);
        }
        ConformanceEvaluation evaluation = evaluateLive(run);
        finalizedResults.save(evaluation);
        runs.markFinalized(runId);
        return evaluation;
    }

    private ConformanceEvaluation evaluateLive(TestRun run) {
        return switch (run.scenario()) {
            case TIMEOUT_AFTER_COMMIT -> timeoutAfterCommit.evaluate(run.runId());
            case SAME_KEY_RETRY -> sameKeyRetry.evaluate(run.runId());
            case KEY_REUSE_DIFFERENT_PAYLOAD -> keyReuseDifferentPayload.evaluate(run.runId());
            case CONCURRENT_DUPLICATE_CREATE -> concurrentDuplicateCreate.evaluate(run.runId());
            case TIMEOUT_BEFORE_COMMIT -> timeoutBeforeCommit.evaluate(run.runId());
            case INVALID_SIGNATURE -> invalidSignature.evaluate(run.runId());
            case ASYNC_SUCCESS -> asyncSuccess.evaluate(run.runId());
            case DUPLICATE_WEBHOOK -> duplicateWebhook.evaluate(run.runId());
            case OUT_OF_ORDER_WEBHOOK -> outOfOrderWebhook.evaluate(run.runId());
            case WEBHOOK_RETRY -> webhookRetry.evaluate(run.runId());
        };
    }

    private ConformanceEvaluation requireFinalizedResult(TestRun run) {
        return finalizedResults.find(run)
                .orElseThrow(() -> new IllegalStateException("Finalized conformance result is missing"));
    }
}
