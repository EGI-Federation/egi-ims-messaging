package egi.eu;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.hibernate.reactive.mutiny.Mutiny;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.logging.Logger;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.List;

import egi.checkin.CheckinConfig;
import egi.checkin.model.CheckinUser;
import egi.eu.entity.MessageEntity;
import egi.eu.model.*;


/***
 * Resource for user queries and operations.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class Messages extends BaseResource {

    private static final Logger log = Logger.getLogger(Users.class);

    @Inject
    MeterRegistry registry;

    @Inject
    SecurityIdentity identity;

    @Inject
    CheckinConfig checkinConfig;

    @Inject
    IntegratedManagementSystemConfig imsConfig;

    @Inject
    Mutiny.SessionFactory sf;

    // Parameter(s) to add to all endpoints
    @RestHeader(TEST_STUB)
    @Parameter(hidden = true)
    @Schema(defaultValue = "default")
    String stub;

    /***
     * Page of notification messages
     */
    public static class PageOfMessages extends Page<Message, LocalDateTime> {
        public PageOfMessages(String baseUri, LocalDateTime from, int limit, List<MessageEntity> messages_) {
            super();

            var messages = messages_.stream().map(Message::new).collect(Collectors.toList());
            populate(baseUri, from, limit, messages, false);
        }
    }


    /***
     * Constructor
     */
    public Messages() { super(log); }

    /**
     * Send notification message to a user or to all users holding a role.
     * @param auth The access token needed to call the service.
     * @param message The message to send and the recipient(s).
     * @return API Response, wraps a {@link Count} or an ActionError entity
     */
    @POST
    @Path("/messages")
    @SecurityRequirement(name = "OIDC")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed( Role.IMS_USER )
    @Operation(operationId = "sendMessage", summary = "Send message to a user or to all users holding a role")
    @APIResponses(value = {
            @APIResponse(responseCode = "201", description = "Sent",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = Count.class))),
            @APIResponse(responseCode = "400", description="Invalid parameters or configuration",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "401", description="Authorization required"),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> send(@RestHeader(HttpHeaders.AUTHORIZATION) String auth, Message message)
    {
        final var checkinUserId = identity.getAttribute(CheckinUser.ATTR_USERID).toString();
        addToDC("userIdCaller", checkinUserId);
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());
        addToDC("message", message);

        final boolean sendToRole = null != message.process || null != message.role;
        log.infof("Sending message to user%s", sendToRole ? "s with role" : "");

        if(sendToRole && (null == message.process || null == message.role)) {
            // Message must be addressed either to a user or to a role
            var ae = new ActionError("badRequest",
                               "Both IMS process and role must be specified when sending to users holding role");
            return Uni.createFrom().item(ae.toResponse());
        }

        final var sentCount = new ArrayList<Integer>();
        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                // Get REST client for Check-in
                if(sendToRole && !checkin.init(this.checkinConfig, this.imsConfig, stub))
                    // Could not get REST client
                    return Uni.createFrom().failure(new ActionException("invalidConfig"));

                return Uni.createFrom().item(unused);
            })
            .chain(unused -> {
                if(sendToRole)
                    // List users holding role
                    return checkin.listUsersWithGroupRolesAsync(message.process, message.role);

                var userList = new ArrayList<CheckinUser>();
                var user = new CheckinUser(message.checkinUserId);
                userList.add(user);

                return Uni.createFrom().item(userList);
            })
            .chain(usersWithRole -> {
                return sf.withTransaction((session, tx) -> {
                    // Create new message(s)
                    message.process = null;
                    message.role = null;

                    var messages = new ArrayList<MessageEntity>();
                    for(var user : usersWithRole) {
                        // When sending to all users with role, exclude caller
                        if(sendToRole && checkinUserId.equals(user.checkinUserId))
                            continue;

                        message.checkinUserId = user.checkinUserId;
                        messages.add(new MessageEntity(message));
                    }
                    sentCount.add(messages.size());

                    return session.persistAll(messages.toArray());
                });
            })
            .chain(unused -> {
                // Send complete, success
                var count = new Count("Sent");
                count.sentMessages = sentCount.get(0);
                addToDC("messageCount", count.sentMessages);
                log.infof("Message%s sent", count.sentMessages > 0 ? "s" : "");
                return Uni.createFrom().item(Response.ok(count).status(Response.Status.CREATED).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("Failed to send message");
                return new ActionError(e).toResponse();
            });

        return result;
    }

    /**
     * Mark notification message as read.
     * @param auth The access token needed to call the service.
     * @param messageId The Id of the message to mark as read.
     * @return API Response, wraps an ActionSuccess or an ActionError entity
     */
    @PATCH
    @Path("/message/{messageId}/read")
    @SecurityRequirement(name = "OIDC")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed( Role.IMS_USER )
    @Operation(operationId = "markMessageRead", summary = "Mark message as read")
    @APIResponses(value = {
            @APIResponse(responseCode = "201", description = "Read",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionSuccess.class))),
            @APIResponse(responseCode = "400", description="Invalid parameters or configuration",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "401", description="Authorization required"),
            @APIResponse(responseCode = "403", description="Permission denied"),
            @APIResponse(responseCode = "404", description="Not found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> markRead(@RestHeader(HttpHeaders.AUTHORIZATION) String auth,

                                  @RestPath("messageId")
                                  Long messageId)
    {
        final var checkinUserId = identity.getAttribute(CheckinUser.ATTR_USERID).toString();
        addToDC("userIdCaller", checkinUserId);
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());
        addToDC("messageId", messageId);

        log.info("Marking message read");

        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                return sf.withTransaction((session, tx) -> { return
                    // Get the message
                    MessageEntity.getMessage(messageId)
                    .chain(message -> {
                        // Got the message
                        if(null == message)
                            // No such message
                            return Uni.createFrom().failure(new ActionException("notFound", "Message not found"));

                        if(!message.checkinUserId.equals(checkinUserId))
                            // This message does not belong to the caller
                            return Uni.createFrom().failure(new ActionException("noAccess",
                                                                                "Can only read your own messages"));

                        // Update message
                        message.wasRead = true;
                        return session.persist(message);
                    });
                });
            })
            .chain(unused -> {
                // Read complete, success
                log.info("Message marked read");
                return Uni.createFrom().item(Response.ok(new ActionSuccess("Read"))
                                                     .status(Response.Status.CREATED).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("Failed to mark message read");
                return new ActionError(e).toResponse();
            });

        return result;
    }

    /**
     * Mark all notifications or the caller as read.
     * @param auth The access token needed to call the service.
     * @return API Response, wraps an ActionSuccess or an ActionError entity
     */
    @PATCH
    @Path("/messages/read")
    @SecurityRequirement(name = "OIDC")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed( Role.IMS_USER )
    @Operation(operationId = "markAllMessagesRead", summary = "Mark all messages as read")
    @APIResponses(value = {
            @APIResponse(responseCode = "201", description = "Read",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionSuccess.class))),
            @APIResponse(responseCode = "400", description="Invalid parameters or configuration",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "401", description="Authorization required"),
            @APIResponse(responseCode = "403", description="Permission denied"),
            @APIResponse(responseCode = "404", description="Not found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> markAllRead(@RestHeader(HttpHeaders.AUTHORIZATION) String auth)
    {
        final var checkinUserId = identity.getAttribute(CheckinUser.ATTR_USERID).toString();
        addToDC("userIdCaller", checkinUserId);
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());

        log.info("Marking all messages read");

        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                return sf.withTransaction((session, tx) -> { return
                    // Get all unread messages
                    MessageEntity.getUnreadMessages(checkinUserId)
                    .chain(messages -> {
                        // Got the unread messages
                        for(var message : messages)
                            message.wasRead = true;

                        // Update messages
                        return session.persistAll(messages.toArray());
                    });
                });
            })
            .chain(unused -> {
                // Read complete, success
                log.info("All messages marked read");
                return Uni.createFrom().item(Response.ok(new ActionSuccess("Read"))
                                                     .status(Response.Status.CREATED).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("Failed to mark all messages read");
                return new ActionError(e).toResponse();
            });

        return result;
    }

    /**
     * Get number of unread notification messages for the caller.
     * @param auth The access token needed to call the service.
     * @return API Response, wraps a {@link Count} or an ActionError entity
     */
    @GET
    @Path("/messages/unread")
    @SecurityRequirement(name = "OIDC")
    @RolesAllowed( Role.IMS_USER )
    @Operation(operationId = "countUnreadMessages", summary = "Get number of unread messages")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Success",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = Count.class))),
            @APIResponse(responseCode = "400", description="Invalid parameters or configuration",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "401", description="Authorization required"),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> countUnread(@RestHeader(HttpHeaders.AUTHORIZATION) String auth)
    {
        final var checkinUserId = identity.getAttribute(CheckinUser.ATTR_USERID).toString();
        addToDC("userIdCaller", checkinUserId);
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());

        log.info("Count unread messages");

        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                return sf.withSession(session -> MessageEntity.countUnreadMessages(checkinUserId));
            })
            .chain(unread -> {
                // Got unread count, success
                log.info("Got unread message count");
                var count = new Count(unread > 0 ? "Found unread messages" : "No unread messages");
                count.unreadMessages = unread;
                return Uni.createFrom().item(Response.ok(count).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("Failed to count unread messages");
                return new ActionError(e, Tuple2.of("oidcInstance", this.checkinConfig.server())).toResponse();
            });

        return result;
    }

    /**
     * List notification messages for the caller.
     * @param auth The access token needed to call the service
     * @param from_ The UTC date and time after which to return elements
     * @param limit_ The maximum number of elements to return
     * @return API Response, wraps an {@link PageOfMessages} or an ActionError entity
     */
    @GET
    @Path("/messages")
    @SecurityRequirement(name = "OIDC")
    @RolesAllowed( Role.IMS_USER )
    @Operation(operationId = "listMessages",
            summary = "List notification messages",
            description = "Returns messages in reverse chronological order")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Success",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = PageOfMessages.class))),
            @APIResponse(responseCode = "400", description="Invalid parameters or configuration",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "401", description="Authorization required"),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> list(@RestHeader(HttpHeaders.AUTHORIZATION) String auth,
                              @Context UriInfo uriInfo,
                              @Context HttpHeaders httpHeaders,

                              @RestQuery("from")
                              @Parameter(description = "Only return logs before this UTC date and time.\n" +
                                                       "Do not include time zone in this parameter.")
                              @Schema(format = "yyyy-mm-ddThh:mm:ss.SSSSSS", defaultValue = "now")
                              String from_,

                              @RestQuery("limit")
                              @Parameter(description = "Restrict the number of results returned")
                              @Schema(defaultValue = "100")
                              int limit_)
    {
        final int limit = (0 == limit_) ? 100 : limit_;

        final var checkinUserId = identity.getAttribute(CheckinUser.ATTR_USERID).toString();
        addToDC("userIdCaller", checkinUserId);
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());
        addToDC("from", from_);
        addToDC("limit", limit);

        log.info("Listing messages");

        LocalDateTime from = null;
        try {
            if((null == from_ || from_.isBlank() || from_.equalsIgnoreCase("now")))
                from = LocalDateTime.now();
            else
                // Convert from UTC to the local timezone
                from = LocalDateTime.parse(from_)
                                    .atZone(ZoneOffset.UTC)
                                    .withZoneSameInstant(ZoneId.systemDefault())
                                    .toLocalDateTime();
        }
        catch(DateTimeParseException e) {
            var ae = new ActionError("badRequest", "Invalid parameter from");
            return Uni.createFrom().item(ae.toResponse());
        }

        LocalDateTime finalFrom = from;
        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                return sf.withSession(session -> MessageEntity.getMessages(checkinUserId, finalFrom, limit));
            })
            .chain(messages -> {
                // Got messages, success
                log.info("Got messages");
                var uri = getRealRequestUri(uriInfo, httpHeaders);
                var page = new PageOfMessages(uri.toString(), finalFrom, limit, messages);
                var logCount = messages.size();
                if(!messages.isEmpty() && logCount == limit) {
                    var lastLog = messages.get(logCount - 1);
                    page.setNextPage(lastLog.sentOn, limit);
                }

                return Uni.createFrom().item(Response.ok(page).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("Failed to list messages");
                return new ActionError(e, Tuple2.of("oidcInstance", this.checkinConfig.server())).toResponse();
            });

        return result;
    }
}
