package egi.jira;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import org.eclipse.microprofile.openapi.annotations.media.Schema;


/***
 * The EGI Jira configuration
 */
@Schema(hidden=true)
@ConfigMapping(prefix = "egi.jira")
public interface JiraConfig {

    String server();

    // Access token to call the Jira API
    String token();
}
