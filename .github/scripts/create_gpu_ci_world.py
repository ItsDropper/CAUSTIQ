#!/usr/bin/env python3
"""Generate a deterministic flat, water-bearing Minecraft world for GPU CI."""
from pathlib import Path
import shutil
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[2]
server_dir = root / "run" / "server"
world_name = "caustiq-ci"
world_dir = server_dir / world_name
server_dir.mkdir(parents=True, exist_ok=True)

# Water above a grass/dirt/bedrock floor makes both opaque and translucent
# terrain paths observable in the client test. Keep this deterministic.
generator = (
    '{"biome":"minecraft:plains","layers":['
    '{"block":"minecraft:bedrock","height":1},'
    '{"block":"minecraft:dirt","height":2},'
    '{"block":"minecraft:grass_block","height":1},'
    '{"block":"minecraft:water","height":3}],'
    '"features":false,"lakes":false,"structure_overrides":[]}'
)
properties = {
    "enable-command-block": "true",
    "enable-query": "false",
    "enable-rcon": "false",
    "level-name": world_name,
    "level-type": "minecraft:flat",
    "generator-settings": generator,
    "max-players": "1",
    "motd": "CAUSTIQ strict GPU integration fixture",
    "online-mode": "false",
    "player-idle-timeout": "0",
    "server-port": "25575",
    "spawn-protection": "0",
    "view-distance": "10",
    "simulation-distance": "8",
}
(server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
(server_dir / "server.properties").write_text(
    "".join(f"{key}={value}\n" for key, value in properties.items()),
    encoding="utf-8",
)
if world_dir.exists():
    shutil.rmtree(world_dir)

log_path = root / "build" / "caustiq-gpu-ci" / "world-generation.log"
log_path.parent.mkdir(parents=True, exist_ok=True)
with log_path.open("w", encoding="utf-8", errors="replace") as log:
    process = subprocess.Popen(
        ["cmd.exe", "/c", "gradlew.bat runServer --args=nogui"],
        cwd=root,
        stdin=subprocess.PIPE,
        stdout=log,
        stderr=subprocess.STDOUT,
        text=True,
    )
    deadline = time.monotonic() + 300
    ready = False
    while time.monotonic() < deadline:
        if process.poll() is not None:
            break
        try:
            contents = log_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            contents = ""
        if "Done (" in contents:
            ready = True
            break
        time.sleep(2)

    if ready:
        assert process.stdin is not None
        process.stdin.write("stop\n")
        process.stdin.flush()
        try:
            process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=15)
    else:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        print(log_path.read_text(encoding="utf-8", errors="replace")[-12000:], file=sys.stderr)
        raise SystemExit("Minecraft server did not reach its ready state.")

if process.returncode not in (0, None):
    raise SystemExit(f"World-generation server exited with status {process.returncode}.")
regions = world_dir / "region"
if not regions.is_dir() or not list(regions.glob("r.*.*.mca")):
    raise SystemExit("Minecraft server started but did not generate any terrain region files.")

client_saves = root / "run" / "saves"
client_saves.mkdir(parents=True, exist_ok=True)
destination = client_saves / world_name
if destination.exists():
    shutil.rmtree(destination)
shutil.copytree(world_dir, destination)
print(f"Generated deterministic client test world at {destination}")
