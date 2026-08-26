#!/usr/bin/env python3
"""Deterministic OpenMesh wire-size and conformance spike.

This file is reference tooling. It is deliberately outside every Gradle source
set and MUST NOT be imported by the OpenMesh runtime.
"""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import hmac
import io
import json
import math
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


ROOT = Path(__file__).resolve().parent
RESULTS_DIR = ROOT / "results"
FIXTURES_DIR = ROOT / "fixtures"

PAYLOAD_SIZES = (32, 64, 256, 1024, 10 * 1024)
CONTENT_TYPE = "application/octet-stream"
TTL_MS = 72 * 60 * 60 * 1000
MAX_HOPS = 12

# 2026-08-25T12:00:00Z. BP time uses the epoch 2000-01-01T00:00:00Z.
UNIX_CREATED_AT_MS = 1_787_659_200_000
DTN_CREATED_AT_MS = 840_974_400_000
CREATION_SEQUENCE = 1

PACKET_ID = "00000000-0000-4000-8000-000000000001"
SOURCE_NODE_ID = "om1-11111111111111111111111111111111"
DESTINATION_NODE_ID = "om1-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

# The EID is a logical BP endpoint. The embedded om1 identifier is only the
# accepted legacy singleton mapping; it is not a transport address.
SOURCE_EID_URI = f"dtn://openmesh/node/{SOURCE_NODE_ID}/admin"
DESTINATION_EID_URI = f"dtn://openmesh/node/{DESTINATION_NODE_ID}/inbox"

# Private/experimental block code used only by this fixture. It is not an IANA
# allocation and not a production registry decision.
OPENMESH_POLICY_BLOCK_TYPE = 192
OPENMESH_POLICY_BLOCK_NUMBER = 2
HOP_COUNT_BLOCK_NUMBER = 3
BIB_BLOCK_NUMBER = 4
BCB_BLOCK_NUMBER = 5
PAYLOAD_BLOCK_NUMBER = 1

# Public, deterministic TEST KEYS. Never use these values in a deployed node.
HMAC_TEST_KEY = hashlib.sha256(b"OpenMesh ADR spike HMAC test key").digest()
AES_TEST_KEY = hashlib.sha256(b"OpenMesh ADR spike AES test key").digest()

CURRENT_BLE_ATT_MTU = 23
CURRENT_BLE_VALUE_BYTES = CURRENT_BLE_ATT_MTU - 3
CURRENT_BLE_FRAME_HEADER_BYTES = 13
CURRENT_BLE_OBJECT_BYTES_PER_FRAME = (
    CURRENT_BLE_VALUE_BYTES - CURRENT_BLE_FRAME_HEADER_BYTES
)

MTU_247_ATT_MTU = 247
MTU_247_VALUE_BYTES = MTU_247_ATT_MTU - 3
MTU_247_OBJECT_BYTES_PER_FRAME = MTU_247_VALUE_BYTES - CURRENT_BLE_FRAME_HEADER_BYTES

# Size-only sensitivity model for the *actual* E2E v1 codec structure, under
# explicit common-provider assumptions. It is not a fourth wire candidate:
# named-curve P-256 X.509 keys are assumed to be 91 bytes -> 124 Base64 chars,
# and 70--72 byte DER ECDSA signatures all occupy 96 Base64 chars. Providers
# can emit other valid lengths. No modeled bytes are used as a conformance
# vector.
E2E_V1_PUBLIC_KEY_BASE64_BYTES = 124
E2E_V1_SIGNATURE_BASE64_BYTES = 96
E2E_V1_NONCE_BYTES = 12
E2E_V1_GCM_TAG_BYTES = 16
E2E_V1_OUTER_CONTENT_TYPE = "application/vnd.openmesh.e2e-v1"


@dataclass(frozen=True)
class Artifact:
    profile: str
    wire: bytes
    components: dict[str, int]


def _cbor_head(major: int, value: int) -> bytes:
    if not 0 <= major <= 7:
        raise ValueError(f"invalid CBOR major type: {major}")
    if value < 0:
        raise ValueError("this spike only encodes non-negative lengths/integers")
    prefix = major << 5
    if value < 24:
        return bytes((prefix | value,))
    if value <= 0xFF:
        return bytes((prefix | 24, value))
    if value <= 0xFFFF:
        return bytes((prefix | 25,)) + struct.pack(">H", value)
    if value <= 0xFFFFFFFF:
        return bytes((prefix | 26,)) + struct.pack(">I", value)
    if value <= 0xFFFFFFFFFFFFFFFF:
        return bytes((prefix | 27,)) + struct.pack(">Q", value)
    raise ValueError("CBOR value exceeds uint64")


