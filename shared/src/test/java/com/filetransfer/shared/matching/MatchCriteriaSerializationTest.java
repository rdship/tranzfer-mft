package com.filetransfer.shared.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchCriteriaSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void serialize_andGroup_roundTrips() throws Exception {
        MatchCriteria original = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null),
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null)
        ));

        String json = mapper.writeValueAsString(original);
        MatchCriteria deserialized = mapper.readValue(json, MatchCriteria.class);

        assertInstanceOf(MatchGroup.class, deserialized);
        MatchGroup group = (MatchGroup) deserialized;
        assertEquals(MatchGroup.GroupOperator.AND, group.operator());
        assertEquals(2, group.conditions().size());
    }

    @Test
    void serialize_nestedOrInAnd_roundTrips() throws Exception {
        MatchCriteria original = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.IN, null, List.of("SFTP", "AS2"), null),
                new MatchGroup(MatchGroup.GroupOperator.OR, List.of(
                        new MatchCondition("ediType", MatchCondition.ConditionOp.EQ, "850", null, null),
                        new MatchCondition("ediType", MatchCondition.ConditionOp.EQ, "855", null, null)
                ))
        ));

        String json = mapper.writeValueAsString(original);
        MatchCriteria deserialized = mapper.readValue(json, MatchCriteria.class);

        assertInstanceOf(MatchGroup.class, deserialized);
        MatchGroup group = (MatchGroup) deserialized;
        assertEquals(2, group.conditions().size());
        assertInstanceOf(MatchGroup.class, group.conditions().get(1));
    }

    @Test
    void serialize_singleCondition_roundTrips() throws Exception {
        MatchCriteria original = new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.csv", null, null);

        String json = mapper.writeValueAsString(original);
        MatchCriteria deserialized = mapper.readValue(json, MatchCriteria.class);

        assertInstanceOf(MatchCondition.class, deserialized);
        MatchCondition cond = (MatchCondition) deserialized;
        assertEquals("filename", cond.field());
        assertEquals(MatchCondition.ConditionOp.GLOB, cond.op());
        assertEquals("*.csv", cond.value());
    }

    @Test
    void deserialize_fromJsonString_correctTypes() throws Exception {
        String json = """
                {
                  "operator": "AND",
                  "conditions": [
                    {"field": "protocol", "op": "EQ", "value": "SFTP"},
                    {"field": "filename", "op": "GLOB", "value": "*.edi"}
                  ]
                }
                """;

        MatchCriteria criteria = mapper.readValue(json, MatchCriteria.class);
        assertInstanceOf(MatchGroup.class, criteria);
        MatchGroup group = (MatchGroup) criteria;
        assertEquals(MatchGroup.GroupOperator.AND, group.operator());
        assertEquals(2, group.conditions().size());
        assertInstanceOf(MatchCondition.class, group.conditions().get(0));
        assertInstanceOf(MatchCondition.class, group.conditions().get(1));
    }

    @Test
    void serialize_conditionWithValues_includesValues() throws Exception {
        MatchCriteria original = new MatchCondition("protocol", MatchCondition.ConditionOp.IN, null, List.of("SFTP", "FTP", "AS2"), null);
        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"values\""));
        assertFalse(json.contains("\"value\"")); // null excluded by @JsonInclude

        MatchCondition deserialized = (MatchCondition) mapper.readValue(json, MatchCriteria.class);
        assertEquals(3, deserialized.values().size());
    }

    @Test
    void serialize_notGroup_roundTrips() throws Exception {
        MatchCriteria original = new MatchGroup(MatchGroup.GroupOperator.NOT, List.of(
                new MatchCondition("fileSize", MatchCondition.ConditionOp.GT, 10000000, null, null)
        ));

        String json = mapper.writeValueAsString(original);
        MatchCriteria deserialized = mapper.readValue(json, MatchCriteria.class);

        assertInstanceOf(MatchGroup.class, deserialized);
        MatchGroup group = (MatchGroup) deserialized;
        assertEquals(MatchGroup.GroupOperator.NOT, group.operator());
        assertEquals(1, group.conditions().size());
    }
}
