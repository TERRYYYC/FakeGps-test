#!/usr/bin/env python3
"""Bind an APK digest to the exact source AND the Gradle runtime JDK that produced it.

A bare APK sha256 is not cross-environment artifact identity. Identical clean source
yields different APK bytes under different Gradle runtime JDKs: JDK 17 emits a synthetic
``UnavailableValueResolver$1`` for an enum switch that JDK 21 does not, and D8 propagates
that into ``classes3.dex`` / ``classes11.dex``. The divergence once read as "the author
installed a dirty worktree" and cost a review cycle -- see
``docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md``.

The repo pins Java source/target 17 but pins NO Gradle runtime JDK (no
``org.gradle.java.home``, no ``java { toolchain { ... } }``), so the building JVM is
ambient per-run environment. Until a toolchain is pinned, the only defence is to make the
JDK travel WITH the hash -- on one line, every time.

That is what this module enforces STRUCTURALLY rather than by convention. Every field is
validated and the line is assembled atomically by :func:`format_line`; a missing or
unparseable JDK raises instead of degrading. There is no code path that emits an
unqualified hash, so "forgot to write down the JDK" is not expressible.

The JDK reported is the **daemon** JVM -- the one that actually runs javac and D8 -- read
from ``gradlew --version`` and then identified from that JDK's own ``release`` file,
rather than from an ambient ``java -version`` that may not be the JVM Gradle used.

One honesty note the line carries itself. Reading the tree at *invocation* time does not
prove the APK came from that tree: build at commit A, check out B, run this, and A's bytes
would be labelled B. So ``--build`` brackets a real Gradle task between two source reads
and refuses if the tree moved, emitting ``source_binding=built``; without it the line says
``source_binding=asserted``, which is the correct claim for an APK whose build this
process never observed. Prefer ``--build`` for anything that becomes release evidence.

Exit codes follow the harness convention: 0 emitted, 2 harness error.
"""

import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

TOKEN = "APK_PROVENANCE"

_SHA256_RE = re.compile(r"\A[0-9a-f]{64}\Z")
_GIT_SHA_RE = re.compile(r"\A[0-9a-f]{40}\Z")
# vendor@version, e.g. JetBrains-s.r.o.@21.0.10 -- neither half may be empty.
_JDK_RE = re.compile(r"\A[^\s@]+@[0-9][^\s@]*\Z")
_DIRTY_SUFFIX = "+dirty"


class ProvenanceError(Exception):
    """Raised when a provenance field cannot be established. Never downgraded."""


# --------------------------------------------------------------------------- parsing


def parse_daemon_java_home(gradle_version_output):
    """Extract the Gradle DAEMON JVM home -- the JVM that actually compiles.

    ``gradlew --version`` reports both a launcher and a daemon JVM. They differ whenever
    ``org.gradle.java.home`` is set, and it is the daemon that runs javac/D8, so the
    daemon is the provenance-relevant one.
    """
    for line in gradle_version_output.splitlines():
        stripped = line.strip()
        if not stripped.startswith("Daemon JVM:"):
            continue
        value = stripped.split(":", 1)[1].strip()
        # Gradle appends a parenthetical rationale, e.g.
        #   /path/to/home (no Daemon JVM specified, using current Java home)
        home = value.split(" (", 1)[0].strip()
        if not home:
            break
        return home
    raise ProvenanceError(
        "could not read 'Daemon JVM:' from gradlew --version output; "
        "refusing to guess the build JDK"
    )


def parse_release_file(text):
    """Identify a JDK from its own ``release`` file: (implementor, version)."""
    fields = {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        key, _, raw = line.partition("=")
        fields[key.strip()] = raw.strip().strip('"')
    version = fields.get("JAVA_VERSION", "")
    implementor = fields.get("IMPLEMENTOR", "")
    if not version:
        raise ProvenanceError("JDK release file has no JAVA_VERSION")
    if not implementor:
        raise ProvenanceError("JDK release file has no IMPLEMENTOR")
    return implementor, version


def jdk_token(implementor, version):
    """Collapse vendor+version into one unsplittable, greppable token."""
    vendor = re.sub(r"\s+", "-", implementor.strip())
    token = "{}@{}".format(vendor, version.strip())
    if not _JDK_RE.match(token):
        raise ProvenanceError("unusable JDK identity: {!r}".format(token))
    return token


def source_token(head_sha, dirty):
    """Exact source identity. Dirtiness is a SUFFIX, not a neighbouring field.

    A separate ``tree=dirty`` column can be quoted away from the sha; a suffix cannot.
    """
    sha = head_sha.strip()
    if not _GIT_SHA_RE.match(sha):
        raise ProvenanceError("not a full 40-hex git sha: {!r}".format(head_sha))
    return sha + _DIRTY_SUFFIX if dirty else sha


BINDING_BUILT = "built"
BINDING_ASSERTED = "asserted"


def format_line(apk_name, apk_sha256, source, jdk, gradle, source_binding):
    """Assemble the one evidence line -- or raise. The single sanctioned producer.

    Every caller path funnels through here, which is what makes an unqualified hash
    impossible to produce rather than merely discouraged.

    The JDK's filesystem home is deliberately NOT a field. It is not build identity --
    JBR 21.0.10 emits the same bytes from ``/opt`` as from ``/Applications`` -- and real
    homes contain spaces (``/Applications/Android Studio.app/...``), which would break the
    whitespace-delimited ``key=value`` grammar every harness parser here relies on.
    """
    if not apk_name or re.search(r"\s", apk_name):
        raise ProvenanceError("bad apk name: {!r}".format(apk_name))
    if not _SHA256_RE.match(apk_sha256 or ""):
        raise ProvenanceError("bad apk sha256: {!r}".format(apk_sha256))
    base = source[: -len(_DIRTY_SUFFIX)] if source.endswith(_DIRTY_SUFFIX) else source
    if not _GIT_SHA_RE.match(base or ""):
        raise ProvenanceError("bad source token: {!r}".format(source))
    if not _JDK_RE.match(jdk or ""):
        raise ProvenanceError("bad jdk token: {!r}".format(jdk))
    if not gradle or re.search(r"\s", gradle):
        raise ProvenanceError("bad gradle version: {!r}".format(gradle))
    if source_binding not in (BINDING_BUILT, BINDING_ASSERTED):
        raise ProvenanceError("bad source_binding: {!r}".format(source_binding))
    return "{} apk={} apk_sha256={} source={} source_binding={} jdk={} gradle={}".format(
        TOKEN, apk_name, apk_sha256, source, source_binding, jdk, gradle
    )


def parse_gradle_version(gradle_version_output):
    match = re.search(r"^Gradle\s+(\S+)\s*$", gradle_version_output, re.MULTILINE)
    if not match:
        raise ProvenanceError("could not read Gradle version from --version output")
    return match.group(1)


def sha256_file(path, _chunk=1024 * 1024):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(_chunk), b""):
            digest.update(block)
    return digest.hexdigest()