def cbor(value: Any) -> bytes:
    """Encode the narrow deterministic-CBOR subset used by the spike."""
    if isinstance(value, bool):
        return b"\xf5" if value else b"\xf4"
    if isinstance(value, int):
        return _cbor_head(0, value)
    if isinstance(value, bytes):
        return _cbor_head(2, len(value)) + value
    if isinstance(value, str):
        encoded = value.encode("utf-8")
        return _cbor_head(3, len(encoded)) + encoded
    if isinstance(value, (list, tuple)):
        return _cbor_head(4, len(value)) + b"".join(cbor(item) for item in value)
    raise TypeError(f"unsupported CBOR value: {type(value).__name__}")


def cbor_sequence(values: Iterable[Any]) -> bytes:
    return b"".join(cbor(value) for value in values)


def cbor_indefinite_array(encoded_items: Iterable[bytes]) -> bytes:
    return b"\x9f" + b"".join(encoded_items) + b"\xff"


def crc16_x25(data: bytes) -> int:
    """X-25 CRC-16 (poly 0x1021, reflected, init/xorout 0xffff)."""
    crc = 0xFFFF
    for octet in data:
        crc ^= octet
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8408 if crc & 1 else crc >> 1
    return crc ^ 0xFFFF


def dtn_eid(uri: str) -> list[Any]:
    if uri == "dtn:none":
        return [1, 0]
    if not uri.startswith("dtn:"):
        raise ValueError(f"not a dtn-scheme EID: {uri}")
    return [1, uri.removeprefix("dtn:")]


def ipn_eid(node_number: int, service_number: int) -> list[Any]:
    return [2, [node_number, service_number]]


def canonical_block(
    block_type: int,
    block_number: int,
    flags: int,
    block_data: bytes,
    *,
    crc_type: int = 0,
) -> bytes:
    if crc_type != 0:
        raise NotImplementedError("the spike only needs CRC-free canonical blocks")
    return cbor([block_type, block_number, flags, crc_type, block_data])


def primary_block(*, with_crc16: bool) -> bytes:
    fields: list[Any] = [
        7,
        0x000004,  # Bundle must not be fragmented in this initial profile.
        1 if with_crc16 else 0,
        dtn_eid(DESTINATION_EID_URI),
        dtn_eid(SOURCE_EID_URI),
        dtn_eid("dtn:none"),
        [DTN_CREATED_AT_MS, CREATION_SEQUENCE],
        TTL_MS,
    ]
    if not with_crc16:
        return cbor(fields)

    # RFC 9171 §4.3.1 plus verified erratum 8043: keep the byte-string
    # initial byte and zero only the two value bytes while calculating.
    placeholder = cbor([*fields, b"\x00\x00"])
    checksum = crc16_x25(placeholder)
    return cbor([*fields, checksum.to_bytes(2, "big")])


def openmesh_policy_data() -> bytes:
    # [profile version, priority code (NORMAL), receipt mode (NONE)].
    return cbor([1, 1, 0])


def openmesh_adu(payload: bytes) -> bytes:
    # Content type belongs to the application data unit, not to the BP route.
    return cbor([1, CONTENT_TYPE, payload])


def hop_count_data() -> bytes:
    return cbor([MAX_HOPS, 0])


def deterministic_payload(size: int) -> bytes:
    if size < 0:
        raise ValueError("payload size must be non-negative")
    return bytes(((index * 73) + 41) % 251 for index in range(size))


def deterministic_test_iv(payload: bytes) -> bytes:
    # Fixture generation only. Production IV allocation is an ADR-011 concern.
    material = b"OpenMesh ADR spike IV v1\x00" + struct.pack(">I", len(payload))
    material += hashlib.sha256(payload).digest()
    return hashlib.sha256(material).digest()[:12]


def _java_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">i", len(encoded)) + encoded


def _java_nullable_string(value: str | None) -> bytes:
    return (b"\x00" if value is None else b"\x01" + _java_string(value))


