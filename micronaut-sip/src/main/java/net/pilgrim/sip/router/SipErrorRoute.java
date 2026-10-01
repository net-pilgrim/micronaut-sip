package net.pilgrim.sip.router;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import net.pilgrim.sip.annotation.SipCallId;
import net.pilgrim.sip.annotation.SipError;
import net.pilgrim.sip.annotation.SipHeader;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;

/**
 * Encapsulates an error-handling route mapped to an exception type via @SipError.
 */
public class SipErrorRoute {

    private final Class<? extends Throwable> exceptionType;
    private final int status;
    private final Object controllerInstance;
    private final ExecutableMethod<Object, ?> executableMethod;
    private final Argument<?>[] arguments;
    private final Method fallbackMethod;

    public SipErrorRoute(Class<? extends Throwable> exceptionType,
                         int status,
                         Object controllerInstance,
                         ExecutableMethod<Object, ?> executableMethod) {
        this.exceptionType = exceptionType != null ? exceptionType : Throwable.class;
        this.status = status;
        this.controllerInstance = controllerInstance;
        this.executableMethod = executableMethod;
        this.arguments = executableMethod != null ? executableMethod.getArguments() : Argument.ZERO_ARGUMENTS;
        this.fallbackMethod = null;
    }

    public SipErrorRoute(Class<? extends Throwable> exceptionType,
                         int status,
                         Object controllerInstance,
                         Method fallbackMethod) {
        this.exceptionType = exceptionType != null ? exceptionType : Throwable.class;
        this.status = status;
        this.controllerInstance = controllerInstance;
        this.executableMethod = null;
        this.arguments = Argument.ZERO_ARGUMENTS;
        this.fallbackMethod = fallbackMethod;
        if (this.fallbackMethod != null) {
            this.fallbackMethod.setAccessible(true);
        }
    }

    public boolean matches(Throwable error) {
        if (error == null) return false;
        return exceptionType.isAssignableFrom(error.getClass());
    }

    public Class<? extends Throwable> getExceptionType() {
        return exceptionType;
    }

    public int getStatus() {
        return status;
    }

    public Object invoke(Throwable error, SipRequest request, SipSessionManager sessionManager) throws Exception {
        if (executableMethod != null) {
            Object[] args = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                Argument<?> arg = arguments[i];
                Class<?> paramType = arg.getType();

                if (Throwable.class.isAssignableFrom(paramType)) {
                    args[i] = error;
                } else if (SipRequest.class.isAssignableFrom(paramType)) {
                    args[i] = request;
                } else if (SipHeaders.class.isAssignableFrom(paramType)) {
                    args[i] = (request != null) ? request.getHeaders() : null;
                } else if (SipUri.class.isAssignableFrom(paramType)) {
                    args[i] = (request != null) ? request.getUri() : null;
                } else if (InetSocketAddress.class.isAssignableFrom(paramType)) {
                    args[i] = (request != null) ? request.getRemoteAddress() : null;
                } else if (SipSession.class.isAssignableFrom(paramType)) {
                    args[i] = (sessionManager != null && request != null)
                            ? sessionManager.getOrCreateSession(request.getCallId(), request.getRemoteAddress())
                            : null;
                } else if (arg.isAnnotationPresent(SipCallId.class)) {
                    args[i] = (request != null) ? request.getCallId() : null;
                } else if (arg.isAnnotationPresent(SipHeader.class)) {
                    AnnotationValue<SipHeader> headerAnn = arg.getAnnotation(SipHeader.class);
                    String headerName = headerAnn != null ? headerAnn.stringValue().orElse("") : "";
                    if (headerName.isEmpty()) {
                        headerName = arg.getName();
                    }
                    args[i] = (request != null) ? request.getHeaders().get(headerName) : null;
                } else {
                    args[i] = null;
                }
            }
            return executableMethod.invoke(controllerInstance, args);
        }

        if (fallbackMethod != null) {
            Parameter[] parameters = fallbackMethod.getParameters();
            Object[] args = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                Class<?> paramType = param.getType();

                if (Throwable.class.isAssignableFrom(paramType)) {
                    args[i] = error;
                } else if (SipRequest.class.isAssignableFrom(paramType)) {
                    args[i] = request;
                } else if (SipHeaders.class.isAssignableFrom(paramType)) {
                    args[i] = (request != null) ? request.getHeaders() : null;
                } else if (SipUri.class.isAssignableFrom(paramType)) {
                    args[i] = (request != null) ? request.getUri() : null;
                } else if (InetSocketAddress.class.isAssignableFrom(paramType)) {
                    args[i] = (request != null) ? request.getRemoteAddress() : null;
                } else if (SipSession.class.isAssignableFrom(paramType)) {
                    args[i] = (sessionManager != null && request != null)
                            ? sessionManager.getOrCreateSession(request.getCallId(), request.getRemoteAddress())
                            : null;
                } else if (param.isAnnotationPresent(SipCallId.class)) {
                    args[i] = (request != null) ? request.getCallId() : null;
                } else if (param.isAnnotationPresent(SipHeader.class)) {
                    SipHeader headerAnn = param.getAnnotation(SipHeader.class);
                    String headerName = headerAnn.value().isEmpty() ? param.getName() : headerAnn.value();
                    args[i] = (request != null) ? request.getHeaders().get(headerName) : null;
                } else {
                    args[i] = null;
                }
            }
            return fallbackMethod.invoke(controllerInstance, args);
        }

        throw new IllegalStateException("No executable or fallback method available for error route");
    }
}
