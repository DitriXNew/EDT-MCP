/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server;

/**
 * Represents a user signal sent from the status bar to control tool execution.
 * Users can send signals to interrupt, retry, or modify the behavior of long-running operations.
 */
public class UserSignal
{
    /** Type of signal */
    public enum SignalType
    {
        /** User cancelled the operation */
        CANCEL,
        /** User requests to retry the operation */
        RETRY,
        /** Operation continues in background, agent should check periodically */
        BACKGROUND,
        /** User requests to consult with expert */
        EXPERT,
        /** Custom message from user */
        CUSTOM
    }
    
    private final SignalType type;
    private final String message;
    private final long timestamp;
    
    /**
     * Creates a new user signal.
     * 
     * @param type the signal type
     * @param message the message to send to agent
     */
    public UserSignal(SignalType type, String message)
    {
        this.type = type;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Gets the signal type.
     * 
     * @return the signal type
     */
    public SignalType getType()
    {
        return type;
    }
    
    /**
     * Gets the message.
     * 
     * @return the message
     */
    public String getMessage()
    {
        return message;
    }
    
    /**
     * Gets the timestamp when the signal was created.
     * 
     * @return timestamp in milliseconds
     */
    public long getTimestamp()
    {
        return timestamp;
    }
    
    /**
     * Creates a JSON representation for the agent response.
     * 
     * @return JSON string
     */
    public String toJson()
    {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("userSignal", true); //$NON-NLS-1$
        json.addProperty("signalType", type.name()); //$NON-NLS-1$
        json.addProperty("message", message); //$NON-NLS-1$
        return new com.google.gson.Gson().toJson(json);
    }
    
    /**
     * Gets the default message for a signal type.
     * 
     * @param type the signal type
     * @return default message
     */
    public static String getDefaultMessage(SignalType type)
    {
        switch (type)
        {
            case CANCEL:
                return "Operation was cancelled by user. Please acknowledge and proceed with next steps.";
            case RETRY:
                return "EDT error occurred. Please retry the operation.";
            case BACKGROUND:
                return "Long operation continues in background.";
            case EXPERT:
                return "User requested to stop and consult with expert before continuing. Please use askExpert tool to clarify the situation.";
            case CUSTOM:
            default:
                return "";
        }
    }
}