def build_envelope_v1(payload: bytes) -> Artifact:
    payload_base64 = base64.b64encode(payload).decode("ascii")
    fields = [
        struct.pack(">I", 0x4F4D5348),
        struct.pack(">i", 1),
        _java_string(PACKET_ID),
        _java_string(SOURCE_NODE_ID),
        _java_nullable_string(DESTINATION_NODE_ID),
        struct.pack(">q", UNIX_CREATED_AT_MS),
        struct.pack(">q", UNIX_CREATED_AT_MS + TTL_MS),
        struct.pack(">i", 0),
        struct.pack(">i", MAX_HOPS),
        _java_nullable_string(None),
        struct.pack(">i", 1),  # PacketPriority.NORMAL.ordinal
        _java_string(CONTENT_TYPE),
        _java_string(payload_base64),
        _java_nullable_string(None),
    ]
    wire = b"".join(fields)
    return Artifact(
        profile="envelope-v1",
        wire=wire,
        components={
            "application_payload": len(payload),
            "base64_payload_field": len(payload_base64),
            "fixed_and_metadata": len(wire) - len(payload_base64),
        },
    )


def _profile_blocks(payload: bytes, *, secure: bool) -> tuple[bytes, bytes, bytes, bytes]:
    primary = primary_block(with_crc16=not secure)
    policy_data = openmesh_policy_data()
    policy = canonical_block(
        OPENMESH_POLICY_BLOCK_TYPE,
        OPENMESH_POLICY_BLOCK_NUMBER,
        0,
        policy_data,
    )
    hop = canonical_block(10, HOP_COUNT_BLOCK_NUMBER, 0, hop_count_data())
    adu = openmesh_adu(payload)
    return primary, policy, hop, adu


def build_bpv7_minimal(payload: bytes) -> Artifact:
    primary, policy, hop, adu = _profile_blocks(payload, secure=False)
    payload_block = canonical_block(1, PAYLOAD_BLOCK_NUMBER, 0, adu)
    wire = cbor_indefinite_array((primary, policy, hop, payload_block))
    return Artifact(
        profile="bpv7-openmesh-minimal",
        wire=wire,
        components={
            "application_payload": len(payload),
            "application_adu": len(adu),
            "primary_block": len(primary),
            "policy_block": len(policy),
            "hop_count_block": len(hop),
            "payload_block": len(payload_block),
            "bundle_framing": 2,
        },
    )


def bib_ippt(
    primary: bytes,
    *,
    target_block_number: int,
    target_block_type: int | None,
    target_block_flags: int,
    target_data: bytes,
    bib_block_number: int,
    bib_block_flags: int = 0,
    scope: int = 7,
) -> bytes:
    output = cbor(scope)
    if target_block_number != 0:
        if scope & 0x01:
            # Included as the primary CBOR array, without a byte-string wrapper.
            output += primary
        if scope & 0x02:
            if target_block_type is None:
                raise ValueError("a non-primary target needs a block type")
            output += cbor_sequence(
                (target_block_type, target_block_number, target_block_flags)
            )
    if scope & 0x04:
        output += cbor_sequence((11, bib_block_number, bib_block_flags))

    # The target canonical form is carried as a CBOR byte string. For target 0,
    # target_data is the encoded primary array itself.
    output += cbor(target_data)
    return output


def bcb_aad(
    primary: bytes,
    *,
    target_block_type: int,
    target_block_number: int,
    target_block_flags: int,
    bcb_block_number: int,
    bcb_block_flags: int,
    scope: int = 7,
) -> bytes:
    output = cbor(scope)
    if scope & 0x01:
        output += primary
    if scope & 0x02:
        output += cbor_sequence(
            (target_block_type, target_block_number, target_block_flags)
        )
    if scope & 0x04:
        output += cbor_sequence((12, bcb_block_number, bcb_block_flags))
    return output


