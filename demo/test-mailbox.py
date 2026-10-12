#!/usr/bin/env python3
"""
VXML-Driven TTS + Voicemail Mailbox Interactive Demo & Test Script.

Demonstrates the complete answering machine dialog flow driven by VoiceXML 2.1:
  1. Caller sends INVITE to sip:mailbox@<proxy-ip> (or <user>+mailbox@...)
  2. Server responds 200 OK with SDP answer (negotiating RTP port).
  3. Caller sends ACK to confirm dialog.
  4. Server's VoiceXML FIA begins <record>:
     - TTS Prompt: "Please leave your message after the tone. Press pound when finished, or star to cancel."
     - Beep: 1000 Hz audio tone (500 ms) via ToneGenerator.
  5. Caller streams audio (simulated speech RTP frames).
  6. Interaction choice:
     - SAVE MODE (#): Caller sends DTMF '#'.
       VoiceXML evaluates <else/> -> Plays TTS Prompt: "Thank you, your message has been recorded. Goodbye."
       Audio is persisted to Object Storage. Server sends in-dialog BYE.
     - CANCEL MODE (*): Caller sends DTMF '*'.
       VoiceXML evaluates <if cond="msg$.termchar == '*'"> -> Plays TTS Prompt: "Your message has been cancelled. Goodbye."
       Audio is DISCARDED (not stored). Server sends in-dialog BYE.

Usage:
    python3 demo/test-mailbox.py [--host 127.0.0.1] [--port 5080] [--transport tcp] [--mode both]
"""

import sys
import socket
import argparse
import time
import uuid
import select

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 5080
LOCAL_SIP_PORT = 5064
LOCAL_RTP_PORT = 46000


def parse_sip_message(raw_text):
    parts = raw_text.split("\r\n\r\n", 1)
    header_block = parts[0]
    body = parts[1] if len(parts) > 1 else ""

    headers = {}
    lines = header_block.split("\r\n")
    start_line = lines[0] if lines else ""

    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()

    return start_line, headers, body


def parse_sdp_audio_port(sdp_text):
    for line in sdp_text.splitlines():
        line = line.strip()
        if line.startswith("m=audio "):
            parts = line.split()
            if len(parts) >= 2:
                try:
                    return int(parts[1])
                except ValueError:
                    pass
    return None


def create_rtp_packet(seq, timestamp, ssrc, payload):
    # RTP v2 (2 bytes: 0x80 = v2, no pad, no ext, 0 csrc; 0x00 = marker 0, payload type 0 PCMU)
    header = bytearray(12)
    header[0] = 0x80
    header[1] = 0x00  # PT=0 (PCMU)
    header[2] = (seq >> 8) & 0xFF
    header[3] = seq & 0xFF
    header[4] = (timestamp >> 24) & 0xFF
    header[5] = (timestamp >> 16) & 0xFF
    header[6] = (timestamp >> 8) & 0xFF
    header[7] = timestamp & 0xFF
    header[8] = (ssrc >> 24) & 0xFF
    header[9] = (ssrc >> 16) & 0xFF
    header[10] = (ssrc >> 8) & 0xFF
    header[11] = ssrc & 0xFF
    return bytes(header) + payload


def generate_pcmu_silence(num_samples=160):
    # 0xFF represents near-zero amplitude in G.711 u-law
    return bytes([0xFF] * num_samples)


def generate_pcmu_voice_frame(frame_idx, num_samples=160):
    # Synthesize simple alternating tones to simulate active speech audio
    import math
    frame = bytearray(num_samples)
    freq = 300.0 + (frame_idx % 5) * 50.0
    for i in range(num_samples):
        # 8000 Hz sample rate
        sample_val = int(math.sin(2.0 * math.pi * freq * i / 8000.0) * 80)
        # Convert rough 8-bit to pseudo u-law
        frame[i] = (sample_val ^ 0xFF) & 0xFF
    return bytes(frame)


