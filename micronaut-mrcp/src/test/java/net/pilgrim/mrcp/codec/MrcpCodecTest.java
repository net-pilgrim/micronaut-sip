package net.pilgrim.mrcp.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.resource.MrcpChannelId;
import net.pilgrim.mrcp.resource.MrcpResourceType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MrcpCodecTest {

    @Test
    void testRequestSerializeAndParse() {
        MrcpRequest req = new MrcpRequest(MrcpMethod.SPEAK, 1001);
        MrcpChannelId channelId = new MrcpChannelId("test-session-1", MrcpResourceType.SPEECHSYNTH);
        req.getHeaders().setChannelIdentifier(channelId);
        req.getHeaders().setContentType("text/plain");
        req.getHeaders().setVoiceName("mistral-en-1");
        req.getHeaders().setKillOnBargeIn(true);
        req.setBody("Hello world from Mistral Audio", "text/plain");

        byte[] serialized = MrcpMessageSerializer.serialize(req);
        String text = new String(serialized, StandardCharsets.UTF_8);

        assertTrue(text.startsWith("MRCP/2.0 "));
        assertTrue(text.contains(" SPEAK 1001\r\n"));
        assertTrue(text.contains("Channel-Identifier: test-session-1@speechsynth\r\n"));
        assertTrue(text.contains("Voice-Name: mistral-en-1\r\n"));
        assertTrue(text.contains("Kill-On-Barge-In: true\r\n"));
        assertTrue(text.endsWith("Hello world from Mistral Audio"));

        // Validate messageLength matches exactly the octet count
        assertEquals(serialized.length, req.getMessageLength());

        // Parse back
        MrcpMessage parsed = MrcpMessageParser.parse(serialized);
        assertInstanceOf(MrcpRequest.class, parsed);
        MrcpRequest parsedReq = (MrcpRequest) parsed;

        assertEquals(MrcpMethod.SPEAK, parsedReq.getMethod());
        assertEquals(1001, parsedReq.getRequestId());
        assertEquals("test-session-1@speechsynth", parsedReq.getHeaders().getChannelIdentifier().toString());
        assertEquals("mistral-en-1", parsedReq.getHeaders().getVoiceName());
        assertTrue(parsedReq.getHeaders().isKillOnBargeIn());
        assertEquals("Hello world from Mistral Audio", parsedReq.getBodyAsString());
    }

    @Test
    void testResponseSerializeAndParse() {
        MrcpResponse resp = new MrcpResponse(1001, MrcpStatusCode.IN_PROGRESS, MrcpRequestState.IN_PROGRESS);
        MrcpChannelId channelId = new MrcpChannelId("test-session-1", MrcpResourceType.SPEECHSYNTH);
        resp.getHeaders().setChannelIdentifier(channelId);

        byte[] serialized = MrcpMessageSerializer.serialize(resp);
        assertEquals(serialized.length, resp.getMessageLength());

        MrcpMessage parsed = MrcpMessageParser.parse(serialized);
        assertInstanceOf(MrcpResponse.class, parsed);
        MrcpResponse parsedResp = (MrcpResponse) parsed;

        assertEquals(1001, parsedResp.getRequestId());
        assertEquals(202, parsedResp.getStatusCode());
        assertEquals(MrcpRequestState.IN_PROGRESS, parsedResp.getRequestState());
        assertEquals("test-session-1@speechsynth", parsedResp.getHeaders().getChannelIdentifier().toString());
    }

    @Test
    void testEventSerializeAndParse() {
        MrcpEvent event = new MrcpEvent(MrcpEventName.SPEAK_COMPLETE, 1001, MrcpRequestState.COMPLETE);
        MrcpChannelId channelId = new MrcpChannelId("test-session-1", MrcpResourceType.SPEECHSYNTH);
        event.getHeaders().setChannelIdentifier(channelId);
        event.getHeaders().setCompletionCause("000 normal");

        byte[] serialized = MrcpMessageSerializer.serialize(event);
        assertEquals(serialized.length, event.getMessageLength());

        MrcpMessage parsed = MrcpMessageParser.parse(serialized);
        assertInstanceOf(MrcpEvent.class, parsed);
        MrcpEvent parsedEvent = (MrcpEvent) parsed;

        assertEquals(MrcpEventName.SPEAK_COMPLETE, parsedEvent.getEvent());
        assertEquals(1001, parsedEvent.getRequestId());
        assertEquals(MrcpRequestState.COMPLETE, parsedEvent.getRequestState());
        assertEquals("000 normal", parsedEvent.getHeaders().getCompletionCause());
    }

    @Test
    void testNettyEmbeddedPipeline() {
        EmbeddedChannel channel = new EmbeddedChannel(new MrcpNettyDecoder(), new MrcpNettyEncoder());

        MrcpRequest req = new MrcpRequest(MrcpMethod.RECOGNIZE, 2002);
        req.getHeaders().setChannelIdentifier(new MrcpChannelId("recog-ch-1", MrcpResourceType.SPEECHRECOG));
        req.setBody("builtin:grammar/boolean", "text/uri-list");

        // Encode to channel
        assertTrue(channel.writeOutbound(req));
        ByteBuf encodedBuf = channel.readOutbound();
        assertNotNull(encodedBuf);

        // Feed back to decode
        assertTrue(channel.writeInbound(encodedBuf));
        MrcpMessage decoded = channel.readInbound();
        assertNotNull(decoded);
        assertInstanceOf(MrcpRequest.class, decoded);

        MrcpRequest decodedReq = (MrcpRequest) decoded;
        assertEquals(MrcpMethod.RECOGNIZE, decodedReq.getMethod());
        assertEquals(2002, decodedReq.getRequestId());
        assertEquals("builtin:grammar/boolean", decodedReq.getBodyAsString());
    }
}
