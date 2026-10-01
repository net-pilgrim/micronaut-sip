package net.pilgrim.sip.router;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;

/**
 * Encapsulates a route mapping a SIP Method and URI pattern to an ExecutableMethod handler.
 */
public class SipRoute {

    private static final Logger LOG = LoggerFactory.getLogger(SipRoute.class);

    private final SipMethod method;
    private final String customMethod;
    private final String uriPattern;
    private final Object controllerInstance;
    private final ExecutableMethod<Object, ?> executableMethod;
    private final Argument<?>[] arguments;
    private final Method fallbackMethod;

    public SipRoute(SipMethod method, String customMethod, String uriPattern, Object controllerInstance, ExecutableMethod<Object, ?> executableMethod) {
        this.method = method;
        this.customMethod = customMethod;
        this.uriPattern = uriPattern != null ? uriPattern.trim() : "";
        this.controllerInstance = controllerInstance;
        this.executableMethod = executableMethod;
        this.arguments = executableMethod != null ? executableMethod.getArguments() : Argument.ZERO_ARGUMENTS;
        this.fallbackMethod = null;
    }

    public SipRoute(SipMethod method, String customMethod, String uriPattern, Object controllerInstance, Method fallbackMethod) {
        this.method = method;
        this.customMethod = customMethod;
        this.uriPattern = uriPattern != null ? uriPattern.trim() : "";
        this.controllerInstance = controllerInstance;
        this.executableMethod = null;
        this.arguments = Argument.ZERO_ARGUMENTS;
        this.fallbackMethod = fallbackMethod;
        if (this.fallbackMethod != null) {
            this.fallbackMethod.setAccessible(true);
        }
    }

    public boolean matches(SipRequest request) {
        if (customMethod != null && !customMethod.isEmpty()) {
            if (!customMethod.equalsIgnoreCase(request.getMethod().name())) {
                return false;
            }
        } else if (method != null) {
            if (request.getMethod() != method) {
                return false;
            }
        }

        if (!uriPattern.isEmpty()) {
            String uriStr = request.getUri() != null ? request.getUri().toString() : "";
            String userStr = (request.getUri() != null && request.getUri().getUser() != null) ? request.getUri().getUser() : "";
            // Check if matches either entire URI or user prefix
            if (!uriStr.contains(uriPattern) && !userStr.startsWith(uriPattern)) {
                return false;
            }
        }

        return true;
    }

