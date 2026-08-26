from __future__ import annotations

import hashlib
import hmac
import json
import unittest
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import benchmark


ROOT = Path(__file__).resolve().parent


class CborAndCrcConformanceTest(unittest.TestCase):
    def test_deterministic_cbor_integer_boundaries(self) -> None:
        self.assertEqual(benchmark.cbor(0).hex(), "00")
        self.assertEqual(benchmark.cbor(23).hex(), "17")
        self.assertEqual(benchmark.cbor(24).hex(), "1818")
        self.assertEqual(benchmark.cbor(255).hex(), "18ff")
        self.assertEqual(benchmark.cbor(256).hex(), "190100")
        self.assertEqual(benchmark.cbor(65536).hex(), "1a00010000")

    def test_x25_check_value(self) -> None:
        self.assertEqual(benchmark.crc16_x25(b"123456789"), 0x906E)


class Rfc9173ConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.vectors = json.loads(
            (ROOT / "fixtures" / "rfc9173-vectors.json").read_text(encoding="utf-8")
        )

    def test_original_bundle_matches_appendix_a(self) -> None:
        primary = benchmark.cbor(
            [
                7,
                0,
                0,
                benchmark.ipn_eid(1, 2),
                benchmark.ipn_eid(2, 1),
                benchmark.ipn_eid(2, 1),
                [0, 40],
                1_000_000,
            ]
        )
        payload = b"Ready to generate a 32-byte payload"
        payload_block = benchmark.canonical_block(1, 1, 0, payload)
        bundle = benchmark.cbor_indefinite_array((primary, payload_block))
        self.assertEqual(bundle.hex(), self.vectors["original_bundle_hex"])

    def test_example_3_aes_gcm_ciphertext_and_tag(self) -> None:
        key = bytes.fromhex("71776572747975696f70617364666768")
        iv = bytes.fromhex("5477656c7665313231323132")
        plaintext = b"Ready to generate a 32-byte payload"
        encrypted = AESGCM(key).encrypt(iv, plaintext, b"\x00")
        self.assertEqual(encrypted[:-16].hex(), self.vectors["example_3_ciphertext_hex"])
        self.assertEqual(encrypted[-16:].hex(), self.vectors["example_3_tag_hex"])

    def test_example_3_bcb_asb_and_block(self) -> None:
        tag = bytes.fromhex(self.vectors["example_3_tag_hex"])
        iv = bytes.fromhex("5477656c7665313231323132")
        asb = benchmark.cbor_sequence(
            (
                [1],
                2,
                1,
                benchmark.ipn_eid(2, 1),
                [[1, iv], [2, 1], [4, 0]],
                [[[1, tag]]],
            )
        )
        block = benchmark.canonical_block(12, 4, 1, asb)
        self.assertEqual(asb.hex(), self.vectors["example_3_bcb_asb_hex"])
        self.assertEqual(block.hex(), self.vectors["example_3_bcb_block_hex"])

    def test_example_4_full_scope_ippt_and_hmac(self) -> None:
        primary = bytes.fromhex(self.vectors["example_primary_hex"])
        payload = b"Ready to generate a 32-byte payload"
        ippt = benchmark.bib_ippt(
            primary,
            target_block_number=1,
            target_block_type=1,
            target_block_flags=0,
            target_data=payload,
            bib_block_number=3,
            scope=7,
        )
        key = bytes.fromhex("1a2b1a2b1a2b1a2b1a2b1a2b1a2b1a2b")
        signature = hmac.new(key, ippt, hashlib.sha384).digest()
        self.assertEqual(ippt.hex(), self.vectors["example_4_full_scope_ippt_hex"])
        self.assertEqual(signature.hex(), self.vectors["example_4_hmac384_hex"])

    def test_example_4_full_scope_payload_aad(self) -> None:
        primary = bytes.fromhex(self.vectors["example_primary_hex"])
        aad = benchmark.bcb_aad(
            primary,
            target_block_type=1,
            target_block_number=1,
            target_block_flags=0,
            bcb_block_number=2,
            bcb_block_flags=1,
            scope=7,
        )
        self.assertEqual(aad.hex(), self.vectors["example_4_payload_aad_hex"])


class OpenMeshFixtureTest(unittest.TestCase):
    def test_all_profiles_are_deterministic(self) -> None:
        for size in benchmark.PAYLOAD_SIZES:
            payload = benchmark.deterministic_payload(size)
            for builder in benchmark.BUILDERS:
                self.assertEqual(builder(payload).wire, builder(payload).wire)

    def test_committed_outputs_are_current(self) -> None:
        self.assertTrue(benchmark.check_outputs())

    def test_v1_payload_is_the_actual_base64_field(self) -> None:
        payload = benchmark.deterministic_payload(32)
        artifact = benchmark.build_envelope_v1(payload)
        self.assertEqual(artifact.components["base64_payload_field"], 44)
        self.assertTrue(artifact.wire.startswith(bytes.fromhex("4f4d534800000001")))

    def test_secure_profile_uses_distinct_keys(self) -> None:
        self.assertNotEqual(benchmark.HMAC_TEST_KEY, benchmark.AES_TEST_KEY)
        self.assertEqual(len(benchmark.HMAC_TEST_KEY), 32)
        self.assertEqual(len(benchmark.AES_TEST_KEY), 32)

    def test_modeled_secure_v1_sizes_follow_current_codecs(self) -> None:
        expected = {
            32: 602,
            64: 642,
            256: 898,
            1024: 1922,
            10 * 1024: 14210,
        }
        for payload_size, encoded_bytes in expected.items():
            with self.subTest(payload_size=payload_size):
                result = benchmark.modeled_secure_envelope_v1_size(payload_size)
                self.assertEqual(result["encoded_bytes"], encoded_bytes)

    def test_bpsec_is_smaller_than_modeled_secure_v1(self) -> None:
        for payload_size in benchmark.PAYLOAD_SIZES:
            payload = benchmark.deterministic_payload(payload_size)
            bpsec = benchmark.build_bpv7_bpsec(payload)
            modeled_v1 = benchmark.modeled_secure_envelope_v1_size(payload_size)
            self.assertLess(len(bpsec.wire), modeled_v1["encoded_bytes"])


if __name__ == "__main__":
    unittest.main()
