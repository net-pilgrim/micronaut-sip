FROM alpine:latest

RUN apk add --no-cache kamailio kamailio-json kamailio-utils

COPY kamailio.cfg /etc/kamailio/kamailio.cfg

EXPOSE 5060/udp 5060/tcp

# Run Kamailio in foreground with log output to stderr
ENTRYPOINT ["kamailio", "-DD", "-E", "-f", "/etc/kamailio/kamailio.cfg"]
