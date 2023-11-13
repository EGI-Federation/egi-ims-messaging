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
import java.time.format.DateTimeParseException;
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
     * Send notification message to a user.
     * @param auth The access token needed to call the service.
     * @param message The message to send and the recipient.
     * @return API Response, wraps an ActionSuccess or an ActionError entity
     */
    @POST
    @Path("/messages")
    @SecurityRequirement(name = "OIDC")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed( Role.IMS_USER )
    @Operation(operationId = "sendToUser", summary = "Send message to a user")
    @APIResponses(value = {
            @APIResponse(responseCode = "201", description = "Sent",
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
    public Uni<Response> sendToUser(@RestHeader(HttpHeaders.AUTHORIZATION) String auth, Message message)
    {
        addToDC("userIdCaller", identity.getAttribute(CheckinUser.ATTR_USERID));
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());
        addToDC("message", message);

        log.info("Sending message to user");

        if(null == message.message || message.message.isBlank()) {
            // Message must be specified
            var ae = new ActionError("badRequest", "Message body is required");
            return Uni.createFrom().item(ae.toResponse());
        }
        if(null == message.checkinUserId || message.checkinUserId.isBlank()) {
            // Recipient must be specified
            var ae = new ActionError("badRequest", "Message recipient is required");
            return Uni.createFrom().item(ae.toResponse());
        }

        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                return sf.withTransaction((session, tx) -> {
                    // Create new message
                    var newMessage = new MessageEntity(message);
                    return session.persist(newMessage);
                });
            })
            .chain(unused -> {
                // Send complete, success
                log.info("Message sent");
                return Uni.createFrom().item(Response.ok(new ActionSuccess("Sent"))
                                                     .status(Response.Status.CREATED).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("Failed to send message");
                return new ActionError(e).toResponse();
            });

        return result;
    }

    /**
     * List notification messages for the caller.
     * @param auth The access token needed to call the service.
     * @param from_ The first element to return
     * @param limit_ The maximum number of elements to return
     * @return API Response, wraps an ActionSuccess({@link PageOfMessages}) or an ActionError entity
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
            @APIResponse(responseCode = "403", description="Permission denied"),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> listMessages(@RestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                      @Context UriInfo uriInfo,
                                      @Context HttpHeaders httpHeaders,

                                      @RestQuery("from")
                                      @Parameter(description = "Only return logs before this date and time")
                                      @Schema(format = "yyyy-mm-ddThh:mm:ss", defaultValue = "now")
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
                from = LocalDateTime.parse(from_);
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
                .chain(logs -> {
                    // Got messages, success
                    log.info("Got messages");
                    var uri = getRealRequestUri(uriInfo, httpHeaders);
                    var page = new PageOfMessages(uri.toString(), finalFrom, limit, logs);
                    var logCount = logs.size();
                    if(!logs.isEmpty() && logCount == limit) {
                        var lastLog = logs.get(logCount - 1);
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
