package com.cryptoradar.analytics.model;

import java.time.Instant;
import java.util.Map;

public class CorrelationMatrix {

    private Instant timestamp;
    private String interval;
    private int days;
    private Map<String, Map<String, Double>> matrix;

    public CorrelationMatrix() {
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public Map<String, Map<String, Double>> getMatrix() {
        return matrix;
    }

    public void setMatrix(Map<String, Map<String, Double>> matrix) {
        this.matrix = matrix;
    }
}
