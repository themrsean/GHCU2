package model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for model.RubricItemRef
 *
 * Verified targets (from src/main/java/model/RubricItemRef.java):
 * - public String getRubricItemId()
 * - public void setRubricItemId(String rubricItemId)
 * - public int getPoints()
 * - public void setPoints(int points)
 */
final class RubricItemRefTest {

    @Test
    void defaults_nullAndZero() {
        RubricItemRef r = new RubricItemRef();
        assertNull(r.getRubricItemId());
        assertEquals(0, r.getPoints());
    }

    @Test
    void settersAndGetters_roundtrip() {
        RubricItemRef r = new RubricItemRef();
        r.setRubricItemId("ri_commits");
        r.setPoints(10);
        assertEquals("ri_commits", r.getRubricItemId());
        assertEquals(10, r.getPoints());
    }

    @Test
    void jsonSerialization_andDeserialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RubricItemRef r = new RubricItemRef();
        r.setRubricItemId("ri_x");
        r.setPoints(7);

        String json = mapper.writeValueAsString(r);
        assertTrue(json.contains("\"rubricItemId\""));
        assertTrue(json.contains("\"points\""));

        RubricItemRef r2 = mapper.readValue(json, RubricItemRef.class);
        assertEquals("ri_x", r2.getRubricItemId());
        assertEquals(7, r2.getPoints());
    }
}
