package egi.eu.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import egi.eu.ActionSuccess;


/***
 * Count of messages
 */
public class Count extends ActionSuccess {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Long totalMessages = null;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Long unreadMessages = null;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer sentMessages = null;

    /***
     * Constructor
     */
    public Count() {
        super();
    }

    /**
     * Construct from message
     */
    public Count(String message) {
        super(message);
    }
}
