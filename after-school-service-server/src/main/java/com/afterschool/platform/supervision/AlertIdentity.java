package com.afterschool.platform.supervision;

public class AlertIdentity {

    private long id;
    private String scanRunId;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getScanRunId() {
        return scanRunId;
    }

    public void setScanRunId(String scanRunId) {
        this.scanRunId = scanRunId;
    }
}
