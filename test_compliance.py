#!/usr/bin/env python3
"""
Cross-language compliance test for QTI Neon.

Uses Java as the source of truth to check that a Java host and Java client can communicate
with a respective client and host from all other implementations. The test verifies that both
sides of the library can each register with neon-relay.quietterminal.co.uk:7777, connect, and
exchange game packets cleanly.

Steps
-----
1. Build + install the Java library to the local Maven cache  (mvn install)
2. Compile small Java host/client runner programs against the installed JAR
3. Create a Python venv and install the Python library
4. Test A  — Java host + Python client: client sends a packet, host logs receipt
5. Test B  — Python host + Java client: client sends a packet, host logs receipt
6. Cleanup — remove the Maven artifact and delete the venv + work directory
"""

import os
import shutil
import subprocess
import sys
import textwrap
import threading
import time
import venv as _venv

# Constants

RELAY     = "neon-relay.quietterminal.co.uk:7777"
SESSION_A = 9901   # Java host / Python client
SESSION_B = 9902   # Python host / Java client

BASE_DIR    = os.path.dirname(os.path.abspath(__file__))
JAVA_DIR    = os.path.join(BASE_DIR, "java")
PYTHON_DIR  = os.path.join(BASE_DIR, "python")
WORK_DIR    = os.path.join(BASE_DIR, ".compliance_work")
VENV_DIR    = os.path.join(BASE_DIR, ".compliance_venv")
VENV_PYTHON = os.path.join(VENV_DIR, "bin", "python")
M2_ARTIFACT = os.path.expanduser("~/.m2/repository/com/quietterminal")
JAR_PATH    = os.path.expanduser(
    "~/.m2/repository/com/quietterminal/qti-neon/1.0.0/qti-neon-1.0.0.jar"
)

_PASS = "\033[32mPASS\033[0m"
_FAIL = "\033[31mFAIL\033[0m"


# Java source templates

_JAVA_HOST = textwrap.dedent("""\
    import com.quietterminal.neon.host.NeonHost;
    import com.quietterminal.neon.core.NeonConfig;

    public class NeonHostRunner {
        public static void main(String[] args) throws Exception {
            int sessionId = Integer.parseInt(args[0]);
            String relay  = args[1];

            NeonConfig cfg  = NeonConfig.defaults();
            NeonHost   host = new NeonHost(sessionId, relay, cfg);

            host.setClientConnectCallback((id, name, sid) -> {
                System.out.println("CLIENT_CONNECTED:" + (id & 0xFF) + ":" + name);
                System.out.flush();
            });
            host.setUnhandledPacketCallback((type, sender) -> {
                System.out.println("PACKET_RECEIVED:" + (type & 0xFF) + ":" + (sender & 0xFF));
                System.out.flush();
            });

            Thread hostThread = Thread.ofVirtual().start(() -> {
                try { host.startAndRun(); }
                catch (Exception e) { System.err.println("Host error: " + e.getMessage()); }
            });

            long deadline = System.currentTimeMillis() + 10_000;
            while (!host.isRunning() && System.currentTimeMillis() < deadline)
                Thread.sleep(50);

            if (!host.isRunning()) {
                System.out.println("HOST_FAILED");
                System.out.flush();
                System.exit(1);
            }

            System.out.println("HOST_READY");
            System.out.flush();

            System.in.read();

            if (host.isRunning()) host.stop();
            hostThread.join(3000);
        }
    }
""")

_JAVA_CLIENT = textwrap.dedent("""\
    import com.quietterminal.neon.client.NeonClient;
    import com.quietterminal.neon.core.NeonConfig;

    public class NeonClientRunner {
        public static void main(String[] args) throws Exception {
            int sessionId = Integer.parseInt(args[0]);
            String relay  = args[1];

            NeonConfig cfg    = NeonConfig.defaults();
            NeonClient client = new NeonClient("java-client", cfg);

            client.setUnhandledPacketCallback((type, sender) -> {
                System.out.println("PACKET_RECEIVED:" + (type & 0xFF) + ":" + (sender & 0xFF));
                System.out.flush();
            });

            boolean ok = client.connect(sessionId, relay);
            if (!ok) {
                System.out.println("CONNECT_FAILED");
                System.out.flush();
                System.exit(1);
            }

            System.out.println("CONNECTED:" + (client.getClientId() & 0xFF));
            System.out.flush();

            Thread runThread = Thread.ofVirtual().start(client::run);

            Thread.sleep(300);
            client.sendPacket(new byte[]{0x42}, (byte) 0x10, (byte) 1);
            System.out.println("PACKET_SENT");
            System.out.flush();

            Thread.sleep(1500);
            if (client.isRunning()) client.stop();
            runThread.join(3000);
        }
    }
""")


