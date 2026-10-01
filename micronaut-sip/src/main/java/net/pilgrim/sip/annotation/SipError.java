package net.pilgrim.sip.annotation;

import io.micronaut.context.annotation.Executable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as an exception handler for SIP requests.
 * Handlers can be specific to an exception class or catch all throwables.
 * <p>
 * Example:
 * <pre>
 * &#64;SipError(IllegalArgumentException.class)
 * public SipResponse handleBadRequest(SipRequest request, IllegalArgumentException ex) {
 *     return request.createResponse(400, ex.getMessage());
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Executable(processOnStartup = true)
public @interface SipError {
    /**
     * The exception class to handle. Defaults to {@link Throwable}.
     */
    Class<? extends Throwable> value() default Throwable.class;

    /**
     * Optional default SIP status code to associate with this error.
     */
    int status() default -1;
}
