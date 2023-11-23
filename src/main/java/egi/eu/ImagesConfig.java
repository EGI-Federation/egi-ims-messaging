package egi.eu;

import io.smallrye.config.ConfigMapping;
import jakarta.enterprise.context.ApplicationScoped;


/***
 * The configuration of the image uploads
 */
@ConfigMapping(prefix = "egi.images")
@ApplicationScoped
public interface ImagesConfig {

    String path();
}
