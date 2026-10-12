package net.pilgrim.sgw.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * TCAP Component carrying an INVOKE operation, RETURN_RESULT, RETURN_ERROR, or REJECT.
 */
@Serdeable
@Introspected
public class TcapComponent {

    private TcapComponentType type;

    @Nullable
    private Integer invokeId;

    @Nullable
    private Integer linkedId;

    @Nullable
    private String operation;

    @Nullable
    private Map<String, Object> parameters;

    @Nullable
    private String errorCode;

    @Nullable
    private Map<String, Object> errorParameters;

    @Nullable
    private String problem;

    @Nullable
    private String berHex;

    @Nullable
    private String berBase64;

    public TcapComponent() {
        this.parameters = new LinkedHashMap<>();
    }

    public TcapComponent(TcapComponentType type, Integer invokeId, String operation, Map<String, Object> parameters) {
        this.type = type;
        this.invokeId = invokeId;
        this.operation = operation;
        this.parameters = parameters != null ? new LinkedHashMap<>(parameters) : new LinkedHashMap<>();
    }

    public static TcapComponent invoke(int invokeId, String operation, Map<String, Object> parameters) {
        return new TcapComponent(TcapComponentType.INVOKE, invokeId, operation, parameters);
    }

    public static TcapComponent returnResult(int invokeId, String operation, Map<String, Object> parameters) {
        return new TcapComponent(TcapComponentType.RETURN_RESULT_LAST, invokeId, operation, parameters);
    }

    public static TcapComponent returnResult(int invokeId, Map<String, Object> parameters) {
        return new TcapComponent(TcapComponentType.RETURN_RESULT_LAST, invokeId, null, parameters);
    }

    public static TcapComponent returnError(int invokeId, String errorCode, Map<String, Object> errorParameters) {
        TcapComponent comp = new TcapComponent(TcapComponentType.RETURN_ERROR, invokeId, null, null);
        comp.setErrorCode(errorCode);
        comp.setErrorParameters(errorParameters);
        return comp;
    }

    public static TcapComponent reject(Integer invokeId, String problem) {
        TcapComponent comp = new TcapComponent(TcapComponentType.REJECT, invokeId, null, null);
        comp.setProblem(problem);
        return comp;
    }

    public TcapComponentType getType() {
        return type;
    }

    public void setType(TcapComponentType type) {
        this.type = type;
    }

    public Integer getInvokeId() {
        return invokeId;
    }

    public void setInvokeId(Integer invokeId) {
        this.invokeId = invokeId;
    }

    public Integer getLinkedId() {
        return linkedId;
    }

    public void setLinkedId(Integer linkedId) {
        this.linkedId = linkedId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters != null ? new LinkedHashMap<>(parameters) : new LinkedHashMap<>();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Map<String, Object> getErrorParameters() {
        return errorParameters;
    }

    public void setErrorParameters(Map<String, Object> errorParameters) {
        this.errorParameters = errorParameters != null ? new LinkedHashMap<>(errorParameters) : null;
    }

    public String getProblem() {
        return problem;
    }

    public void setProblem(String problem) {
        this.problem = problem;
    }

    public String getBerHex() {
        return berHex;
    }

    public void setBerHex(String berHex) {
        this.berHex = berHex;
    }

    public String getBerBase64() {
        return berBase64;
    }

    public void setBerBase64(String berBase64) {
        this.berBase64 = berBase64;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TcapComponent that)) return false;
        return type == that.type &&
                Objects.equals(invokeId, that.invokeId) &&
                Objects.equals(linkedId, that.linkedId) &&
                Objects.equals(operation, that.operation) &&
                Objects.equals(parameters, that.parameters) &&
                Objects.equals(errorCode, that.errorCode) &&
                Objects.equals(errorParameters, that.errorParameters) &&
                Objects.equals(problem, that.problem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, invokeId, linkedId, operation, parameters, errorCode, errorParameters, problem);
    }

    @Override
    public String toString() {
        return "TcapComponent{" +
                "type=" + type +
                ", invokeId=" + invokeId +
                ", operation='" + operation + '\'' +
                ", parameters=" + parameters +
                ", errorCode='" + errorCode + '\'' +
                ", problem='" + problem + '\'' +
                '}';
    }
}
