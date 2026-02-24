package model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public class RubricItemDef {
    private String id;
    private String name;
    private String description;
    private int defaultPoints;

    private boolean checkstyleItem;

    public RubricItemDef() {
        // Required for JSON deserialization
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getDefaultPoints() {
        return defaultPoints;
    }

    public void setDefaultPoints(int defaultPoints) {
        this.defaultPoints = defaultPoints;
    }

    @JsonProperty("isCheckstyleItem")
    public boolean isCheckstyleItem() {
        return checkstyleItem;
    }

    @JsonProperty("isCheckstyleItem")
    @JsonAlias({"checkstyleItem"})
    public void setCheckstyleItem(boolean checkstyleItem) {
        this.checkstyleItem = checkstyleItem;
    }
}