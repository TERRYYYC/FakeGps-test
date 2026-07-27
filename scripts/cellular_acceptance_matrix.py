import argparse
import json
import sys
from dataclasses import dataclass
from typing import Any, Dict, Iterable, Mapping, Set, Tuple


OUTPUT_PAYLOAD = "payload"
OUTPUT_EXPECTED = "expected"


@dataclass(frozen=True)
class Scenario:
    name: str
    fields: Mapping[str, Any]


_NEIGHBOR_CELLS = [
    {
        "type": "gsm",
        "mcc": 311,
        "mnc": 480,
        "lac": 1_111,
        "cid": 2_222,
        "arfcn": 700,
        "bsic": 15,
        "rssi": -83,
        "ber": 4,
        "ta": 21,
    },
    {
        "type": "lte",
        "mcc": 312,
        "mnc": 530,
        "tac": 5_000,
        "ci": 87_654_321,
        "pci": 411,
        "earfcn": 2_100,
        "bandwidth": 15_000,
        "rssi": -66,
        "rsrp": -109,
        "rsrq": -16,
        "sinr": 11,
        "cqi": 7,
        "ta": 45,
    },
    {
        "type": "wcdma",
        "mcc": 313,
        "mnc": 610,
        "lac": 3_333,
        "cid": 4_444,
        "psc": 222,
        "uarfcn": 10_564,
        "rssi": -82,
        "rscp": -91,
        "ecno": -11,
    },
]

_COMMON_FIELDS = {
    "mcc": 310,
    "mnc": 260,
    "lac": 32_123,
    "cid": 54_321,
    "arfcn": 512,
    "bsic": 37,
    "psc": 321,
    "uarfcn": 10_688,
    "tac": 4_095,
    "ci": 12_345_678,
    "pci": 321,
    "earfcn": 1_800,
    "lte_bandwidth": 10_000,
    "nci": 68_719_400_000,
    "nrarfcn": 636_666,
    "nr_pci": 777,
    "nr_tac": 703_710,
    "gsm_rssi": -77,
    "gsm_ber": 3,
    "gsm_ta": 17,
    "wcdma_ecno": -9,
    "lte_rssi": -61,
    "lte_rsrp": -101,
    "lte_rsrq": -14,
    "lte_sinr": 17,
    "lte_cqi": 9,
    "lte_ta": 37,
    "nr_ss_rsrp": -103,
    "nr_ss_rsrq": -15,
    "nr_ss_sinr": 12,
    "nr_csi_rsrp": -99,
    "nr_csi_rsrq": -13,
    "nr_csi_sinr": 14,
    "signal_fluctuation_enabled": 0,
    "signal_fluctuation_range_db": 6,
    "network_type": 20,
    "data_network_type": 20,
    "voice_network_type": 3,
    "operator_name": "HOOK-LAB",
    "operator_numeric": "310260",
    "sim_operator": "310260",
    "sim_operator_name": "HOOK-SIM",
    "sim_country_iso": "us",
    "network_country_iso": "ca",
    "is_roaming": 1,
    "phone_type": 1,
    "service_state": 0,
    "data_state": 2,
    "data_activity": 3,
    "override_network_type": 2,
    "band": 78,
    "channel_bandwidth": 40_000,
    "cell_bandwidth_downlink": 100_000,
    "physical_cell_id": 777,
    "neighbor_cells_json": json.dumps(
        _NEIGHBOR_CELLS,
        separators=(",", ":"),
        sort_keys=True,
    ),
}

_SCENARIOS = {
    "full-rscp": Scenario(
        "full-rscp",
        dict(_COMMON_FIELDS, wcdma_rscp=-88),
    ),
    "full-rssi": Scenario(
        "full-rssi",
        dict(_COMMON_FIELDS, wcdma_rssi=-85),
    ),
}


def scenario_names() -> Tuple[str, ...]:
    return tuple(_SCENARIOS)


def get_scenario(name: str) -> Scenario:
    try:
        return _SCENARIOS[name]
    except KeyError:
        raise ValueError(
            "unknown scenario {!r}; expected one of {}".format(
                name,
                ", ".join(scenario_names()),
            )
        )


def payload_for(name: str) -> Dict[str, Any]:
    return {
        "schemaVersion": 2,
        "mode": "always_on",
        "fields": dict(get_scenario(name).fields),
    }


def expected_for(name: str) -> Dict[str, Any]:
    expected, _ = _build_expected(get_scenario(name))
    return expected


def covered_profile_fields(name: str) -> Set[str]:
    _, covered = _build_expected(get_scenario(name))
    return covered