# ------------------------------------------------------------------------ collection


def _run(args, cwd):
    try:
        completed = subprocess.run(
            args, cwd=str(cwd), stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
    except OSError as exc:
        raise ProvenanceError("could not run {}: {}".format(args[0], exc))
    if completed.returncode != 0:
        raise ProvenanceError(
            "{} exited {}: {}".format(
                args[0], completed.returncode, completed.stderr.decode("utf-8", "replace").strip()
            )
        )
    return completed.stdout.decode("utf-8", "replace")


def _source_state(run, repo_root):
    head = run(["git", "rev-parse", "HEAD"], repo_root).strip()
    # Tracked-file dirtiness only: untracked scratch files do not change build inputs.
    dirty = bool(run(["git", "status", "--porcelain", "--untracked-files=no"], repo_root).strip())
    return source_token(head, dirty)


def collect(repo_root, apk_path, run=_run, read_text=None, build_task=None):
    """Gather every field. Any failure raises; nothing partial is returned.

    ``build_task`` is what makes ``source=`` trustworthy. Without it this function can
    only read the tree as it is *now*, which is not necessarily the tree the APK was
    built from -- build at commit A, check out commit B, run this, and the line would
    attribute A's bytes to B. That path is still supported (installed or third-party APKs
    have no build to observe) but self-labels ``source_binding=asserted``.

    With ``build_task`` the build is bracketed by two reads of the source state and the
    run is rejected if the tree moved underneath it, so ``source_binding=built`` means the
    binding was observed rather than assumed.
    """
    repo_root = Path(repo_root)

    if build_task:
        before = _source_state(run, repo_root)
        run(["./gradlew", build_task, "--console=plain"], repo_root)
        after = _source_state(run, repo_root)
        if before != after:
            raise ProvenanceError(
                "source changed during the build ({} -> {}); the APK cannot be bound to "
                "either state".format(before, after)
            )
        source, binding = after, BINDING_BUILT
    else:
        source, binding = _source_state(run, repo_root), BINDING_ASSERTED

    apk = Path(apk_path)
    if not apk.is_file():
        raise ProvenanceError("no such APK: {}".format(apk))

    gradle_out = run(["./gradlew", "--version", "--console=plain", "--quiet"], repo_root)
    java_home = parse_daemon_java_home(gradle_out)
    gradle_version = parse_gradle_version(gradle_out)

    reader = read_text or (lambda p: Path(p).read_text(encoding="utf-8"))
    release_path = Path(java_home) / "release"
    try:
        release_text = reader(release_path)
    except OSError as exc:
        raise ProvenanceError("cannot identify build JDK at {}: {}".format(release_path, exc))

    implementor, version = parse_release_file(release_text)
    return format_line(
        apk_name=apk.name,
        apk_sha256=sha256_file(apk),
        source=source,
        jdk=jdk_token(implementor, version),
        gradle=gradle_version,
        source_binding=binding,
    )


def main(argv=None, emit=print):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk", help="path to the built APK")
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parents[1]),
        help="repository root used for git and gradlew (default: this script's repo)",
    )
    parser.add_argument(
        "--build",
        metavar="TASK",
        help=(
            "run this Gradle task first and bracket it with two source reads, so the "
            "emitted source= is observed rather than assumed "
            "(e.g. --build :app:assembleRelease). Strongly preferred for release evidence."
        ),
    )
    args = parser.parse_args(argv)
    try:
        emit(collect(args.repo_root, args.apk, build_task=args.build))
    except ProvenanceError as exc:
        print("HARNESS_ERROR {}".format(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
