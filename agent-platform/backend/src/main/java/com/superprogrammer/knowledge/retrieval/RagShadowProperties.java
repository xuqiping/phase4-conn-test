package com.superprogrammer.knowledge.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.shadow")
public class RagShadowProperties {
    private boolean enabled = false;
    private int samplePercentage = 0;
    private double budgetPerRequest = 0;
    private long timeoutMs = 1500;

    public RagShadowProperties() {}
    public RagShadowProperties(boolean enabled, int samplePercentage, double budgetPerRequest, long timeoutMs) {
        this.enabled = enabled; this.samplePercentage = samplePercentage;
        this.budgetPerRequest = budgetPerRequest; this.timeoutMs = timeoutMs;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSamplePercentage() { return samplePercentage; }
    public void setSamplePercentage(int value) { this.samplePercentage = value; }
    public double getBudgetPerRequest() { return budgetPerRequest; }
    public void setBudgetPerRequest(double value) { this.budgetPerRequest = value; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long value) { this.timeoutMs = value; }
}
