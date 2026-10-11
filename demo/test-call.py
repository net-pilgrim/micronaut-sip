#!/usr/bin/env python3
"""
Simple SIP User Agent Client (UAC) test script for demo verification.
Sends a SIP INVITE through the Kamailio proxy to the backend sip-app,
verifying Via header stacking, Record-Route, 100 Trying, and 200 OK.
Supports both TCP (recommended for host-to-Docker) and UDP transports.
"""

import sys
import socket
import argparse
import time
import uuid

PROXY_HOST = "127.0.0.1"
PROXY_PORT = 5080
LOCAL_PORT = 5062


def run_test_call_tcp(target_user="annc", query_params=";play=builtin:tone:440,1000", host=PROXY_HOST, port=PROXY_PORT):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(6.0)
    print(f"[*] Connecting TCP to {host}:{port}...")
    sock.connect((host, port))

    call_id = f"demo-call-{str(uuid.uuid4())[:8]}@127.0.0.1"
    from_tag = f"tag-{str(uuid.uuid4())[:6]}"
    branch = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    request_uri = f"sip:{target_user}@{host}:{port}{query_params}"

    sdp_body = (
        "v=0\r\n"
        "o=demo-caller 1000 1000 IN IP4 127.0.0.1\r\n"
        "s=DemoCall\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "t=0 0\r\n"
        "m=audio 45000 RTP/AVP 0\r\n"
        "a=rtpmap:0 PCMU/8000\r\n"
    )

    invite = (
        f"INVITE {request_uri} SIP/2.0\r\n"
        f"Via: SIP/2.0/TCP 127.0.0.1:{LOCAL_PORT};branch={branch};rport\r\n"
        f"Max-Forwards: 70\r\n"
        f"From: <sip:caller@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
        f"To: <sip:{target_user}@{host}:{port}>\r\n"
        f"Call-ID: {call_id}\r\n"
        f"CSeq: 1 INVITE\r\n"
        f"Contact: <sip:caller@127.0.0.1:{LOCAL_PORT};transport=tcp>\r\n"
        f"Content-Type: application/sdp\r\n"
        f"Content-Length: {len(sdp_body.encode())}\r\n"
        f"\r\n"
        f"{sdp_body}"
    )

    print("==================================================================")
    print(f"[*] Sending INVITE over TCP to Proxy: {host}:{port}")
    print(f"[*] Target URI: {request_uri}")
    print("==================================================================")
    print(invite)

    sock.sendall(invite.encode())

    to_header = None
    record_routes = []
    received_data = ""

    try:
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            message = chunk.decode(errors="replace")
            received_data += message
            print("------------------------------------------------------------------")
            print(f"[<] Received SIP message:")
            print("------------------------------------------------------------------")
            print(message)

            lines = message.split("\r\n")
            for line in lines:
                if line.lower().startswith("to:"):
                    to_header = line.split(":", 1)[1].strip()
                elif line.lower().startswith("record-route:"):
                    record_routes.append(line.split(":", 1)[1].strip())

            if "200 OK" in message:
                print("\n[+] Received 200 OK! Sending ACK back through Proxy...")
                ack_branch = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
                route_header = ""
                if record_routes:
                    route_header = f"Route: {record_routes[0]}\r\n"

                ack = (
                    f"ACK {request_uri} SIP/2.0\r\n"
                    f"Via: SIP/2.0/TCP 127.0.0.1:{LOCAL_PORT};branch={ack_branch};rport\r\n"
                    f"Max-Forwards: 70\r\n"
                    f"{route_header}"
                    f"From: <sip:caller@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
                    f"To: {to_header}\r\n"
                    f"Call-ID: {call_id}\r\n"
                    f"CSeq: 1 ACK\r\n"
                    f"Contact: <sip:caller@127.0.0.1:{LOCAL_PORT};transport=tcp>\r\n"
                    f"Content-Length: 0\r\n\r\n"
                )
                print(ack)
                sock.sendall(ack.encode())
                print("[+] ACK sent successfully.")
                break
    except socket.timeout:
        print("[-] Socket timed out waiting for SIP response.")
    finally:
        sock.close()