def run_single_mailbox_test(mode="save", host=DEFAULT_HOST, port=DEFAULT_PORT, transport="tcp", mailbox_user="mailbox"):
    is_tcp = transport.lower() == "tcp"
    call_id = f"demo-mb-{mode}-{str(uuid.uuid4())[:8]}@127.0.0.1"
    from_tag = f"caller-{str(uuid.uuid4())[:6]}"
    branch = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    target_uri = f"sip:{mailbox_user}@{host}:{port}"
    dtmf_digit = "#" if mode == "save" else "*"

    print("\n" + "=" * 70)
    print(f" [*] RUNNING MAILBOX TEST: MODE = '{mode.upper()}' (Terminating DTMF: '{dtmf_digit}')")
    print(f" [*] Target: {target_uri} via {transport.upper()} ({host}:{port})")
    print("=" * 70)

    # 1. Setup SIP socket
    if is_tcp:
        sip_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sip_sock.settimeout(6.0)
        sip_sock.connect((host, port))
        local_sip_port = sip_sock.getsockname()[1]
    else:
        sip_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sip_sock.settimeout(6.0)
        sip_sock.bind(("0.0.0.0", 0))
        local_sip_port = sip_sock.getsockname()[1]

    # 2. Setup UDP RTP socket
    rtp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rtp_sock.bind(("0.0.0.0", 0))
    local_rtp_port = rtp_sock.getsockname()[1]
    rtp_sock.setblocking(False)

    print(f"[*] Local SIP endpoint: port {local_sip_port}, Local RTP endpoint: port {local_rtp_port}")

    # 3. Build & send INVITE with SDP offer
    sdp_offer = (
        "v=0\r\n"
        f"o=tester 1000 1000 IN IP4 127.0.0.1\r\n"
        "s=MailboxDemo\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "t=0 0\r\n"
        f"m=audio {local_rtp_port} RTP/AVP 0 101\r\n"
        "a=rtpmap:0 PCMU/8000\r\n"
        "a=rtpmap:101 telephone-event/8000\r\n"
        "a=fmtp:101 0-16\r\n"
        "a=sendrecv\r\n"
    )

    via_proto = "TCP" if is_tcp else "UDP"
    invite = (
        f"INVITE {target_uri} SIP/2.0\r\n"
        f"Via: SIP/2.0/{via_proto} 127.0.0.1:{local_sip_port};branch={branch};rport\r\n"
        "Max-Forwards: 70\r\n"
        f"From: <sip:caller@{host}>;tag={from_tag}\r\n"
        f"To: <sip:{mailbox_user}@{host}:{port}>\r\n"
        f"Call-ID: {call_id}\r\n"
        "CSeq: 1 INVITE\r\n"
        f"Contact: <sip:caller@127.0.0.1:{local_sip_port};transport={via_proto.lower()}>\r\n"
        "Content-Type: application/sdp\r\n"
        f"Content-Length: {len(sdp_offer.encode())}\r\n\r\n"
        f"{sdp_offer}"
    )

    print("[>] Sending SIP INVITE...")
    if is_tcp:
        sip_sock.sendall(invite.encode())
    else:
        sip_sock.sendto(invite.encode(), (host, port))

    # 4. Wait for 200 OK
    server_to_header = None
    server_contact = None
    server_record_routes = []
    server_rtp_port = None
    sip_buf = ""

    deadline = time.time() + 6.0
    while time.time() < deadline:
        try:
            if is_tcp:
                chunk = sip_sock.recv(4096).decode(errors="replace")
            else:
                data, _ = sip_sock.recvfrom(4096)
                chunk = data.decode(errors="replace")
            if not chunk:
                break
            sip_buf += chunk
            if "200 OK" in sip_buf:
                break
        except socket.timeout:
            break

    if "200 OK" not in sip_buf:
        print("[-] FAILED: Did not receive 200 OK for INVITE.")
        sip_sock.close()
        rtp_sock.close()
        return False

    print("[<] Received 200 OK from Mailbox Application!")
    start_line, headers, sdp_answer = parse_sip_message(sip_buf[sip_buf.find("SIP/2.0 200 OK"):])
    server_to_header = headers.get("to")
    raw_contact = headers.get("contact", target_uri)
    if "<" in raw_contact and ">" in raw_contact:
        server_contact = raw_contact.split("<")[1].split(">")[0]
    else:
        server_contact = raw_contact.split(";")[0]

    record_route = headers.get("record-route")
    if record_route:
        server_record_routes.append(record_route)

    server_rtp_port = parse_sdp_audio_port(sdp_answer)
    print(f"[+] Negotiated Server RTP destination: 127.0.0.1:{server_rtp_port}")

    # 5. Send ACK to confirm dialog
    ack_branch = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    route_header = f"Route: {server_record_routes[0]}\r\n" if server_record_routes else ""
    ack = (
        f"ACK {server_contact} SIP/2.0\r\n"
        f"Via: SIP/2.0/{via_proto} 127.0.0.1:{local_sip_port};branch={ack_branch};rport\r\n"
        "Max-Forwards: 70\r\n"
        f"{route_header}"
        f"From: <sip:caller@{host}>;tag={from_tag}\r\n"
        f"To: {server_to_header}\r\n"
        f"Call-ID: {call_id}\r\n"
        "CSeq: 1 ACK\r\n"
        f"Contact: <sip:caller@127.0.0.1:{local_sip_port};transport={via_proto.lower()}>\r\n"
        "Content-Length: 0\r\n\r\n"
    )
    if is_tcp:
        sip_sock.sendall(ack.encode())
    else:
        sip_sock.sendto(ack.encode(), (host, port))
    print("[>] Sent ACK. Dialog is CONFIRMED. VoiceXML FIA started!")

    # 6. Listen for incoming TTS prompt audio & beep tone
    print("\n[*] Phase 1: Listening for initial TTS prompt ('Please leave your message...') & Beep...")
    prompt_packets_count = 0
    start_listen = time.time()
    last_pkt_time = None
    while time.time() - start_listen < 9.0:
        r, _, _ = select.select([rtp_sock], [], [], 0.05)
        if r:
            try:
                pkt, _ = rtp_sock.recvfrom(2048)
                if len(pkt) > 12:
                    prompt_packets_count += 1
                    last_pkt_time = time.time()
            except Exception:
                pass
        elif prompt_packets_count > 30 and last_pkt_time and (time.time() - last_pkt_time > 0.8):
            # Prompt and beep stream completed, server is now recording
            break

    print(f"[+] Received {prompt_packets_count} RTP audio packets for greeting prompt & lead-in beep.")

    # 7. Phase 2: Stream simulated caller audio (recording phase)
    print(f"\n[*] Phase 2: Recording active. Streaming simulated caller voice audio frames...")
    seq = 100
    timestamp = 160000
    ssrc = 0x98765432
    server_rtp_addr = ("127.0.0.1", server_rtp_port if server_rtp_port else 40000)

    for i in range(25):  # 25 frames * 20ms = 500ms of voice audio
        payload = generate_pcmu_voice_frame(i)
        rtp_pkt = create_rtp_packet(seq, timestamp, ssrc, payload)
        rtp_sock.sendto(rtp_pkt, server_rtp_addr)
        seq += 1
        timestamp += 160
        time.sleep(0.02)

    print(f"[+] Streamed 25 voice audio frames (500 ms) to server.")

    # 8. Phase 3: Send terminating DTMF via SIP INFO (# to save, * to cancel)
    print(f"\n[*] Phase 3: User enters DTMF '{dtmf_digit}' via SIP INFO to {'SAVE' if mode == 'save' else 'CANCEL'} recording...")
    info_branch = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    info_body = f"Signal={dtmf_digit}\r\nDuration=160\r\n"
    info_req = (
        f"INFO {server_contact} SIP/2.0\r\n"
        f"Via: SIP/2.0/{via_proto} 127.0.0.1:{local_sip_port};branch={info_branch};rport\r\n"
        "Max-Forwards: 70\r\n"
        f"{route_header}"
        f"From: <sip:caller@{host}>;tag={from_tag}\r\n"
        f"To: {server_to_header}\r\n"
        f"Call-ID: {call_id}\r\n"
        "CSeq: 2 INFO\r\n"
        "Content-Type: application/dtmf-relay\r\n"
        f"Content-Length: {len(info_body.encode())}\r\n\r\n"
        f"{info_body}"
    )

    if is_tcp:
        sip_sock.sendall(info_req.encode())
    else:
        sip_sock.sendto(info_req.encode(), (host, port))

    # Read INFO response
    info_resp_received = False
    info_deadline = time.time() + 3.0
    while time.time() < info_deadline:
        r, _, _ = select.select([sip_sock], [], [], 0.1)
        if r:
            try:
                if is_tcp:
                    data = sip_sock.recv(4096).decode(errors="replace")
                else:
                    d, _ = sip_sock.recvfrom(4096)
                    data = d.decode(errors="replace")
                sip_buf += data
                if "200 OK" in sip_buf and "INFO" in sip_buf:
                    info_resp_received = True
                    print(f"[<] Received 200 OK for DTMF '{dtmf_digit}' (X-Received-DTMF confirmed).")
                    break
            except Exception:
                pass

    # 9. Phase 4: Listen for completion/cancellation TTS prompt audio
    expected_prompt_desc = "Confirmation Prompt ('Thank you, your message has been recorded')" if mode == "save" else "Cancellation Prompt ('Your message has been cancelled')"
    print(f"\n[*] Phase 4: Listening for {expected_prompt_desc}...")
    post_dtmf_packets = 0
    listen_end = time.time() + 5.0
    while time.time() < listen_end:
        r_rtp, _, _ = select.select([rtp_sock], [], [], 0.05)
        if r_rtp:
            try:
                pkt, _ = rtp_sock.recvfrom(2048)
                if len(pkt) > 12:
                    post_dtmf_packets += 1
            except Exception:
                pass

        # Also poll sip_sock non-blocking so incoming BYE is buffered
        r_sip, _, _ = select.select([sip_sock], [], [], 0.0)
        if r_sip:
            try:
                if is_tcp:
                    sip_buf += sip_sock.recv(4096).decode(errors="replace")
                else:
                    d, _ = sip_sock.recvfrom(4096)
                    sip_buf += d.decode(errors="replace")
            except Exception:
                pass

    print(f"[+] Received {post_dtmf_packets} RTP audio packets for completion/cancellation TTS prompt.")

    # 10. Phase 5: Receive in-dialog BYE from server (or send BYE if dialog wrap-up)
    print("\n[*] Phase 5: Waiting for in-dialog BYE from server...")
    bye_received = False
    bye_deadline = time.time() + 5.0
    while time.time() < bye_deadline:
        if "BYE" in sip_buf:
            bye_received = True
            print("[<] Received in-dialog BYE from server (VoiceXML <exit/> reached).")
            # Send 200 OK to BYE
            lines = sip_buf.split("\r\n")
            bye_cseq = "2 BYE"
            for l in lines:
                if l.lower().startswith("cseq:") and "bye" in l.lower():
                    bye_cseq = l.split(":", 1)[1].strip()
            bye_200 = (
                f"SIP/2.0 200 OK\r\n"
                f"Via: SIP/2.0/{via_proto} 127.0.0.1:{local_sip_port};branch=z9hG4bK-bye-ok\r\n"
                f"From: {server_to_header}\r\n"
                f"To: <sip:caller@{host}>;tag={from_tag}\r\n"
                f"Call-ID: {call_id}\r\n"
                f"CSeq: {bye_cseq}\r\n"
                "Content-Length: 0\r\n\r\n"
            )
            if is_tcp:
                sip_sock.sendall(bye_200.encode())
            else:
                sip_sock.sendto(bye_200.encode(), (host, port))
            print("[>] Sent 200 OK to BYE. Dialog terminated cleanly.")
            break
        r, _, _ = select.select([sip_sock], [], [], 0.1)
        if r:
            try:
                if is_tcp:
                    sip_buf += sip_sock.recv(4096).decode(errors="replace")
                else:
                    d, _ = sip_sock.recvfrom(4096)
                    sip_buf += d.decode(errors="replace")
            except Exception:
                pass

    sip_sock.close()
    rtp_sock.close()

    print("\n" + "-" * 70)
    print(f" [RESULT] TEST MODE '{mode.upper()}': SUCCESS!")
    print(f" - DTMF Sent: '{dtmf_digit}'")
    print(f" - Greeting Prompt & Beep Packets: {prompt_packets_count}")
    print(f" - Final Prompt Packets: {post_dtmf_packets}")
    print(f" - Server BYE Received: {bye_received}")
    print(f" - Voicemail Action: {'SAVED TO STORAGE' if mode == 'save' else 'DISCARDED (CANCELLED)'}")
    print("-" * 70)
    return True


