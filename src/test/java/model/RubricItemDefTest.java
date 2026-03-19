package model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for model.RubricItemDef
 *
 * Verified targets (from src/main/java/model/RubricItemDef.java):
 * - public String getId()
 * - public void setId(String id)
 * - public String getName()
 * - public void setName(String name)
 * - public String getDescription()
 * - public void setDescription(String description)
 * - public int getDefaultPoints()
 * - public void setDefaultPoints(int defaultPoints)
 * - public boolean isCheckstyleItem()
 * - public void setCheckstyleItem(boolean checkstyleItem)
 */
final class RubricItemDefTest {

    @Test
    void defaults_areNullsAndZeroAndFalse() {
        RubricItemDef d = new RubricItemDef();
        assertNull(d.getId());
        assertNull(d.getName());
        assertNull(d.getDescription());
        assertEquals(0, d.getDefaultPoints());
        assertFalse(d.isCheckstyleItem());
    }

    @Test
    void settersAndGetters_roundtripValues() {
        RubricItemDef d = new RubricItemDef();
        d.setId("ri_foo");
        d.setName("Foo");
        d.setDescription("Some description");
        d.setDefaultPoints(12);
        d.setCheckstyleItem(true);

        assertEquals("ri_foo", d.getId());
        assertEquals("Foo", d.getName());
        assertEquals("Some description", d.getDescription());
        assertEquals(12, d.getDefaultPoints());
        assertTrue(d.isCheckstyleItem());
    }

    @Test
    void jsonSerialization_usesIsCheckstyleItemPropertyName() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RubricItemDef d = new RubricItemDef();
        d.setId("ri_x");
        d.setName("X");
        d.setDefaultPoints(5);
        d.setCheckstyleItem(true);

        String json = mapper.writeValueAsString(d);
        // should contain the boolean property named "isCheckstyleItem"
        assertTrue(json.contains("\"isCheckstyleItem\":true"), "JSON should contain isCheckstyleItem:true -> " + json);

        // when false it should be present and false
        d.setCheckstyleItem(false);
        String json2 = mapper.writeValueAsString(d);
        assertTrue(json2.contains("\"isCheckstyleItem\":false"), "JSON should contain isCheckstyleItem:false -> " + json2);
    }

    @Test
    void jsonDeserialization_acceptsAliasCheckstyleItemAndIsCheckstyleItem() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String j1 = "{\"id\":\"ri_a\",\"name\":\"A\",\"defaultPoints\":3,\"checkstyleItem\":true}";
        RubricItemDef d1 = mapper.readValue(j1, RubricItemDef.class);
        assertEquals("ri_a", d1.getId());
        assertEquals("A", d1.getName());
        assertEquals(3, d1.getDefaultPoints());
        assertTrue(d1.isCheckstyleItem());

        String j2 = "{\"id\":\"ri_b\",\"name\":\"B\",\"defaultPoints\":4,\"isCheckstyleItem\":false}";
        RubricItemDef d2 = mapper.readValue(j2, RubricItemDef.class);
        assertEquals("ri_b", d2.getId());
        assertEquals("B", d2.getName());
        assertEquals(4, d2.getDefaultPoints());
        assertFalse(d2.isCheckstyleItem());
    }
}