def build_bpv7_bpsec(payload: bytes) -> Artifact:
    primary, policy, hop, adu = _profile_blocks(payload, secure=True)
    policy_data = openmesh_policy_data()

    primary_hmac = hmac.new(
        HMAC_TEST_KEY,
        bib_ippt(
            primary,
            target_block_number=0,
            target_block_type=None,
            target_block_flags=0,
            target_data=primary,
            bib_block_number=BIB_BLOCK_NUMBER,
        ),
        hashlib.sha256,
    ).digest()
    policy_hmac = hmac.new(
        HMAC_TEST_KEY,
        bib_ippt(
            primary,
            target_block_number=OPENMESH_POLICY_BLOCK_NUMBER,
            target_block_type=OPENMESH_POLICY_BLOCK_TYPE,
            target_block_flags=0,
            target_data=policy_data,
            bib_block_number=BIB_BLOCK_NUMBER,
        ),
        hashlib.sha256,
    ).digest()
    bib_asb = cbor_sequence(
        (
            [0, OPENMESH_POLICY_BLOCK_NUMBER],
            1,  # BIB-HMAC-SHA2
            1,  # security context parameters present
            dtn_eid(SOURCE_EID_URI),
            [[1, 5], [3, 7]],  # HMAC-SHA-256 and full integrity scope
            [[[1, primary_hmac]], [[1, policy_hmac]]],
        )
    )
    bib = canonical_block(11, BIB_BLOCK_NUMBER, 0, bib_asb)

    iv = deterministic_test_iv(payload)
    aad = bcb_aad(
        primary,
        target_block_type=1,
        target_block_number=PAYLOAD_BLOCK_NUMBER,
        target_block_flags=0,
        bcb_block_number=BCB_BLOCK_NUMBER,
        bcb_block_flags=1,
    )
    encrypted = AESGCM(AES_TEST_KEY).encrypt(iv, adu, aad)
    ciphertext, authentication_tag = encrypted[:-16], encrypted[-16:]
    bcb_asb = cbor_sequence(
        (
            [PAYLOAD_BLOCK_NUMBER],
            2,  # BCB-AES-GCM
            1,  # security context parameters present
            dtn_eid(SOURCE_EID_URI),
            [[1, iv], [2, 3], [4, 7]],  # IV, A256GCM, full AAD scope
            [[[1, authentication_tag]]],
        )
    )
    bcb = canonical_block(12, BCB_BLOCK_NUMBER, 1, bcb_asb)
    payload_block = canonical_block(1, PAYLOAD_BLOCK_NUMBER, 0, ciphertext)

    wire = cbor_indefinite_array((primary, policy, hop, bib, bcb, payload_block))
    return Artifact(
        profile="bpv7-openmesh-bpsec",
        wire=wire,
        components={
            "application_payload": len(payload),
            "application_adu_plaintext": len(adu),
            "primary_block": len(primary),
            "policy_block": len(policy),
            "hop_count_block": len(hop),
            "bib_block": len(bib),
            "bcb_block": len(bcb),
            "encrypted_payload_block": len(payload_block),
            "bundle_framing": 2,
        },
    )


BUILDERS = (build_envelope_v1, build_bpv7_minimal, build_bpv7_bpsec)


def _frames(encoded_bytes: int, object_bytes_per_frame: int) -> int:
    return math.ceil(encoded_bytes / object_bytes_per_frame)


def modeled_secure_envelope_v1_size(payload_size: int) -> dict[str, int]:
    """Model SecureMeshMessage + MeshEnvelopeCodec using current field sizes.

    The output is intentionally a size model rather than a fixture because the
    runtime ECDSA signature is randomized. The assumed common 70--72 byte DER
    variants all map to the same 96-character Base64 field used here; this is
    not a universal provider guarantee.
    """
    if payload_size < 0:
        raise ValueError("payload size must be non-negative")

    # SecureInnerPayloadCodec: writeUTF(content type), int length, payload.
    inner_payload = 2 + len(CONTENT_TYPE.encode("ascii")) + 4 + payload_size
    ciphertext = inner_payload + E2E_V1_GCM_TAG_BYTES

    # SecureContainerCodec: three int-length-prefixed fields.
    secure_container = (
        4
        + E2E_V1_PUBLIC_KEY_BASE64_BYTES
        + 4
        + E2E_V1_NONCE_BYTES
        + 4
        + ciphertext
    )
    outer_payload_base64 = 4 * math.ceil(secure_container / 3)

    # Reuse the exact v1 codec baseline to avoid independently restating all
    # routing-field lengths, then account for the secure content type and the
    # non-null Base64 signature field.
    empty_v1 = build_envelope_v1(b"")
    insecure_outer_fixed = len(empty_v1.wire)
    content_type_delta = len(E2E_V1_OUTER_CONTENT_TYPE) - len(CONTENT_TYPE)
    signature_delta = 4 + E2E_V1_SIGNATURE_BASE64_BYTES
    encoded_bytes = (
        insecure_outer_fixed
        + content_type_delta
        + signature_delta
        + outer_payload_base64
    )
    return {
        "encoded_bytes": encoded_bytes,
        "overhead_bytes": encoded_bytes - payload_size,
        "inner_payload_bytes": inner_payload,
        "ciphertext_bytes": ciphertext,
        "secure_container_bytes": secure_container,
        "outer_payload_base64_bytes": outer_payload_base64,
        "signature_base64_bytes": E2E_V1_SIGNATURE_BASE64_BYTES,
        "ble_mtu23_frames": _frames(
            encoded_bytes,
            CURRENT_BLE_OBJECT_BYTES_PER_FRAME,
        ),
        "ble_mtu247_frames": _frames(
            encoded_bytes,
            MTU_247_OBJECT_BYTES_PER_FRAME,
        ),
    }


