#!/usr/bin/env bash
#
# test-hook.sh — end-to-end hook verification for FakeGps, zero manual interaction.
#
# Uses whatever profile you configured in the app as ground truth (the ContentProvider is
# read-only, so we can't inject; DB-as-truth also matches the real workflow), relaunches the app
# to republish config + run the read-back probe, then compares — PER FIELD — what a real app
# observes against what you configured. A field configured but observed as the real device value
# is a broken hook; a field configured but missing from the prefs transport is a coverage gap.
#
#   [DB]    what you configured        (adb content query — ground truth)
#   [prefs] world-readable transport   (safe-zone xml; SpoofConfig only carries a subset!)
#   [hook]  parsed by the module       ([DIAG] prefs loaded)
#   [probe] real-app observable value  (HookProbe JSON via public APIs)
set -u

PKG="name.caiyao.fakegps"
ACT="$PKG/.ui.ComposeActivity"
PROVIDER="content://$PKG.data.AppInfoProvider/app"

command -v adb >/dev/null || { echo "adb not found"; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "no device connected"; exit 2; }
PY=$(command -v python3 || command -v python) || { echo "python3 not found"; exit 2; }

echo "════════════════════════════════════════════════════════════════"
echo " FakeGps hook end-to-end test"
echo "════════════════════════════════════════════════════════════════"

# ── [DB] ground truth: the configured profile (first row) ─────────────────────
# NB: `content query` does NOT support --sort; the hook uses rows.get(0) i.e. the lowest id,
# which is the first row the provider returns, so head -1 matches the effective profile.
DB_RAW=$(adb shell content query --uri "$PROVIDER" 2>/dev/null | grep -E '^Row:' | head -1)
[ -z "$DB_RAW" ] && { echo "❌ no profile configured — set one in the app first"; exit 2; }
echo "[DB] effective profile: $(echo "$DB_RAW" | cut -c1-90)…"

# ── relaunch: triggers ConfigPrefsSync.sync() + HookProbe.run() ───────────────
echo "[relaunch] restarting app (republish config + probe)…"
adb shell am force-stop "$PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
adb shell am start -n "$ACT" >/dev/null 2>&1
sleep 8

PREFS=$(adb shell "su -c 'find /data/misc -name spoof_config.xml -exec cat {} \;'" 2>/dev/null | sed 's/&quot;/\"/g')
DIAG=$(adb logcat -d 2>/dev/null | grep -E "FakeGPS: \[DIAG\] prefs loaded" | tail -1)
PROBE=$(adb logcat -d 2>/dev/null | grep -E "FakeGPSProbe" | tail -1 | sed -E "s/.*FakeGPSProbe *: //")

# ── compare per field (python does the JSON + table + verdict) ────────────────
DB_RAW="$DB_RAW" PREFS="$PREFS" PROBE="$PROBE" DIAG="$DIAG" "$PY" - <<'PYEOF'
import os, json, re, sys

db_raw = os.environ.get("DB_RAW", "")
prefs  = os.environ.get("PREFS", "")
probe  = os.environ.get("PROBE", "")

# parse "Row: 0 col=val, col=val, ..." into a dict
db = {}
m = re.sub(r'^Row:\s*\d+\s*', '', db_raw.strip())
for part in m.split(', '):
    if '=' in part:
        k, v = part.split('=', 1)
        db[k.strip()] = None if v == 'NULL' else v.strip()

try:
    pj = json.loads(probe) if probe else {}
except Exception:
    pj = {}

def g(obj, *path):
    for p in path:
        if not isinstance(obj, dict): return None
        obj = obj.get(p)
    return obj

# field spec: (label, db_column, probe_value, in_SpoofConfig_transport)
loc = pj.get("location", {}) or {}
lte = pj.get("lte", {}) or {}
op  = pj.get("operator", {}) or {}
wifi= pj.get("wifi", {}) or {}

def approx(a, b):
    try: return abs(float(a) - float(b)) < 1e-3
    except Exception: return str(a) == str(b)

