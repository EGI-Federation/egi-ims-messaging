package egi.eu;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.logging.Logger;
import io.smallrye.mutiny.Uni;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;

import java.io.IOException;
import java.nio.file.Files;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import egi.checkin.model.CheckinUser;
import egi.eu.model.*;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;


/***
 * Resource for image queries and operations.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class Images extends BaseResource {

    private static final Logger log = Logger.getLogger(Images.class);

    @Inject
    MeterRegistry registry;

    @Inject
    SecurityIdentity identity;

    @Inject
    IntegratedManagementSystemConfig imsConfig;

    @Inject
    ImagesConfig imgConfig;

    // Parameter(s) to add to all endpoints
    @RestHeader(TEST_STUB)
    @Parameter(hidden = true)
    @Schema(defaultValue = "default")
    String stub;


    /***
     * Constructor
     */
    public Images() { super(log); }

    /**
     * Check if an image can be uploaded.
     * @param auth The access token needed to call the service.
     * @return API Response, wraps an ActionSuccess or an ActionError entity
     */
    @POST
    @Path("/images/check")
    @SecurityRequirement(name = "OIDC")
    @RolesAllowed(Role.IMS_USER)
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "checkUpload", summary = "Check if uploading image file is possible")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Allowed",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = UserInfo.class))),
            @APIResponse(responseCode = "400", description="Invalid parameters or configuration",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "401", description="Authorization required"),
            @APIResponse(responseCode = "403", description="Permission denied"),
            @APIResponse(responseCode = "409", description="File already exists"),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> checkUpload(@RestHeader(HttpHeaders.AUTHORIZATION) String auth, FileInfo info)
    {
        addToDC("userIdCaller", identity.getAttribute(CheckinUser.ATTR_USERID));
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());
        addToDC("info", info);

        log.info("Checking file upload");

        if(null == info || null == info.name || info.name.isBlank() || 0 == info.size) {
            // File information is required
            var ae = new ActionError("badRequest", "File details are required");
            return Uni.createFrom().item(ae.toResponse());
        }

        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                // Check if file already exists
                var path = java.nio.file.Path.of(this.imgConfig.path()).resolve(info.name);
                if(Files.exists(path))
                    return Uni.createFrom().failure(new ServiceException("fileExists", "Cannot overwrite existing file"));

                return Uni.createFrom().voidItem();
            })
            .chain(unused -> {
                // All checks passed, success
                log.info("File upload allowed");
                return Uni.createFrom().item(Response.ok(new ActionSuccess("Allowed")).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("File upload check failed");
                return new ActionError(e).toResponse();
            });

        return result;
    }

    /**
     * Upload an image.
     * @param auth The access token needed to call the service.
     * @return API Response, wraps an ActionSuccess or an ActionError entity
     */
    @POST
    @Path("/images")
    @SecurityRequirement(name = "OIDC")
    @RolesAllowed(Role.IMS_USER)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(operationId = "upload", summary = "Upload an image file")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Uploaded",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = UserInfo.class))),
            @APIResponse(responseCode = "400", description="Invalid parameters or configuration",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ActionError.class))),
            @APIResponse(responseCode = "401", description="Authorization required"),
            @APIResponse(responseCode = "403", description="Permission denied"),
            @APIResponse(responseCode = "409", description="File already exists"),
            @APIResponse(responseCode = "503", description="Try again later")
    })
    public Uni<Response> upload(@RestHeader(HttpHeaders.AUTHORIZATION) String auth,

                                @RestForm("imageFile")
                                FileUpload imageFile)
    {
        addToDC("userIdCaller", identity.getAttribute(CheckinUser.ATTR_USERID));
        addToDC("userNameCaller", identity.getAttribute(CheckinUser.ATTR_FULLNAME));
        addToDC("processName", imsConfig.group());
        addToDC("fileName", imageFile.name());

        log.info("Uploading");

        Uni<Response> result = Uni.createFrom().nullItem()

            .chain(unused -> {
                // Check if file already exists
                var path = java.nio.file.Path.of(this.imgConfig.path()).resolve(imageFile.fileName());
                if(Files.exists(path))
                    return Uni.createFrom().failure(new ServiceException("fileExists", "Cannot overwrite existing file"));

                try {
                    Files.move(imageFile.uploadedFile(), path, REPLACE_EXISTING);
                } catch(IOException e) {
                    throw new RuntimeException(e);
                }

                return Uni.createFrom().voidItem();
            })
            .chain(unused -> {
                // Success
                log.info("Uploaded");
                return Uni.createFrom().item(Response.ok(new ActionSuccess("Allowed")).build());
            })
            .onFailure().recoverWithItem(e -> {
                log.error("Upload failed");
                return new ActionError(e).toResponse();
            });

        return result;
    }
}
