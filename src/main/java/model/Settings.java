package model;

public class Settings {
    private int schemaVersion;
    private String checkstyleConfigUrl;

    public Settings() {
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getCheckstyleConfigUrl() {
        return checkstyleConfigUrl;
    }

    public void setCheckstyleConfigUrl(String checkstyleConfigUrl) {
        this.checkstyleConfigUrl = checkstyleConfigUrl;
    }
}
