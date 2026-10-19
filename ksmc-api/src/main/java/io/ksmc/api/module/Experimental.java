package io.ksmc.api.module;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Partie de l'API encore instable : elle peut changer dans une version mineure. */
@Documented
@Retention(RetentionPolicy.CLASS)
public @interface Experimental {
}
