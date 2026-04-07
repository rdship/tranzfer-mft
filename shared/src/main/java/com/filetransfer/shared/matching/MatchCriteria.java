package com.filetransfer.shared.matching;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Root type for composable file flow match criteria.
 * Stored as JSONB on file_flows table.
 * Jackson uses property-based deduction to distinguish groups from conditions.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(MatchGroup.class),
        @JsonSubTypes.Type(MatchCondition.class)
})
public sealed interface MatchCriteria permits MatchGroup, MatchCondition {
}
