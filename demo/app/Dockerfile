FROM eclipse-temurin:25-jre

WORKDIR /home/app

# Copies fat shadow JAR containing Micronaut, Netty, SIP, SDP, RTP, and VoiceXML engine
COPY sip-app/build/libs/sip-app-*-all.jar /home/app/application.jar

EXPOSE 5060/udp 5060/tcp 8080/tcp

ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "/home/app/application.jar"]
