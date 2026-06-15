package org.example.core.exception;

public class TargetSizeUnreachableException extends Exception {
    private final long achievedBytes;

    public TargetSizeUnreachableException(long achievedBytes) {
        super("Target size unreachable; best achieved: " + achievedBytes + " bytes");
        this.achievedBytes = achievedBytes;
    }

    public long getAchievedBytes() {
        return achievedBytes;
    }
}