def emit_json(name: str, output: str) -> str:
    if output == OUTPUT_PAYLOAD:
        value = payload_for(name)
    elif output == OUTPUT_EXPECTED:
        value = expected_for(name)
    else:
        raise ValueError("unknown output {!r}".format(output))
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def main(argv=None, emit=print) -> int:
    parser = argparse.ArgumentParser(
        description="Emit one deterministic FakeGps cellular acceptance scenario."
    )
    parser.add_argument("scenario")
    parser.add_argument(
        "--output",
        choices=(OUTPUT_PAYLOAD, OUTPUT_EXPECTED),
        required=True,
    )
    try:
        args = parser.parse_args(argv)
        rendered = emit_json(args.scenario, args.output)
    except ValueError as failure:
        emit("MATRIX_ERROR {}".format(failure))
        return 2
    emit(rendered)
    return 0


def _build_expected(scenario: Scenario) -> Tuple[Dict[str, Any], Set[str]]:
    fields = scenario.fields
    expected: Dict[str, Any] = {}
    covered: Set[str] = set()

    def add(path: str, value: Any, sources: Iterable[str] = ()) -> None:
        if path in expected:
            raise AssertionError("duplicate expected path: {}".format(path))
        expected[path] = value
        covered.update(sources)

    radio_values = {
        "gsm": {
            "mccString": ("310", ("mcc",)),
            "mncString": ("260", ("mnc",)),
            "lac": (fields["lac"], ("lac",)),
            "cid": (fields["cid"], ("cid",)),
            "arfcn": (fields["arfcn"], ("arfcn",)),
            "bsic": (fields["bsic"], ("bsic",)),
            "dbm": (fields["gsm_rssi"], ("gsm_rssi",)),
            "bitErrorRate": (fields["gsm_ber"], ("gsm_ber",)),
            "timingAdvance": (fields["gsm_ta"], ("gsm_ta",)),
        },
        "wcdma": {
            "mccString": ("310", ("mcc",)),
            "mncString": ("260", ("mnc",)),
            "lac": (fields["lac"], ("lac",)),
            "cid": (fields["cid"], ("cid",)),
            "psc": (fields["psc"], ("psc",)),
            "uarfcn": (fields["uarfcn"], ("uarfcn",)),
            "dbm": (
                fields.get("wcdma_rscp", fields.get("wcdma_rssi")),
                (
                    ("wcdma_rscp",)
                    if "wcdma_rscp" in fields
                    else ("wcdma_rssi",)
                ),
            ),
            "ecNo": (fields["wcdma_ecno"], ("wcdma_ecno",)),
        },
        "lte": {
            "mccString": ("310", ("mcc",)),
            "mncString": ("260", ("mnc",)),
            "tac": (fields["tac"], ("tac",)),
            "ci": (fields["ci"], ("ci",)),
            "pci": (fields["pci"], ("pci",)),
            "earfcn": (fields["earfcn"], ("earfcn",)),
            "bandwidth": (fields["lte_bandwidth"], ("lte_bandwidth",)),
            "rssi": (fields["lte_rssi"], ("lte_rssi",)),
            "dbm": (fields["lte_rsrp"], ("lte_rsrp",)),
            "rsrp": (
                fields["lte_rsrp"],
                (
                    "lte_rsrp",
                    "signal_fluctuation_enabled",
                    "signal_fluctuation_range_db",
                ),
            ),
            "rsrq": (fields["lte_rsrq"], ("lte_rsrq",)),
            "rssnr": (fields["lte_sinr"], ("lte_sinr",)),
            "cqi": (fields["lte_cqi"], ("lte_cqi",)),
            "timingAdvance": (fields["lte_ta"], ("lte_ta",)),
        },
        "nr": {
            "mccString": ("310", ("mcc",)),
            "mncString": ("260", ("mnc",)),
            "nci": (fields["nci"], ("nci",)),
            "nrarfcn": (fields["nrarfcn"], ("nrarfcn",)),
            "pci": (fields["nr_pci"], ("nr_pci",)),
            "tac": (fields["nr_tac"], ("nr_tac",)),
            "dbm": (fields["nr_ss_rsrp"], ("nr_ss_rsrp",)),
            "ssRsrp": (fields["nr_ss_rsrp"], ("nr_ss_rsrp",)),
            "ssRsrq": (fields["nr_ss_rsrq"], ("nr_ss_rsrq",)),
            "ssSinr": (fields["nr_ss_sinr"], ("nr_ss_sinr",)),
            "csiRsrp": (fields["nr_csi_rsrp"], ("nr_csi_rsrp",)),
            "csiRsrq": (fields["nr_csi_rsrq"], ("nr_csi_rsrq",)),
            "csiSinr": (fields["nr_csi_sinr"], ("nr_csi_sinr",)),
        },
    }

    for prefix in ("cellInfo.sync", "cellInfo.request", "callback.cellInfo"):
        for radio, values in radio_values.items():
            add("{}.{}.registered".format(prefix, radio), True)
            for field_name, (value, sources) in values.items():
                add(
                    "{}.{}.{}".format(prefix, radio, field_name),
                    value,
                    sources,
                )

    neighbor_values = {
        "gsm": {
            "registered": False,
            "mccString": "311",
            "mncString": "480",
            "lac": 1_111,
            "cid": 2_222,
            "arfcn": 700,
            "bsic": 15,
            "dbm": -83,
            "bitErrorRate": 4,
            "timingAdvance": 21,
        },
        "lte": {
            "registered": False,
            "mccString": "312",
            "mncString": "530",
            "tac": 5_000,
            "ci": 87_654_321,
            "pci": 411,
            "earfcn": 2_100,
            "bandwidth": 15_000,
            "rssi": -66,
            "dbm": -109,
            "rsrp": -109,
            "rsrq": -16,
            "rssnr": 11,
            "cqi": 7,
            "timingAdvance": 45,
        },
        "wcdma": {
            "registered": False,
            "mccString": "313",
            "mncString": "610",
            "lac": 3_333,
            "cid": 4_444,
            "psc": 222,
            "uarfcn": 10_564,
            "dbm": -91,
            "ecNo": -11,
        },
    }
    for prefix in ("cellInfo.sync", "cellInfo.request", "callback.cellInfo"):
        for radio, values in neighbor_values.items():
            for field_name, value in values.items():
                add(
                    "{}.neighbors.{}.{}".format(prefix, radio, field_name),
                    value,
                    ("neighbor_cells_json",),
                )

    add("cellInfo.requestCompleted", True)
    add("callback.completed", True)
    add(
        "callback.physicalChannelDelivery",
        "hook_replay_after_permission_denied",
    )

    telephony = {
        "networkOperatorName": ("operator_name", fields["operator_name"]),
        "networkOperator": ("operator_numeric", fields["operator_numeric"]),
        "simOperator": ("sim_operator", fields["sim_operator"]),
        "simOperatorName": ("sim_operator_name", fields["sim_operator_name"]),
        "simCountryIso": ("sim_country_iso", fields["sim_country_iso"]),
        "networkCountryIso": (
            "network_country_iso",
            fields["network_country_iso"],
        ),
        "isNetworkRoaming": ("is_roaming", True),
        "phoneType": ("phone_type", fields["phone_type"]),
        "networkType": ("network_type", fields["network_type"]),
        "dataNetworkType": (
            "data_network_type",
            fields["data_network_type"],
        ),
        "voiceNetworkType": (
            "voice_network_type",
            fields["voice_network_type"],
        ),
        "dataState": ("data_state", fields["data_state"]),
        "dataActivity": ("data_activity", fields["data_activity"]),
        "serviceState": ("service_state", fields["service_state"]),
    }
    for public_name, (source, value) in telephony.items():
        add("telephony.{}".format(public_name), value, (source,))

    add(
        "callback.serviceState.state",
        fields["service_state"],
        ("service_state",),
    )
    add(
        "callback.displayInfo.networkType",
        fields["network_type"],
        ("network_type",),
    )
    add(
        "callback.displayInfo.overrideNetworkType",
        fields["override_network_type"],
        ("override_network_type",),
    )

    physical = {
        "cellBandwidthDownlinkKhz": (
            "cell_bandwidth_downlink",
            fields["cell_bandwidth_downlink"],
        ),
        "cellBandwidthUplinkKhz": (
            "channel_bandwidth",
            fields["channel_bandwidth"],
        ),
        "physicalCellId": (
            "physical_cell_id",
            fields["physical_cell_id"],
        ),
        "networkType": ("network_type", fields["network_type"]),
        "band": ("band", fields["band"]),
        "downlinkChannelNumber": ("nrarfcn", fields["nrarfcn"]),
        "uplinkChannelNumber": ("nrarfcn", fields["nrarfcn"]),
    }
    for public_name, (source, value) in physical.items():
        add(
            "callback.physicalChannel.{}".format(public_name),
            value,
            (source,),
        )
    add("callback.physicalChannel.connectionStatus", 1)

    return expected, covered


if __name__ == "__main__":
    sys.exit(main())