def benchmark_document() -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    artifacts_by_size: dict[int, list[Artifact]] = {}
    for payload_size in PAYLOAD_SIZES:
        payload = deterministic_payload(payload_size)
        artifacts = [builder(payload) for builder in BUILDERS]
        artifacts_by_size[payload_size] = artifacts
        v1_size = len(artifacts[0].wire)
        for artifact in artifacts:
            encoded_bytes = len(artifact.wire)
            rows.append(
                {
                    "payload_bytes": payload_size,
                    "profile": artifact.profile,
                    "encoded_bytes": encoded_bytes,
                    "overhead_bytes": encoded_bytes - payload_size,
                    "expansion_ratio": round(encoded_bytes / payload_size, 6),
                    "delta_vs_envelope_v1_bytes": encoded_bytes - v1_size,
                    "delta_vs_envelope_v1_percent": round(
                        ((encoded_bytes / v1_size) - 1) * 100,
                        3,
                    ),
                    "ble_mtu23_object_bytes_per_frame": CURRENT_BLE_OBJECT_BYTES_PER_FRAME,
                    "ble_mtu23_frames": _frames(
                        encoded_bytes,
                        CURRENT_BLE_OBJECT_BYTES_PER_FRAME,
                    ),
                    "ble_mtu247_object_bytes_per_frame": MTU_247_OBJECT_BYTES_PER_FRAME,
                    "ble_mtu247_frames": _frames(
                        encoded_bytes,
                        MTU_247_OBJECT_BYTES_PER_FRAME,
                    ),
                    "sha256": hashlib.sha256(artifact.wire).hexdigest(),
                    "components": artifact.components,
                }
            )

    secure_v1_sensitivity = []
    for payload_size in PAYLOAD_SIZES:
        modeled = modeled_secure_envelope_v1_size(payload_size)
        bpsec_size = next(
            len(artifact.wire)
            for artifact in artifacts_by_size[payload_size]
            if artifact.profile == "bpv7-openmesh-bpsec"
        )
        secure_v1_sensitivity.append(
            {
                "payload_bytes": payload_size,
                **modeled,
                "bpsec_delta_vs_modeled_secure_v1_bytes": bpsec_size
                - modeled["encoded_bytes"],
                "bpsec_delta_vs_modeled_secure_v1_percent": round(
                    ((bpsec_size / modeled["encoded_bytes"]) - 1) * 100,
                    3,
                ),
            }
        )

    return {
        "schema_version": 1,
        "audit_base": "main@df2dcca20d697cb77bed790d924fe940bd25aaf0",
        "architecture_base": "5b286223175ae249bb292a57222a2835762d25ba",
        "profile_date": "2026-08-25",
        "profile_timezone": "America/Sao_Paulo",
        "payload_generation": "byte[i] = ((i * 73) + 41) mod 251",
        "parameters": {
            "payload_sizes": list(PAYLOAD_SIZES),
            "content_type": CONTENT_TYPE,
            "ttl_ms": TTL_MS,
            "max_hops": MAX_HOPS,
            "source_node_id": SOURCE_NODE_ID,
            "destination_node_id": DESTINATION_NODE_ID,
            "source_eid": SOURCE_EID_URI,
            "destination_eid": DESTINATION_EID_URI,
            "bpv7_no_fragment_flag": True,
            "bpv7_status_reports_requested": False,
            "bpsec_bib": "HMAC-SHA-256, scope=7, targets=primary+policy",
            "bpsec_bcb": "AES-256-GCM, scope=7, target=payload",
            "bpsec_key_delivery": "out-of-band fixture policy; no wrapped key",
            "ble_current_att_mtu": CURRENT_BLE_ATT_MTU,
            "ble_frame_header_bytes": CURRENT_BLE_FRAME_HEADER_BYTES,
        },
        "security_equivalence_limit": (
            "The BPSec default contexts provide symmetric integrity and "
            "confidentiality but do not by themselves provide OpenMesh's "
            "self-certifying asymmetric origin identity. ADR-011 remains "
            "Experimental and no unstandardized origin-proof bytes are hidden "
            "inside this benchmark."
        ),
        "security_equivalence_sensitivity": {
            "status": "modeled-not-a-primary-wire-profile",
            "basis": (
                "Current SecureMeshMessage, SecureInnerPayloadCodec, "
                "SecureContainerCodec and MeshEnvelopeCodec field lengths"
            ),
            "assumptions": {
                "p256_x509_public_key_bytes": 91,
                "public_key_base64_bytes": E2E_V1_PUBLIC_KEY_BASE64_BYTES,
                "ecdsa_der_signature_bytes_assumed_common_range": [70, 72],
                "signature_base64_bytes": E2E_V1_SIGNATURE_BASE64_BYTES,
                "nonce_bytes": E2E_V1_NONCE_BYTES,
                "aes_gcm_tag_bytes": E2E_V1_GCM_TAG_BYTES,
                "inner_content_type": CONTENT_TYPE,
                "outer_content_type": E2E_V1_OUTER_CONTENT_TYPE,
                "provider_variation_warning": (
                    "Valid providers may encode public keys or randomized DER "
                    "ECDSA signatures at other lengths; this is a sensitivity, "
                    "not a universal exact-size claim"
                ),
            },
            "results": secure_v1_sensitivity,
        },
        "results": rows,
    }