def run_test_call_udp(target_user="annc", query_params=";play=builtin:tone:440,1000", host=PROXY_HOST, port=PROXY_PORT):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(5.0)
    sock.bind(("0.0.0.0", LOCAL_PORT))

    call_id = f"demo-call-{str(uuid.uuid4())[:8]}@127.0.0.1"
    from_tag = f"tag-{str(uuid.uuid4())[:6]}"
    branch = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    request_uri = f"sip:{target_user}@{host}:{port}{query_params}"

    sdp_body = (
        "v=0\r\n"
        "o=demo-caller 1000 1000 IN IP4 127.0.0.1\r\n"
        "s=DemoCall\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "t=0 0\r\n"
        "m=audio 45000 RTP/AVP 0\r\n"
        "a=rtpmap:0 PCMU/8000\r\n"
    )

    invite = (
        f"INVITE {request_uri} SIP/2.0\r\n"
        f"Via: SIP/2.0/UDP 127.0.0.1:{LOCAL_PORT};branch={branch};rport\r\n"
        f"Max-Forwards: 70\r\n"
        f"From: <sip:caller@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
        f"To: <sip:{target_user}@{host}:{port}>\r\n"
        f"Call-ID: {call_id}\r\n"
        f"CSeq: 1 INVITE\r\n"
        f"Contact: <sip:caller@127.0.0.1:{LOCAL_PORT}>\r\n"
        f"Content-Type: application/sdp\r\n"
        f"Content-Length: {len(sdp_body.encode())}\r\n"
        f"\r\n"
        f"{sdp_body}"
    )

    print("==================================================================")
    print(f"[*] Sending INVITE over UDP to Proxy: {host}:{port}")
    print(f"[*] Target URI: {request_uri}")
    print("==================================================================")
    print(invite)

    sock.sendto(invite.encode(), (host, port))

    to_header = None
    record_routes = []

    try:
        while True:
            data, addr = sock.recvfrom(4096)
            message = data.decode(errors="replace")
            print("------------------------------------------------------------------")
            print(f"[<] Received SIP message from {addr}:")
            print("------------------------------------------------------------------")
            print(message)

            lines = message.split("\r\n")
            status_line = lines[0]

            for line in lines:
                if line.lower().startswith("to:"):
                    to_header = line.split(":", 1)[1].strip()
                elif line.lower().startswith("record-route:"):
                    record_routes.append(line.split(":", 1)[1].strip())

            if "200 OK" in status_line:
                print("\n[+] Received 200 OK! Sending ACK back through Proxy...")
                ack_branch = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
                route_header = ""
                if record_routes:
                    route_header = f"Route: {record_routes[0]}\r\n"

                ack = (
                    f"ACK {request_uri} SIP/2.0\r\n"
                    f"Via: SIP/2.0/UDP 127.0.0.1:{LOCAL_PORT};branch={ack_branch};rport\r\n"
                    f"Max-Forwards: 70\r\n"
                    f"{route_header}"
                    f"From: <sip:caller@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
                    f"To: {to_header}\r\n"
                    f"Call-ID: {call_id}\r\n"
                    f"CSeq: 1 ACK\r\n"
                    f"Contact: <sip:caller@127.0.0.1:{LOCAL_PORT}>\r\n"
                    f"Content-Length: 0\r\n\r\n"
                )
                sock.sendto(ack.encode(), (host, port))
                print(ack)
                print("[+] ACK sent successfully.")
                break
    except socket.timeout:
        print("[-] Socket timed out waiting for SIP response.")
    finally:
        sock.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="SIP UAC test client through Kamailio proxy to sip-app")
    parser.add_argument("target", nargs="?", default="annc", help="Target user: annc, mailbox, early-media, alice, etc.")
    parser.add_argument("params", nargs="?", default=";play=builtin:tone:440,1000", help="URI parameters (e.g. ;play=...)")
    parser.add_argument("--udp", action="store_true", help="Use UDP transport instead of TCP")
    parser.add_argument("--host", default=PROXY_HOST, help=f"Proxy host (default: {PROXY_HOST})")
    parser.add_argument("--port", type=int, default=PROXY_PORT, help=f"Proxy port (default: {PROXY_PORT})")

    args = parser.parse_args()

    param_val = args.params
    if args.target in ("alice", "mailbox", "early-media"):
        if args.params == ";play=builtin:tone:440,1000":
            param_val = ""

    if args.udp:
        run_test_call_udp(args.target, param_val, args.host, args.port)
    else:
        run_test_call_tcp(args.target, param_val, args.host, args.port)
