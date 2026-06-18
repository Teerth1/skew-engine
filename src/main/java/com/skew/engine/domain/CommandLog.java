package com.skew.engine.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Logs each Discord command execution for usage analytics.
 * Used to track metrics like "Processing X commands per day".
 */
@Entity
@Data
@Table(name = "command_logs")
public class CommandLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Command name (e.g., "spread", "analyze", "portfolio") */
    private String command;

    /** Discord username who ran the command */
    private String userId;

    /** When the command was executed */
    private LocalDateTime timestamp = LocalDateTime.now();

    /** Whether the command completed successfully */
    private boolean success = true;

    public CommandLog() {
    }

    public CommandLog(String command, String userId) {
        this.command = command;
        this.userId = userId;
    }

    public CommandLog(String command, String userId, boolean success) {
        this.command = command;
        this.userId = userId;
        this.success = success;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}
