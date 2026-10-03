package net.pilgrim.sip.rtp.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for Netty RTP media streaming.
 */
@ConfigurationProperties("sip.rtp")
public class RtpConfiguration {

    private int portRangeStart = 10000;
    private int portRangeEnd = 20000;
    private int workerThreads = 4;
    private String bindAddress = "0.0.0.0";
    private boolean symmetricRtp = true;

    public int getPortRangeStart() {
        return portRangeStart;
    }

    public void setPortRangeStart(int portRangeStart) {
        this.portRangeStart = portRangeStart;
    }

    public int getPortRangeEnd() {
        return portRangeEnd;
    }

    public void setPortRangeEnd(int portRangeEnd) {
        this.portRangeEnd = portRangeEnd;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public boolean isSymmetricRtp() {
        return symmetricRtp;
    }

    public void setSymmetricRtp(boolean symmetricRtp) {
        this.symmetricRtp = symmetricRtp;
    }
}