# Python script templates

_PY_HOST = textwrap.dedent("""\
    import sys, threading, time
    from qti_neon import NeonHost

    session_id = int(sys.argv[1])
    relay      = sys.argv[2]

    host = NeonHost(session_id=session_id, relay_address=relay)
    host.set_client_connect_callback(
        lambda cid, name, sid: print(f"CLIENT_CONNECTED:{cid}:{name}", flush=True)
    )
    host.set_unhandled_packet_callback(
        lambda ptype, sender: print(f"PACKET_RECEIVED:{ptype}:{sender}", flush=True)
    )

    threading.Thread(target=host.start_and_run, daemon=True).start()

    deadline = time.monotonic() + 10
    while not host.is_running and time.monotonic() < deadline:
        time.sleep(0.05)

    if not host.is_running:
        print("HOST_FAILED", flush=True)
        sys.exit(1)

    print("HOST_READY", flush=True)

    try:
        sys.stdin.read()
    except Exception:
        pass

    if host.is_running:
        host.stop()
""")

_PY_CLIENT = textwrap.dedent("""\
    import sys, threading, time
    from qti_neon import NeonClient

    session_id = int(sys.argv[1])
    relay      = sys.argv[2]

    client = NeonClient("python-client")
    client.set_unhandled_packet_callback(
        lambda ptype, sender: print(f"PACKET_RECEIVED:{ptype}:{sender}", flush=True)
    )

    ok = client.connect(session_id=session_id, relay_address=relay)
    if not ok:
        print("CONNECT_FAILED", flush=True)
        sys.exit(1)

    print(f"CONNECTED:{client.client_id}", flush=True)
    threading.Thread(target=client.run, daemon=True).start()

    time.sleep(0.3)
    client.send_packet(b"\\x42", packet_type=0x10, dest_id=1)
    print("PACKET_SENT", flush=True)

    time.sleep(1.5)
    if client.is_running:
        client.stop()
""")


# Helpers

def _section(title: str) -> None:
    print(f"\n{'─' * 62}")
    print(f"  {title}")
    print(f"{'─' * 62}")


def _run(cmd: list, cwd: str | None = None) -> None:
    print(f"  $ {' '.join(str(c) for c in cmd)}")
    subprocess.run(cmd, cwd=cwd, check=True)


def _collect(proc, lines: list, events: dict) -> None:
    for raw in proc.stdout:
        line = raw.rstrip()
        lines.append(line)
        print(f"    {line}")
        for marker, ev in events.items():
            if marker in line:
                ev.set()


def _launch(cmd: list, ready_marker: str, timeout: int = 20,
            use_stdin_pipe: bool = True):
    lines: list[str] = []
    ready = threading.Event()
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stdin=subprocess.PIPE if use_stdin_pipe else None,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    threading.Thread(
        target=_collect,
        args=(proc, lines, {ready_marker: ready}),
        daemon=True,
    ).start()
    if not ready.wait(timeout=timeout):
        proc.kill()
        raise RuntimeError(
            f"Timed out after {timeout}s waiting for '{ready_marker}'"
        )
    return proc, lines


def _java_cmd(main_class: str, *args) -> list:
    cp = f"{WORK_DIR}:{JAR_PATH}"
    return ["java", "-cp", cp, main_class, *args]


# Tests