def vectors_document() -> dict[str, Any]:
    payload = deterministic_payload(32)
    artifacts = [builder(payload) for builder in BUILDERS]
    return {
        "schema_version": 1,
        "payload_bytes": len(payload),
        "payload_hex": payload.hex(),
        "vectors": [
            {
                "profile": artifact.profile,
                "encoded_bytes": len(artifact.wire),
                "sha256": hashlib.sha256(artifact.wire).hexdigest(),
                "wire_hex": artifact.wire.hex(),
            }
            for artifact in artifacts
        ],
    }


def _csv_text(document: dict[str, Any]) -> str:
    fields = [
        "payload_bytes",
        "profile",
        "encoded_bytes",
        "overhead_bytes",
        "expansion_ratio",
        "delta_vs_envelope_v1_bytes",
        "delta_vs_envelope_v1_percent",
        "ble_mtu23_frames",
        "ble_mtu247_frames",
        "sha256",
    ]
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=fields, lineterminator="\n")
    writer.writeheader()
    for row in document["results"]:
        writer.writerow({field: row[field] for field in fields})
    return output.getvalue()


def _json_text(value: Any) -> str:
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def generated_outputs() -> dict[Path, str]:
    document = benchmark_document()
    return {
        RESULTS_DIR / "benchmark.json": _json_text(document),
        RESULTS_DIR / "benchmark.csv": _csv_text(document),
        FIXTURES_DIR / "openmesh-32-byte-vectors.json": _json_text(vectors_document()),
    }


def write_outputs() -> None:
    for path, contents in generated_outputs().items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8", newline="\n")
        print(path.relative_to(ROOT))


def check_outputs() -> bool:
    valid = True
    for path, expected in generated_outputs().items():
        if not path.exists():
            print(f"missing generated file: {path.relative_to(ROOT)}", file=sys.stderr)
            valid = False
            continue
        actual = path.read_text(encoding="utf-8")
        if actual != expected:
            print(f"stale generated file: {path.relative_to(ROOT)}", file=sys.stderr)
            valid = False
    return valid


def print_summary() -> None:
    document = benchmark_document()
    print(
        "payload\tprofile\tencoded\toverhead\tBLE(MTU23)\tBLE(MTU247)\tΔ v1"
    )
    for row in document["results"]:
        print(
            f"{row['payload_bytes']}\t{row['profile']}\t{row['encoded_bytes']}\t"
            f"{row['overhead_bytes']}\t{row['ble_mtu23_frames']}\t"
            f"{row['ble_mtu247_frames']}\t{row['delta_vs_envelope_v1_percent']}%"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="regenerate committed results")
    mode.add_argument("--check", action="store_true", help="verify committed results")
    args = parser.parse_args()

    if args.write:
        write_outputs()
        return 0
    if args.check:
        return 0 if check_outputs() else 1
    print_summary()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
