#!/usr/bin/env python3
"""
A stand-in for a Discord webhook, for testing Sculklink without Discord.

Run it, point `webhook-url` at it, and every message the mod would send to Discord is printed
to your terminal instead — including the exact JSON, so you can see the embed colours, the
avatar URLs and the allowed_mentions guard for yourself.

    python3 tools/mock-discord.py
    # then set  "webhook-url": "http://127.0.0.1:8787/webhook"  in your config

No dependencies; standard library only.
"""
import argparse
import functools
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

# Always flush: block-buffered stdout through a pipe hides every message until exit.
print = functools.partial(print, flush=True)  # noqa: A001

RESET = "\033[0m"
DIM = "\033[2m"
BOLD = "\033[1m"


def colour_for(value):
    """Maps Sculklink's embed colours back to something visible in a terminal."""
    return {
        0x43B581: "\033[32m",   # join / online   - green
        0xF04747: "\033[31m",   # leave           - red
        0x992D22: "\033[91m",   # death           - dark red
        0xFAA61A: "\033[33m",   # advancement     - gold
        0x747F8D: "\033[90m",   # shutting down   - grey
    }.get(value, "")


class Handler(BaseHTTPRequestHandler):
    show_json = False

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")

        # Webhooks answer 204 with no body; mimic that so the mod's success path is exercised.
        self.send_response(204)
        self.end_headers()

        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            print(f"{DIM}[unparseable]{RESET} {raw}")
            return

        who = payload.get("username", "(webhook default)")

        if "embeds" in payload:
            for embed in payload["embeds"]:
                tint = colour_for(embed.get("color"))
                print(f"{tint}{BOLD}[{who}]{RESET} {tint}{embed.get('description', '')}{RESET}")
        elif "content" in payload:
            avatar = payload.get("avatar_url", "")
            print(f"{BOLD}<{who}>{RESET} {payload['content']}"
                  + (f"  {DIM}(avatar: {avatar}){RESET}" if avatar else ""))

        # The mention guard is the one thing worth seeing on every message: "parse": [] is what
        # stops a player typing @everyone in Minecraft from pinging your whole Discord.
        mentions = payload.get("allowed_mentions")
        if mentions is not None:
            bits = []
            if mentions.get("parse") == []:
                bits.append("@everyone blocked")
            if mentions.get("users"):
                bits.append(f"pings users {mentions['users']}")
            if mentions.get("roles"):
                bits.append(f"pings roles {mentions['roles']}")
            if bits:
                print(f"   {DIM}↳ {', '.join(bits)}{RESET}")

        if self.show_json:
            print(DIM + json.dumps(payload, indent=2, ensure_ascii=False) + RESET)

    def log_message(self, *args):
        pass    # the default access log drowns out the actual messages


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--json", action="store_true", help="also dump the raw payload")
    args = parser.parse_args()

    Handler.show_json = args.json
    url = f"http://127.0.0.1:{args.port}/webhook"
    print(f"Pretending to be Discord on {url}")
    print(f'Set  "webhook-url": "{url}"  in your Sculklink config, then start the server.\n')

    try:
        HTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
