package net.pilgrim.sgw.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * TCAP Dialogue Portion (ITU-T Q.773), containing Application Context Name, protocol version, etc.
 */
@Serdeable
@Introspected
public class TcapDialoguePortion {

    @Nullable
    private String applicationContext;

    @Nullable
    private Integer protocolVersion;

    @Nullable
    private String securityContext;

    @Nullable
    private Map<String, Object> userInfo;

    public TcapDialoguePortion() {
        this.userInfo = new LinkedHashMap<>();
    }

    public TcapDialoguePortion(String applicationContext) {
        this.applicationContext = applicationContext;
        this.userInfo = new LinkedHashMap<>();
    }

    public String getApplicationContext() {
        return applicationContext;
    }

    public void setApplicationContext(String applicationContext) {
        this.applicationContext = applicationContext;
    }

    public Integer getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(Integer protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getSecurityContext() {
        return securityContext;
    }

    public void setSecurityContext(String securityContext) {
        this.securityContext = securityContext;
    }

    public Map<String, Object> getUserInfo() {
        return userInfo;
    }

    public void setUserInfo(Map<String, Object> userInfo) {
        this.userInfo = userInfo != null ? new LinkedHashMap<>(userInfo) : new LinkedHashMap<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TcapDialoguePortion that)) return false;
        return Objects.equals(applicationContext, that.applicationContext) &&
                Objects.equals(protocolVersion, that.protocolVersion) &&
                Objects.equals(securityContext, that.securityContext) &&
                Objects.equals(userInfo, that.userInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(applicationContext, protocolVersion, securityContext, userInfo);
    }

    @Override
    public String toString() {
        return "TcapDialoguePortion{" +
                "applicationContext='" + applicationContext + '\'' +
                ", protocolVersion=" + protocolVersion +
                '}';
    }
}
