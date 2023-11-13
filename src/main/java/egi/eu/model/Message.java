package egi.eu.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;

import egi.eu.entity.MessageEntity;


/***
 * Notification message
 */
public class Message {

    @Schema(enumeration={ "Message" })
    public String kind = "Message";

    public String message; // Markdown

    public String checkinUserId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public LocalDateTime sentOn;


    /***
     * Constructor
     */
    public Message() {}

    /***
     * Copy constructor
     * @param message The entity to copy
     */
    public Message(MessageEntity message) {
        this.message = message.message;
        this.checkinUserId = message.checkinUserId;
        this.sentOn = message.sentOn;
    }
}
