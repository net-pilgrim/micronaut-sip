package net.pilgrim.sip.annotation;

import io.micronaut.context.annotation.DefaultScope;
import io.micronaut.context.annotation.Executable;
import jakarta.inject.Singleton;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a SIP controller component.
 * Handled methods can use {@link OnInvite}, {@link OnBye}, {@link OnAck}, etc.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Singleton
@DefaultScope(Singleton.class)
@Executable(processOnStartup = true)
public @interface SipController {
    /**
     * Optional URI path or username prefix filter for this controller.
     * Empty string matches all URIs.
     */
    String value() default "";
}