def _test_java_host_python_client() -> None:
    _section(f"Test A: Java host  ←→  Python client   (session {SESSION_A})")

    print("  Starting Java host…")
    host_proc, host_lines = _launch(
        _java_cmd("NeonHostRunner", str(SESSION_A), RELAY),
        ready_marker="HOST_READY",
        timeout=20,
    )

    print("  Starting Python client…")
    client_proc, client_lines = _launch(
        [VENV_PYTHON, os.path.join(WORK_DIR, "py_client.py"), str(SESSION_A), RELAY],
        ready_marker="PACKET_SENT",
        timeout=20,
        use_stdin_pipe=False,
    )

    time.sleep(2.0)
    client_proc.wait(timeout=5)

    ok_conn   = any("CLIENT_CONNECTED" in l for l in host_lines)
    ok_packet = any("PACKET_RECEIVED"  in l for l in host_lines)

    try:
        host_proc.stdin.close()
        host_proc.wait(timeout=8)
    except Exception:
        host_proc.kill()

    if ok_conn:
        print(f"  {_PASS}  Java host registered Python client connection")
    else:
        print(f"  {_FAIL}  Java host never saw CLIENT_CONNECTED")

    if ok_packet:
        print(f"  {_PASS}  Java host received game packet from Python client")
    else:
        raise RuntimeError("Java host did not receive the game packet from Python client")


def _test_python_host_java_client() -> None:
    _section(f"Test B: Python host  ←→  Java client   (session {SESSION_B})")

    print("  Starting Python host…")
    host_proc, host_lines = _launch(
        [VENV_PYTHON, os.path.join(WORK_DIR, "py_host.py"), str(SESSION_B), RELAY],
        ready_marker="HOST_READY",
        timeout=20,
    )

    print("  Starting Java client…")
    client_proc, client_lines = _launch(
        _java_cmd("NeonClientRunner", str(SESSION_B), RELAY),
        ready_marker="PACKET_SENT",
        timeout=20,
        use_stdin_pipe=False,
    )

    time.sleep(2.0)
    client_proc.wait(timeout=5)

    ok_conn   = any("CLIENT_CONNECTED" in l for l in host_lines)
    ok_packet = any("PACKET_RECEIVED"  in l for l in host_lines)

    try:
        host_proc.stdin.close()
        host_proc.wait(timeout=8)
    except Exception:
        host_proc.kill()

    if ok_conn:
        print(f"  {_PASS}  Python host registered Java client connection")
    else:
        print(f"  {_FAIL}  Python host never saw CLIENT_CONNECTED")

    if ok_packet:
        print(f"  {_PASS}  Python host received game packet from Java client")
    else:
        raise RuntimeError("Python host did not receive the game packet from Java client")


# Main

def main() -> int:
    failures: list[str] = []

    _section("Step 1: Build and install Java library")
    _run(["mvn", "-f", os.path.join(JAVA_DIR, "pom.xml"), "install", "-DskipTests"])

    _section("Step 2: Compile Java host/client runners")
    os.makedirs(WORK_DIR, exist_ok=True)

    host_java   = os.path.join(WORK_DIR, "NeonHostRunner.java")
    client_java = os.path.join(WORK_DIR, "NeonClientRunner.java")
    with open(host_java,   "w") as f: f.write(_JAVA_HOST)
    with open(client_java, "w") as f: f.write(_JAVA_CLIENT)

    _run(["javac", "-cp", JAR_PATH, "-d", WORK_DIR, host_java, client_java])

    _section("Step 3: Create Python venv and install library")
    _venv.create(VENV_DIR, with_pip=True, clear=True)
    _run([VENV_PYTHON, "-m", "pip", "install", "--quiet", PYTHON_DIR])

    with open(os.path.join(WORK_DIR, "py_host.py"),   "w") as f: f.write(_PY_HOST)
    with open(os.path.join(WORK_DIR, "py_client.py"), "w") as f: f.write(_PY_CLIENT)

    try:
        _test_java_host_python_client()
    except Exception as e:
        print(f"\n  {_FAIL}  Test A failed: {e}")
        failures.append(f"Test A: {e}")

    try:
        _test_python_host_java_client()
    except Exception as e:
        print(f"\n  {_FAIL}  Test B failed: {e}")
        failures.append(f"Test B: {e}")

    _section("Step 5: Cleanup")

    if os.path.exists(M2_ARTIFACT):
        shutil.rmtree(M2_ARTIFACT)
        print(f"  Removed Maven artifact:  {M2_ARTIFACT}")

    if os.path.exists(VENV_DIR):
        shutil.rmtree(VENV_DIR)
        print(f"  Removed Python venv:     {VENV_DIR}")

    if os.path.exists(WORK_DIR):
        shutil.rmtree(WORK_DIR)
        print(f"  Removed work directory:  {WORK_DIR}")

    _section("Results")
    if failures:
        for msg in failures:
            print(f"  {_FAIL}  {msg}")
        print()
        return 1

    print(f"  {_PASS}  All tests passed")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