fields = [
    ("latitude",       "latitude",  loc.get("latitude"),  True),
    ("longitude",      "longitude", loc.get("longitude"), True),
    ("lte.tac",        "tac",       lte.get("tac"),       True),
    ("lte.ci",         "ci",        lte.get("ci"),        True),
    ("lte.pci",        "pci",       lte.get("pci"),       True),
    ("lte.earfcn",     "earfcn",    lte.get("earfcn"),    True),
    ("lte.rsrp",       "lte_rsrp",  lte.get("rsrp"),      True),
    ("mcc",            "mcc",       lte.get("mccString") or op.get("networkOperator"), False),
    ("mnc",            "mnc",       lte.get("mncString"), False),
    ("operatorName",   "operator_name", op.get("networkOperatorName"), False),
    ("gsm.lac",        "lac",       g(pj,"gsm","lac"),    False),
    ("gsm.cid",        "cid",       g(pj,"gsm","cid"),    False),
    ("wifi.ssid",      "wifi_ssid", wifi.get("ssid"),     True),
    ("wifi.bssid",     "wifi_bssid",wifi.get("bssid"),    True),
]

print("")
print("  %-14s %-16s %-22s %-9s %s" % ("FIELD","CONFIGURED(DB)","OBSERVED(probe)","TRANSPORT","VERDICT"))
print("  " + "-"*82)
configured=0; spoofed=0; broken=[]; gaps=[]
for label, col, pv, in_tx in fields:
    cfg = db.get(col)
    if cfg in (None, ""):        # not configured -> skip verdict
        continue
    configured += 1
    tx = "yes" if in_tx else "NO(gap)"
    if pv is None:
        verdict = "probe∅"
    elif approx(cfg, pv):
        verdict = "✅ spoofed"; spoofed += 1
    else:
        verdict = "❌ REAL"
        (broken if in_tx else gaps).append(label)
    if not in_tx and not approx(cfg, pv or ""):
        gaps_note = ""  # already counted
    print("  %-14s %-16s %-22s %-9s %s" % (label, str(cfg)[:15], str(pv)[:21], tx, verdict))

print("  " + "-"*82)
# Full observed cellular/operator readout — exposes "configured value coincidentally equals the
# real network value" false-positives (e.g. tac=26999 also being the real KYIVSTAR tac).
print("")
print("  Observed cellular environment (what a real app sees right now):")
print("    lte:      %s" % json.dumps(lte))
print("    operator: %s" % json.dumps(op))
if lte.get("ci") is not None and (db.get("ci") in (None, "")):
    print("    ⚠️  lte.ci/mcc/mnc/operator are REAL and NOT configured — cellular spoof unproven.")
    print("        Set a DISTINCT cellular value in the app (e.g. ci=99999, operator=TEST) to verify.")
# transport reachability
prefs_ok = bool(re.search(r'"latitude":', prefs))
diag_ok  = bool(os.environ.get("DIAG"))
print("")
print("  Layers:")
print("   [prefs transport]  %s" % ("✅ file present + carries location" if prefs_ok else "❌ prefs file missing/empty"))
print("   [hook parse]       %s" % (("✅ " + os.environ.get("DIAG","")[-60:]) if os.environ.get("DIAG") else "❌ no [DIAG] prefs-loaded log"))
ismock = loc.get("isMock")
print("   [fidelity]         isMock=%s  %s" % (ismock, "✅" if ismock is False else "⚠️ detectable" if ismock else "?"))
print("")
print("  SUMMARY: %d configured fields | %d spoofed OK" % (configured, spoofed))
if broken: print("   ❌ hooked but ineffective (transport carries it, hook not applying): %s" % ", ".join(broken))
if gaps:   print("   🕳  coverage gap (SpoofConfig transport does NOT carry this field): %s" % ", ".join(gaps))
if not broken and not gaps and configured>0: print("   🎉 all configured fields spoofed end-to-end")
sys.exit(1 if (broken or gaps) else 0)
PYEOF
RC=$?
echo "════════════════════════════════════════════════════════════════"
exit $RC
