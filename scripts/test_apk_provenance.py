#!/usr/bin/env python3
"""Contract tests for the APK provenance line.

The point of these is not coverage of a formatter. It is that the ONE failure this repo
already paid for -- an APK hash quoted without the JDK that produced it, read as a dirty
worktree -- must be unrepresentable, not merely discouraged. So every test below asks the
same question: can a caller still get a hash out of this module without its build JDK?
"""

import unittest

from scripts import apk_provenance as prov

HEAD = "f825b01aee0a31febf1f85f7ba1806ae22ccf3e9"
APK_SHA = "a" * 64

# Real Gradle 9.3.1 output shape, JBR 21 with no daemon pin.
GRADLE_OUT = """
------------------------------------------------------------
Gradle 9.3.1
------------------------------------------------------------

Kotlin:        2.2.21
Launcher JVM:  21.0.10 (JetBrains s.r.o. 21.0.10+-117844308-b1163.108)
Daemon JVM:    /Applications/Android Studio.app/Contents/jbr/Contents/Home (no Daemon JVM specified, using current Java home)
OS:            Mac OS X 26.5.2 aarch64
"""

JBR_RELEASE = 'JAVA_VERSION="21.0.10"\nIMPLEMENTOR="JetBrains s.r.o."\n'


class ParseDaemonJavaHomeTest(unittest.TestCase):
    def test_reads_daemon_home_and_drops_the_parenthetical_rationale(self):
        self.assertEqual(
            "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
            prov.parse_daemon_java_home(GRADLE_OUT),
        )

    def test_prefers_the_daemon_over_the_launcher_when_they_differ(self):
        # org.gradle.java.home was set: the launcher runs on 17 but javac/D8 run on 21.
        # Reporting the launcher would attribute the bytes to the wrong compiler.
        out = (
            "Launcher JVM:  17.0.20 (Homebrew 17.0.20+0)\n"
            "Daemon JVM:    /opt/jbr-21 (from org.gradle.java.home)\n"
        )
        self.assertEqual("/opt/jbr-21", prov.parse_daemon_java_home(out))

    def test_missing_daemon_line_raises_instead_of_falling_back_to_ambient_java(self):
        # An ambient `java -version` may not be the JVM Gradle used at all. Guessing here
        # is exactly how a hash acquires a JDK label that is not true.
        with self.assertRaises(prov.ProvenanceError):
            prov.parse_daemon_java_home("Gradle 9.3.1\nLauncher JVM:  21.0.10 (JetBrains)\n")