    public Object invoke(SipRequest request, SipSessionManager sessionManager) throws Exception {
        if (executableMethod != null) {
            Object[] args = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                Argument<?> arg = arguments[i];
                Class<?> paramType = arg.getType();

                if (SipRequest.class.isAssignableFrom(paramType)) {
                    args[i] = request;
                } else if (SipHeaders.class.isAssignableFrom(paramType)) {
                    args[i] = request.getHeaders();
                } else if (arg.isAnnotationPresent(SipCallId.class)) {
                    args[i] = convertValue(request.getCallId(), paramType);
                } else if (arg.isAnnotationPresent(SipFrom.class)) {
                    if (SipUri.class.isAssignableFrom(paramType)) {
                        args[i] = SipUri.extractUri(request.getFrom());
                    } else {
                        args[i] = convertValue(request.getFrom(), paramType);
                    }
                } else if (arg.isAnnotationPresent(SipTo.class)) {
                    if (SipUri.class.isAssignableFrom(paramType)) {
                        args[i] = SipUri.extractUri(request.getTo());
                    } else {
                        args[i] = convertValue(request.getTo(), paramType);
                    }
                } else if (arg.isAnnotationPresent(SipParam.class)) {
                    AnnotationValue<SipParam> paramAnn = arg.getAnnotation(SipParam.class);
                    String paramName = paramAnn != null ? paramAnn.stringValue().orElse("") : "";
                    if (paramName.isEmpty()) {
                        paramName = arg.getName();
                    }
                    String paramVal = (request.getUri() != null) ? request.getUri().getParameter(paramName) : null;
                    if (paramVal == null && paramAnn != null) {
                        String def = paramAnn.stringValue("defaultValue").orElse("");
                        if (!def.isEmpty()) {
                            paramVal = def;
                        }
                    }
                    boolean required = paramAnn != null && paramAnn.booleanValue("required").orElse(false);
                    if (paramVal == null && required) {
                        throw new IllegalArgumentException("Missing required SIP URI parameter: " + paramName);
                    }
                    args[i] = convertValue(paramVal, paramType);
                } else if (SipUri.class.isAssignableFrom(paramType)) {
                    args[i] = request.getUri();
                } else if (ViaHeader.class.isAssignableFrom(paramType)) {
                    String via = request.getVia();
                    args[i] = (via != null) ? ViaHeader.parse(via) : null;
                } else if (InetSocketAddress.class.isAssignableFrom(paramType)) {
                    args[i] = request.getRemoteAddress();
                } else if (SipSession.class.isAssignableFrom(paramType)) {
                    args[i] = sessionManager.getOrCreateSession(request.getCallId(), request.getRemoteAddress());
                } else if (arg.isAnnotationPresent(SipHeader.class)) {
                    AnnotationValue<SipHeader> headerAnn = arg.getAnnotation(SipHeader.class);
                    String headerName = headerAnn != null ? headerAnn.stringValue().orElse("") : "";
                    if (headerName.isEmpty()) {
                        headerName = arg.getName();
                    }
                    String headerVal = request.getHeaders().get(headerName);
                    if (headerVal == null && headerAnn != null) {
                        String def = headerAnn.stringValue("defaultValue").orElse("");
                        if (!def.isEmpty()) {
                            headerVal = def;
                        }
                    }
                    boolean required = headerAnn != null ? headerAnn.booleanValue("required").orElse(false) : false;
                    if (headerVal == null && required) {
                        throw new IllegalArgumentException("Missing required SIP header: " + headerName);
                    }
                    if (SipUri.class.isAssignableFrom(paramType)) {
                        args[i] = SipUri.extractUri(headerVal);
                    } else if (ViaHeader.class.isAssignableFrom(paramType)) {
                        args[i] = (headerVal != null) ? ViaHeader.parse(headerVal) : null;
                    } else {
                        args[i] = convertValue(headerVal, paramType);
                    }
                } else if (arg.isAnnotationPresent(SipBody.class)) {
                    AnnotationValue<SipBody> bodyAnn = arg.getAnnotation(SipBody.class);
                    if (byte[].class.isAssignableFrom(paramType)) {
                        args[i] = request.getBody();
                    } else {
                        args[i] = request.getBodyAsString();
                    }
                    boolean required = bodyAnn != null ? bodyAnn.booleanValue("required").orElse(false) : false;
                    if (args[i] == null && required) {
                        throw new IllegalArgumentException("Missing required SIP body");
                    }
                } else if (String.class.isAssignableFrom(paramType)) {
                    // If unannotated String, check if parameter name corresponds to a header
                    String headerVal = request.getHeaders().get(arg.getName());
                    args[i] = headerVal;
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

                if (SipRequest.class.isAssignableFrom(paramType)) {
                    args[i] = request;
                } else if (SipHeaders.class.isAssignableFrom(paramType)) {
                    args[i] = request.getHeaders();
                } else if (param.isAnnotationPresent(SipCallId.class)) {
                    args[i] = convertValue(request.getCallId(), paramType);
                } else if (param.isAnnotationPresent(SipFrom.class)) {
                    if (SipUri.class.isAssignableFrom(paramType)) {
                        args[i] = SipUri.extractUri(request.getFrom());
                    } else {
                        args[i] = convertValue(request.getFrom(), paramType);
                    }
                } else if (param.isAnnotationPresent(SipTo.class)) {
                    if (SipUri.class.isAssignableFrom(paramType)) {
                        args[i] = SipUri.extractUri(request.getTo());
                    } else {
                        args[i] = convertValue(request.getTo(), paramType);
                    }
                } else if (param.isAnnotationPresent(SipParam.class)) {
                    SipParam paramAnn = param.getAnnotation(SipParam.class);
                    String paramName = paramAnn.value().isEmpty() ? param.getName() : paramAnn.value();
                    String paramVal = (request.getUri() != null) ? request.getUri().getParameter(paramName) : null;
                    if (paramVal == null && !paramAnn.defaultValue().isEmpty()) {
                        paramVal = paramAnn.defaultValue();
                    }
                    if (paramVal == null && paramAnn.required()) {
                        throw new IllegalArgumentException("Missing required SIP URI parameter: " + paramName);
                    }
                    args[i] = convertValue(paramVal, paramType);
                } else if (SipUri.class.isAssignableFrom(paramType)) {
                    args[i] = request.getUri();
                } else if (ViaHeader.class.isAssignableFrom(paramType)) {
                    String via = request.getVia();
                    args[i] = (via != null) ? ViaHeader.parse(via) : null;
                } else if (InetSocketAddress.class.isAssignableFrom(paramType)) {
                    args[i] = request.getRemoteAddress();
                } else if (SipSession.class.isAssignableFrom(paramType)) {
                    args[i] = sessionManager.getOrCreateSession(request.getCallId(), request.getRemoteAddress());
                } else if (param.isAnnotationPresent(SipHeader.class)) {
                    SipHeader headerAnn = param.getAnnotation(SipHeader.class);
                    String headerName = headerAnn.value().isEmpty() ? param.getName() : headerAnn.value();
                    String headerVal = request.getHeaders().get(headerName);
                    if (headerVal == null && !headerAnn.defaultValue().isEmpty()) {
                        headerVal = headerAnn.defaultValue();
                    }
                    if (headerVal == null && headerAnn.required()) {
                        throw new IllegalArgumentException("Missing required SIP header: " + headerName);
                    }
                    if (SipUri.class.isAssignableFrom(paramType)) {
                        args[i] = SipUri.extractUri(headerVal);
                    } else if (ViaHeader.class.isAssignableFrom(paramType)) {
                        args[i] = (headerVal != null) ? ViaHeader.parse(headerVal) : null;
                    } else {
                        args[i] = convertValue(headerVal, paramType);
                    }
                } else if (param.isAnnotationPresent(SipBody.class)) {
                    SipBody bodyAnn = param.getAnnotation(SipBody.class);
                    if (byte[].class.isAssignableFrom(paramType)) {
                        args[i] = request.getBody();
                    } else {
                        args[i] = request.getBodyAsString();
                    }
                    if (args[i] == null && bodyAnn.required()) {
                        throw new IllegalArgumentException("Missing required SIP body");
                    }
                } else if (String.class.isAssignableFrom(paramType)) {
                    String headerVal = request.getHeaders().get(param.getName());
                    args[i] = headerVal;
                } else {
                    args[i] = null;
                }
            }

            return fallbackMethod.invoke(controllerInstance, args);
        }

        throw new IllegalStateException("No executable method or fallback method available for route");
    }