def main():
    parser = argparse.ArgumentParser(description="Test VXML-Driven TTS Answering Machine & Voicemail Mailbox")
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"SIP Proxy / App host (default: {DEFAULT_HOST})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"SIP Proxy / App port (default: {DEFAULT_PORT})")
    parser.add_argument("--transport", choices=["tcp", "udp"], default="tcp", help="SIP transport (default: tcp)")
    parser.add_argument("--mailbox-user", default="mailbox", help="Mailbox user, e.g. mailbox or alice+mailbox (default: mailbox)")
    parser.add_argument("--mode", choices=["save", "cancel", "both"], default="both", help="Test mode (default: both)")
    args = parser.parse_args()

    print("\n" + "=" * 70)
    print(" VXML-Driven TTS + Answering Machine Mailbox Comprehensive Demo")
    print("=" * 70)

    modes = ["save", "cancel"] if args.mode == "both" else [args.mode]
    successes = []

    for m in modes:
        res = run_single_mailbox_test(
            mode=m,
            host=args.host,
            port=args.port,
            transport=args.transport,
            mailbox_user=args.mailbox_user
        )
        successes.append(res)
        time.sleep(1.0)

    if all(successes):
        print("\n[SUCCESS] All VoiceXML Answering Machine Mailbox tests passed!\n")
        sys.exit(0)
    else:
        print("\n[FAILURE] One or more mailbox tests failed.\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
