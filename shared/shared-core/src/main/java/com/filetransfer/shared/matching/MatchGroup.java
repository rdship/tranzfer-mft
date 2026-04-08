package com.filetransfer.shared.matching;

import java.util.List;

/**
 * AND/OR/NOT group node in the match criteria tree.
 * NOT must have exactly one child condition.
 */
public record MatchGroup(
        GroupOperator operator,
        List<MatchCriteria> conditions
) implements MatchCriteria {

    public enum GroupOperator { AND, OR, NOT }
}
