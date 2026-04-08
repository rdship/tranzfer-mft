package com.filetransfer.shared.matching;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Leaf condition node in the match criteria tree.
 * Evaluates a single field against a value or set of values.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchCondition(
        String field,
        ConditionOp op,
        Object value,
        List<Object> values,
        String key
) implements MatchCriteria {

    public enum ConditionOp {
        EQ, IN, REGEX, GLOB, CONTAINS, STARTS_WITH, ENDS_WITH,
        GT, LT, GTE, LTE, BETWEEN,
        CIDR, KEY_EQ
    }
}