    public SipMethod getMethod() {
        return method;
    }

    public String getCustomMethod() {
        return customMethod;
    }

    public String getUriPattern() {
        return uriPattern;
    }

    public boolean matchesUri(SipRequest request) {
        if (!uriPattern.isEmpty()) {
            String uriStr = request.getUri() != null ? request.getUri().toString() : "";
            String userStr = (request.getUri() != null && request.getUri().getUser() != null) ? request.getUri().getUser() : "";
            return uriStr.contains(uriPattern) || userStr.startsWith(uriPattern);
        }
        return true;
    }

    public ExecutableMethod<Object, ?> getExecutableMethod() {
        return executableMethod;
    }

    public Method getHandlerMethod() {
        if (executableMethod != null) {
            return executableMethod.getTargetMethod();
        }
        return fallbackMethod;
    }

    public Object getControllerInstance() {
        return controllerInstance;
    }

    private static Object convertValue(String val, Class<?> targetType) {
        if (val == null) {
            if (targetType == boolean.class) return false;
            if (targetType == int.class) return 0;
            if (targetType == long.class) return 0L;
            if (targetType == double.class) return 0.0;
            return null;
        }
        if (targetType == String.class || targetType == Object.class) {
            return val;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(val.trim());
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(val.trim());
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(val.trim());
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(val.trim());
        }
        return val;
    }
}
