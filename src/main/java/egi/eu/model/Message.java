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

    public Long id;

    @Schema(description="Notification message")
    public String message; // Markdown

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String category;

    @Schema(description="Optional action link")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String url;

    @Schema(description="Addressee of the message")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String checkinUserId;

    @Schema(description="IMS process of the role.\n" +
                        "Use together with field _role_.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String process;

    @Schema(description="Send message to all users holding this process-specific role.\n" +
                        "Use together with field _group_.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String role;

    public boolean wasRead;

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
        this.id = message.id;
        this.message = message.message;
        this.category = message.category;
        this.url = message.link;
        this.wasRead = message.wasRead;
        this.sentOn = message.sentOn;
    }
}
