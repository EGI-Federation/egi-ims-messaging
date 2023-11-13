package egi.eu.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.quarkus.panache.common.Page;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Uni;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import egi.eu.model.Message;


/**
 * Notification message
 */
@Entity
@Table(name = "messages")
public class MessageEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(length = 2048)
    @NotNull
    public String message;

    @Column(length = 256)
    public String link;

    public Boolean wasRead;

    @Column(length = 120)
    @NotNull
    public String checkinUserId;

    public LocalDateTime sentOn;

    @UpdateTimestamp
    public LocalDateTime changedOn;


    /***
     * Constructor
     */
    public MessageEntity() { super(); }

    /***
     * Copy constructor
     * @param message The notification message
     */
    public MessageEntity(Message message) {
        super();

        this.message = message.message;
        this.link = message.link;
        this.checkinUserId = message.checkinUserId;
        this.wasRead = false;
        this.sentOn = LocalDateTime.now();
    }

    /***
     * Get specific message.
     * @param messageId The Id of the message
     * @return Message entities
     */
    public static Uni<MessageEntity> getMessage(Long messageId) {

        return find("id", messageId).firstResult();
    }

    /***
     * Get messages for a user that are older than the specified datetime, in reverse chronological order.
     * @param checkinUserId The user to fetch messages for
     * @param from The date and time from where to start loading logs
     * @param limit The maximum number of logs to return
     * @return Message entities
     */
    public static Uni<List<MessageEntity>> getMessages(String checkinUserId, LocalDateTime from, int limit) {

        Map<String, Object> params = new HashMap<>();
        params.put("checkinUserId", checkinUserId);
        params.put("from", from);
        return find("checkinUserId = :checkinUserId AND sentOn < :from ORDER BY sentOn DESC", params)
                .page(Page.ofSize(limit))
                .list();
    }

    /***
     * Get the number of unread messages for a user.
     * @param checkinUserId The user to check unread messages for
     * @return Unread message count
     */
    public static Uni<Long> countUnreadMessages(String checkinUserId) {

        Map<String, Object> params = new HashMap<>();
        params.put("checkinUserId", checkinUserId);
        return count("checkinUserId = :checkinUserId AND wasRead = false", params);
    }

}
