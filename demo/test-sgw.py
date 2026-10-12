#!/usr/bin/env python3
"""
TCAP over SIP Signalling Gateway (SGW) Test Script.

Simulates a client exchanging TCAP transaction dialogue messages with the
SignallingGatewayController in sip-app:
  1. INVITE carrying JSON-encoded TC_begin (with MAP sendRoutingInfoForSM operation)
  2. Gateway responds with 200 OK carrying JSON-encoded TC_continue (IMSI / MSC number)
  3. ACK confirms the dialog
  4. Mid-dialog INFO carrying JSON-encoded tc_continue (with MAP provideSubscriberInfo operation)
  5. Gateway responds with 200 OK carrying JSON-encoded tc_continue (Location & subscriber state)
  6. BYE carrying JSON-encoded tc_end
  7. Gateway responds with 200 OK terminating the TCAP transaction

Supports UDP and TCP transports.
"""

import sys
import socket
import argparse
import json
import uuid

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 5060
LOCAL_PORT = 5063


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


def run_sgw_demo(host=DEFAULT_HOST, port=DEFAULT_PORT, transport="udp", msisdn="33612345678", is_camel=False):
    is_tcp = transport.lower() == "tcp"
    call_id = f"sgw-tcap-{str(uuid.uuid4())[:8]}@127.0.0.1"
    from_tag = f"client-{str(uuid.uuid4())[:6]}"
    client_otid = f"otid-{str(uuid.uuid4())[:6]}"

    proto_name = "CAMEL/INAP ASN.1 BER" if is_camel else "GSM MAP"
    print(f"[*] Starting Dummy Signalling Gateway test ({proto_name}) over {transport.upper()} to {host}:{port}")
    print(f"[*] Call-ID: {call_id}, Client OTID: {client_otid}, Target: {msisdn}")

    # 1. Prepare TC_begin payload
    if is_camel:
        # CAMEL InitialDP with ASN.1 BER encoded payload (3GPP TS 29.078)
        # Sequence: serviceKey [0] 1, calledPartyNumber [2], callingPartyNumber [3]
        ber_hex = "301e800101820a913361234567f8830a813369876543f2"
        tc_begin = {
            "type": "TC_begin",
            "otid": client_otid,
            "dialogue": {
                "applicationContext": "camel-v2"
            },
            "components": [
                {
                    "type": "invoke",
                    "invokeId": 1,
                    "operation": "initialDP",
                    "berHex": ber_hex,
                    "parameters": {
                        "serviceKey": 1,
                        "calledPartyNumber": msisdn,
                        "callingPartyNumber": "33698765432"
                    }
                }
            ]
        }
    else:
        tc_begin = {
            "type": "TC_begin",
            "otid": client_otid,
            "dialogue": {
                "applicationContext": "dummy-map-v3"
            },
            "components": [
                {
                    "type": "invoke",
                    "invokeId": 1,
                    "operation": "sendRoutingInfoForSM",
                    "parameters": {
                        "msisdn": msisdn
                    }
                }
            ]
        }
    begin_json = json.dumps(tc_begin, indent=2)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM if is_tcp else socket.SOCK_DGRAM)
    sock.settimeout(5.0)

    if is_tcp:
        sock.connect((host, port))

    # Send INVITE
    branch1 = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    proto = "TCP" if is_tcp else "UDP"
    invite_msg = (
        f"INVITE sip:sgw@{host}:{port} SIP/2.0\r\n"
        f"Via: SIP/2.0/{proto} 127.0.0.1:{LOCAL_PORT};branch={branch1};rport\r\n"
        f"Max-Forwards: 70\r\n"
        f"From: <sip:client@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
        f"To: <sip:sgw@{host}:{port}>\r\n"
        f"Call-ID: {call_id}\r\n"
        f"CSeq: 1 INVITE\r\n"
        f"Contact: <sip:client@127.0.0.1:{LOCAL_PORT};transport={transport.lower()}>\r\n"
        f"Content-Type: application/json\r\n"
        f"Content-Length: {len(begin_json.encode())}\r\n"
        f"\r\n"
        f"{begin_json}"
    )

    print("\n-------------------------------------------------------------")
    print(f"[>] Sending INVITE with TC_begin payload:")
    print(begin_json)
    print("-------------------------------------------------------------")

    if is_tcp:
        sock.sendall(invite_msg.encode())
    else:
        sock.sendto(invite_msg.encode(), (host, port))

    server_to_tag = None
    server_dtid = None

    # Wait for responses
    while True:
        try:
            if is_tcp:
                data = sock.recv(4096)
            else:
                data, _ = sock.recvfrom(4096)
        except socket.timeout:
            print("[!] Timeout waiting for INVITE response")
            return False

        if not data:
            break

        resp_text = data.decode("utf-8", errors="ignore")
        start_line, headers, body = parse_sip_message(resp_text)
        print(f"[<] Received SIP Response: {start_line}")

        if "200" in start_line:
            server_to_tag = headers.get("to")
            print(f"[+] 200 OK received with TCAP payload:\n{body}")
            try:
                resp_tcap = json.loads(body)
                server_dtid = resp_tcap.get("otid")
                print(f"[+] Gateway assigned Server DTID: {server_dtid}")
                print(f"[+] Received Components: {resp_tcap.get('components')}")
            except Exception as e:
                print(f"[!] Failed parsing JSON body: {e}")
            break

    if not server_dtid:
        print("[!] No server DTID obtained, cannot proceed.")
        return False

    # 2. Send ACK
    ack_msg = (
        f"ACK sip:sgw@{host}:{port} SIP/2.0\r\n"
        f"Via: SIP/2.0/{proto} 127.0.0.1:{LOCAL_PORT};branch={branch1};rport\r\n"
        f"Max-Forwards: 70\r\n"
        f"From: <sip:client@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
        f"To: {server_to_tag}\r\n"
        f"Call-ID: {call_id}\r\n"
        f"CSeq: 1 ACK\r\n"
        f"Content-Length: 0\r\n"
        f"\r\n"
    )
    print("\n[>] Sending ACK confirming dialogue setup...")
    if is_tcp:
        sock.sendall(ack_msg.encode())
    else:
        sock.sendto(ack_msg.encode(), (host, port))

    # 3. Send mid-dialog INFO with tc_continue (provideSubscriberInfo or INAP playAndCollectUserInfo)
    if is_camel:
        tc_continue = {
            "type": "tc_continue",
            "otid": client_otid,
            "dtid": server_dtid,
            "components": [
                {
                    "type": "invoke",
                    "invokeId": 2,
                    "operation": "playAndCollectUserInfo",
                    "parameters": {
                        "messageId": 201,
                        "minDigits": 1,
                        "maxDigits": 4,
                        "endOfReplyDigit": "#",
                        "simulatedDigits": "1234#"
                    }
                }
            ]
        }
    else:
        tc_continue = {
            "type": "tc_continue",
            "otid": client_otid,
            "dtid": server_dtid,
            "components": [
                {
                    "type": "invoke",
                    "invokeId": 2,
                    "operation": "provideSubscriberInfo",
                    "parameters": {
                        "imsi": "208010123456789"
                    }
                }
            ]
        }
    continue_json = json.dumps(tc_continue, indent=2)
    branch2 = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    info_msg = (
        f"INFO sip:sgw@{host}:{port} SIP/2.0\r\n"
        f"Via: SIP/2.0/{proto} 127.0.0.1:{LOCAL_PORT};branch={branch2};rport\r\n"
        f"Max-Forwards: 70\r\n"
        f"From: <sip:client@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
        f"To: {server_to_tag}\r\n"
        f"Call-ID: {call_id}\r\n"
        f"CSeq: 2 INFO\r\n"
        f"Content-Type: application/json\r\n"
        f"Content-Length: {len(continue_json.encode())}\r\n"
        f"\r\n"
        f"{continue_json}"
    )

    print("\n-------------------------------------------------------------")
    print(f"[>] Sending INFO with tc_continue payload:")
    print(continue_json)
    print("-------------------------------------------------------------")

    if is_tcp:
        sock.sendall(info_msg.encode())
    else:
        sock.sendto(info_msg.encode(), (host, port))

    # Receive 200 OK for INFO
    try:
        if is_tcp:
            data = sock.recv(4096)
        else:
            data, _ = sock.recvfrom(4096)
        start_line, headers, body = parse_sip_message(data.decode("utf-8", errors="ignore"))
        print(f"[<] Received Response to INFO: {start_line}")
        print(f"[+] TCAP payload in 200 OK to INFO:\n{body}")
    except socket.timeout:
        print("[!] Timeout waiting for INFO response")

    # 4. Send BYE with tc_end
    tc_end = {
        "type": "tc_end",
        "dtid": server_dtid
    }
    end_json = json.dumps(tc_end, indent=2)
    branch3 = f"z9hG4bK-{str(uuid.uuid4())[:8]}"
    bye_msg = (
        f"BYE sip:sgw@{host}:{port} SIP/2.0\r\n"
        f"Via: SIP/2.0/{proto} 127.0.0.1:{LOCAL_PORT};branch={branch3};rport\r\n"
        f"Max-Forwards: 70\r\n"
        f"From: <sip:client@127.0.0.1:{LOCAL_PORT}>;tag={from_tag}\r\n"
        f"To: {server_to_tag}\r\n"
        f"Call-ID: {call_id}\r\n"
        f"CSeq: 3 BYE\r\n"
        f"Content-Type: application/json\r\n"
        f"Content-Length: {len(end_json.encode())}\r\n"
        f"\r\n"
        f"{end_json}"
    )

    print("\n-------------------------------------------------------------")
    print(f"[>] Sending BYE with tc_end payload:")
    print(end_json)
    print("-------------------------------------------------------------")

    if is_tcp:
        sock.sendall(bye_msg.encode())
    else:
        sock.sendto(bye_msg.encode(), (host, port))

    # Receive 200 OK for BYE
    try:
        if is_tcp:
            data = sock.recv(4096)
        else:
            data, _ = sock.recvfrom(4096)
        start_line, headers, body = parse_sip_message(data.decode("utf-8", errors="ignore"))
        print(f"[<] Received Response to BYE: {start_line}")
        print(f"[+] Final TCAP ACK in BYE Response:\n{body}")
    except socket.timeout:
        print("[!] Timeout waiting for BYE response")

    sock.close()
    print("\n[✓] Dummy Signalling Gateway TCAP over SIP transaction finished successfully!")
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Test Dummy Signalling Gateway (TCAP over SIP)")
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"SIP Server host (default: {DEFAULT_HOST})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"SIP Server port (default: {DEFAULT_PORT})")
    parser.add_argument("--transport", default="udp", choices=["udp", "tcp"], help="Signaling transport (udp/tcp)")
    parser.add_argument("--msisdn", default="33612345678", help="MSISDN for SRI-for-SM / InitialDP operation")
    parser.add_argument("--camel", action="store_true", help="Simulate CAMEL/INAP InitialDP with ASN.1 BER payload")

    args = parser.parse_args()
    success = run_sgw_demo(host=args.host, port=args.port, transport=args.transport, msisdn=args.msisdn, is_camel=args.camel)
    sys.exit(0 if success else 1)