class ReleaseFileTest(unittest.TestCase):
    def test_identifies_vendor_and_version_from_the_jdks_own_release_file(self):
        self.assertEqual(("JetBrains s.r.o.", "21.0.10"), prov.parse_release_file(JBR_RELEASE))

    def test_missing_version_raises(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.parse_release_file('IMPLEMENTOR="JetBrains s.r.o."\n')

    def test_missing_implementor_raises(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.parse_release_file('JAVA_VERSION="21.0.10"\n')

    def test_vendor_whitespace_is_collapsed_so_the_token_cannot_split_a_line(self):
        self.assertEqual("JetBrains-s.r.o.@21.0.10", prov.jdk_token("JetBrains s.r.o.", "21.0.10"))

    def test_empty_vendor_or_version_is_rejected(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.jdk_token("", "21.0.10")
        with self.assertRaises(prov.ProvenanceError):
            prov.jdk_token("JetBrains", "")


class SourceTokenTest(unittest.TestCase):
    def test_clean_tree_is_the_bare_sha(self):
        self.assertEqual(HEAD, prov.source_token(HEAD, dirty=False))

    def test_dirty_is_a_suffix_so_it_cannot_be_quoted_away_from_the_sha(self):
        # A neighbouring `tree=dirty` field can be dropped when someone copies the sha
        # into a doc; a suffix travels with it.
        self.assertEqual(HEAD + "+dirty", prov.source_token(HEAD, dirty=True))

    def test_abbreviated_sha_is_rejected(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.source_token("f825b01", dirty=False)


class FormatLineTest(unittest.TestCase):
    def line(self, **overrides):
        kwargs = dict(
            apk_name="app-release.apk",
            apk_sha256=APK_SHA,
            source=HEAD,
            jdk="JetBrains-s.r.o.@21.0.10",
            gradle="9.3.1",
            source_binding=prov.BINDING_BUILT,
        )
        kwargs.update(overrides)
        return prov.format_line(**kwargs)

    def test_one_line_carries_hash_source_and_jdk_together(self):
        line = self.line()
        self.assertEqual(1, len(line.splitlines()))
        self.assertIn("apk_sha256=" + APK_SHA, line)
        self.assertIn("source=" + HEAD, line)
        self.assertIn("jdk=JetBrains-s.r.o.@21.0.10", line)

    def test_a_hash_without_a_jdk_cannot_be_formatted(self):
        # THE regression this module exists for.
        for absent in ("", None):
            with self.assertRaises(prov.ProvenanceError):
                self.line(jdk=absent)

    def test_a_placeholder_jdk_is_not_accepted_as_a_jdk(self):
        for bogus in ("unknown", "TODO", "n/a", "@21.0.10", "JetBrains@"):
            with self.assertRaises(prov.ProvenanceError):
                self.line(jdk=bogus)

    def test_malformed_hash_is_rejected(self):
        for bad in ("", "abc123", APK_SHA.upper(), "z" * 64):
            with self.assertRaises(prov.ProvenanceError):
                self.line(apk_sha256=bad)

    def test_dirty_source_still_formats_but_stays_marked(self):
        self.assertIn("source=" + HEAD + "+dirty", self.line(source=HEAD + "+dirty"))

    def test_the_strength_of_the_source_claim_is_itself_a_required_field(self):
        # A line may not stay silent about whether its source binding was observed or
        # merely assumed -- silence would read as "observed".
        for bogus in ("", None, "probably", "true"):
            with self.assertRaises(prov.ProvenanceError):
                self.line(source_binding=bogus)


class CollectTest(unittest.TestCase):
    def fake_run(self, gradle_out=GRADLE_OUT):
        def run(args, cwd):
            if args[0] == "git" and args[1] == "rev-parse":
                return HEAD + "\n"
            if args[0] == "git" and args[1] == "status":
                return ""
            if args[0] == "./gradlew":
                return gradle_out
            raise AssertionError("unexpected command {}".format(args))

        return run

    def test_the_baseline_jbr_home_contains_a_space_and_must_not_break_collection(self):
        # Regression: the release-build baseline lives at
        # /Applications/Android Studio.app/Contents/jbr/Contents/Home. An earlier cut put
        # that path in the line and rejected it for containing whitespace, so the tool
        # failed closed on the single most common real setup. The home is not build
        # identity and is no longer a field.
        import tempfile

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            line = prov.collect(
                ".", apk.name, run=self.fake_run(), read_text=lambda p: JBR_RELEASE
            )
        self.assertNotIn(" /", line.split("apk=", 1)[1])
        self.assertIn("jdk=JetBrains-s.r.o.@21.0.10", line)

    def test_emits_a_complete_line_for_a_real_apk(self):
        import tempfile

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            line = prov.collect(
                ".", apk.name, run=self.fake_run(), read_text=lambda p: JBR_RELEASE
            )
        self.assertTrue(line.startswith("APK_PROVENANCE "))
        self.assertIn("jdk=JetBrains-s.r.o.@21.0.10", line)
        self.assertIn("gradle=9.3.1", line)
        self.assertIn("source=" + HEAD, line)

    def test_unreadable_jdk_yields_no_line_at_all_rather_than_a_bare_hash(self):
        import tempfile

        def unreadable(path):
            raise OSError("no such file")

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            with self.assertRaises(prov.ProvenanceError):
                prov.collect(".", apk.name, run=self.fake_run(), read_text=unreadable)

    def test_missing_apk_raises(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.collect(".", "/nonexistent/app.apk", run=self.fake_run())

    def test_without_a_build_the_source_claim_self_labels_as_assumed(self):
        # Reading the tree now does not prove the APK came from it: build at A, check out
        # B, run this, and A's bytes would be labelled B. The line must say so.
        import tempfile

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            line = prov.collect(
                ".", apk.name, run=self.fake_run(), read_text=lambda p: JBR_RELEASE
            )
        self.assertIn("source_binding=asserted", line)

    def test_building_here_upgrades_the_claim_to_observed(self):
        import tempfile

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            line = prov.collect(
                ".",
                apk.name,
                run=self.fake_run(),
                read_text=lambda p: JBR_RELEASE,
                build_task=":app:assembleRelease",
            )
        self.assertIn("source_binding=built", line)

    def test_a_tree_that_moves_mid_build_yields_no_line_at_all(self):
        # Neither the before-sha nor the after-sha honestly describes those bytes, so
        # there is no correct line to emit -- and a wrong one would be worse than none.
        import tempfile

        # One rev-parse per source read: once before the build, once after.
        heads = iter([HEAD, "b" * 40])

        def run(args, cwd):
            if args[0] == "git" and args[1] == "rev-parse":
                return next(heads) + "\n"
            if args[0] == "git" and args[1] == "status":
                return ""
            if args[0] == "./gradlew":
                return GRADLE_OUT
            raise AssertionError("unexpected command {}".format(args))

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            with self.assertRaises(prov.ProvenanceError):
                prov.collect(
                    ".",
                    apk.name,
                    run=run,
                    read_text=lambda p: JBR_RELEASE,
                    build_task=":app:assembleRelease",
                )


class MainTest(unittest.TestCase):
    def test_harness_error_exits_2_and_prints_nothing_to_stdout(self):
        # Fail closed: a non-zero exit with an empty stdout is unusable as evidence,
        # which is the intent. A partial line would have looked usable.
        emitted = []
        code = prov.main(["/nonexistent/app.apk"], emit=emitted.append)
        self.assertEqual(2, code)
        self.assertEqual([], emitted)


if __name__ == "__main__":
    unittest.main()
