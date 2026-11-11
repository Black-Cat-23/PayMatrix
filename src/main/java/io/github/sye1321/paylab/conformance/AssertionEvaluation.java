package io.github.sye1321.paylab.conformance;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record AssertionEvaluation(String assertionId, String invariantId, ConformanceVerdict verdict,
        String explanation, List<Long> evidenceEventIds) {

    @Schema(description = "Provider-boundary event identifiers supporting this assertion")
    public List<Long> evidenceEventIds() {
        return evidenceEventIds;
    }
}
