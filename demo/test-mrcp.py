#!/usr/bin/env python3
"""
Standalone MRCPv2 (RFC 6787) test client demonstrating Speech Synthesis (TTS)
and Speech Recognition (ASR) with Mistral Audio models over TCP.

Usage:
    python3 demo/test-mrcp.py [--host 127.0.0.1] [--port 1554] [--text "Hello world"]
"""

import argparse
import socket
import sys
import time


def build_mrcp_message(start_line_prefix, tokens_suffix, headers, body=""):
    body_bytes = body.encode("utf-8") if isinstance(body, str) else body
    if body_bytes:
        headers["Content-Length"] = str(len(body_bytes))

    header_lines = "".join(f"{k}: {v}\r\n" for k, v in headers.items()) + "\r\n"
    headers_bytes = header_lines.encode("utf-8")

    prefix_bytes = (start_line_prefix + " ").encode("ascii")
    suffix_bytes = (" " + tokens_suffix + "\r\n").encode("ascii")

    fixed = len(prefix_bytes) + len(suffix_bytes) + len(headers_bytes) + len(body_bytes)
    digits = len(str(fixed))
    total_len = fixed + digits
    while len(str(total_len)) != digits:
        digits = len(str(total_len))
        total_len = fixed + digits

    length_bytes = str(total_len).encode("ascii")
    return prefix_bytes + length_bytes + suffix_bytes + headers_bytes + body_bytes


class MrcpSocketReader:
    def __init__(self, sock):
        self.sock = sock
        self.buffer = b""

    def read_message(self):
        """Reads a complete framed MRCPv2 message from the socket stream buffer."""
        while b"\r\n" not in self.buffer:
            chunk = self.sock.recv(4096)
            if not chunk:
                if not self.buffer:
                    return None
                break
            self.buffer += chunk

        line_end = self.buffer.find(b"\r\n")
        start_line = self.buffer[:line_end].decode("ascii")
        tokens = start_line.split()
        if len(tokens) < 2 or not tokens[0].startswith("MRCP/"):
            raise ValueError(f"Invalid MRCP start-line: {start_line}")

        msg_len = int(tokens[1])
        while len(self.buffer) < msg_len:
            chunk = self.sock.recv(min(4096, msg_len - len(self.buffer)))
            if not chunk:
                break
            self.buffer += chunk

        msg_bytes = self.buffer[:msg_len]
        self.buffer = self.buffer[msg_len:]
        return msg_bytes.decode("utf-8", errors="replace")


def main():
    parser = argparse.ArgumentParser(description="Test MRCPv2 Speech Synthesis and Recognition")
    parser.add_argument("--host", default="127.0.0.1", help="MRCP server host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=1554, help="MRCP server port (default: 1554)")
    parser.add_argument("--text", default="Welcome to Mistral AI Voice Assistant running on Micronaut.",
                        help="Text prompt to synthesize")
    args = parser.parse_args()

    print(f"\n=======================================================")
    print(f" Connecting to MRCPv2 Server at {args.host}:{args.port}...")
    print(f"=======================================================\n")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.connect((args.host, args.port))
    except Exception as e:
        print(f"[ERROR] Could not connect to MRCP server at {args.host}:{args.port}: {e}")
        print("Tip: Ensure sip-app is running with MRCP server enabled.")
        sys.exit(1)

    print("[SUCCESS] TCP connection established to MRCPv2 control channel.\n")

    reader = MrcpSocketReader(sock)

    # -------------------------------------------------------------
    # Step 1: Speech Synthesis (TTS) via SPEAK
    # -------------------------------------------------------------
    print("-" * 55)
    print(" 1. Testing MRCP Speech Synthesis (TTS) with Mistral Audio")
    print("-" * 55)

    channel_synth = "demo-session-1@speechsynth"
    speak_msg = build_mrcp_message(
        "MRCP/2.0",
        f"SPEAK 1",
        {
            "Channel-Identifier": channel_synth,
            "Content-Type": "text/plain",
            "Voice-Name": "en_paul_neutral",
            "Speech-Language": "en-US",
            "Kill-On-Barge-In": "true"
        },
        args.text
    )

    print(f"[SEND REQUEST] SPEAK 1 ({len(args.text)} chars):\n{speak_msg.decode('utf-8')}")
    sock.sendall(speak_msg)

    resp = reader.read_message()
    print(f"[RECV RESPONSE]:\n{resp}")

    if "202 IN-PROGRESS" not in resp and "200 COMPLETE" not in resp:
        print("[FAIL] Unexpected response from server.")
        sys.exit(1)

    # Wait for SPEAK-COMPLETE event
    event = reader.read_message()
    print(f"[RECV EVENT]:\n{event}")

    if "SPEAK-COMPLETE" in event:
        print("[PASS] Speech synthesis completed successfully!\n")
    else:
        print("[WARN] Received unexpected event while waiting for SPEAK-COMPLETE.\n")

    # -------------------------------------------------------------
    # Step 2: Speech Recognition (ASR) via RECOGNIZE
    # -------------------------------------------------------------
    print("-" * 55)
    print(" 2. Testing MRCP Speech Recognition (ASR) with Mistral Audio")
    print("-" * 55)

    channel_recog = "demo-session-1@speechrecog"
    grammar = "grammar: support | sales | billing | operator"
    recog_msg = build_mrcp_message(
        "MRCP/2.0",
        f"RECOGNIZE 2",
        {
            "Channel-Identifier": channel_recog,
            "Content-Type": "text/plain",
            "Confidence-Threshold": "0.75",
            "No-Input-Timeout": "5000",
            "Speech-Language": "en-US"
        },
        grammar
    )

    print(f"[SEND REQUEST] RECOGNIZE 2:\n{recog_msg.decode('utf-8')}")
    sock.sendall(recog_msg)

    resp = reader.read_message()
    print(f"[RECV RESPONSE]:\n{resp}")

    sock.close()
    print("=" * 55)
    print(" Demo completed successfully.")
    print("=" * 55)


if __name__ == "__main__":
    main()
