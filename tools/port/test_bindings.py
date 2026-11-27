"""False-match and malformed-input checks for semantic binding discovery."""
import contextlib
import hashlib
import io
import os
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile
import zlib

import bindings
from dex import Dex, Instruction, Method, Unsupported, reachable, successors


def ins(offset, op, words, ref=None):
    return Instruction(offset, op, tuple(words), ref)


def method(body, ref=('Lparser;', 'parse', '()V'), flags=9):
    return SimpleNamespace(body=lambda: body, ref=ref, flags=flags, code=1,
                           dex=SimpleNamespace(name='classes.dex'),
                           literals=lambda: {i.ref for i in body.values() if i.op in (0x1a, 0x1b)})


def annotation_methods():
    rows = []
    for role, marker in bindings.MARKERS.items():
        body = {0: ins(0, 0x20, [0x1020, 0], 'L' + role + ';'),
                2: ins(2, 0x38, [0x38, 5]),
                4: ins(4, 0x1a, [0x21a, 0], marker),
                6: ins(6, 0x0e, [0x0e]), 7: ins(7, 0x0e, [0x0e])}
        rows.append(method(body))
    return rows


class AnnotationTests(unittest.TestCase):
    def test_roles_follow_true_branch(self):
        result = bindings.annotation_roles(annotation_methods())
        self.assertEqual({k: v['type'] for k, v in result.items()}, {k: 'L' + k + ';' for k in bindings.MARKERS})

    def test_inverted_branch_still_follows_true_result(self):
        rows = annotation_methods()
        body = {0: ins(0, 0x20, [0x1020, 0], 'LGET;'),
                2: ins(2, 0x39, [0x39, 3]), 4: ins(4, 0x0e, [0x0e]),
                5: ins(5, 0x1a, [0x21a, 0], 'GET'), 7: ins(7, 0x0e, [0x0e])}
        rows[0] = method(body)
        self.assertEqual(bindings.annotation_roles(rows)['GET']['type'], 'LGET;')

    def test_shared_marker_after_join_is_not_evidence(self):
        rows = annotation_methods()
        body = rows[0].body()
        body[2].words = (0x38, 2)
        with self.assertRaisesRegex(Unsupported, 'GET branch.*found 0'):
            bindings.annotation_roles(rows)

    def test_other_register_condition_does_not_select_role(self):
        rows = annotation_methods()
        rows[0].body()[2].words = (0x138, 5)
        with self.assertRaisesRegex(Unsupported, 'GET branch.*found 0'):
            bindings.annotation_roles(rows)

    def test_duplicate_role_is_rejected(self):
        rows = annotation_methods()
        rows.append(rows[0])
        with self.assertRaisesRegex(Unsupported, 'GET branch.*found 2'):
            bindings.annotation_roles(rows)

    def test_copied_result_survives_original_register_reuse(self):
        body = {0: ins(0, 0x20, [0x1020, 0], 'LPath;'),
                2: ins(2, 0x02, [0x1402, 0]),
                4: ins(4, 0x54, [0x2054, 0], ('Lx;', 'f', 'Lx;')),
                6: ins(6, 0x38, [0x1438, 4]),
                8: ins(8, 0x0e, [0x0e]), 10: ins(10, 0x0e, [0x0e])}
        self.assertEqual(bindings.test_condition(body, body[0]).offset, 6)

    def test_unreachable_type_test_is_not_evidence(self):
        rows = annotation_methods()
        rows[0].body()[0] = ins(0, 0x0e, [0x0e])
        with self.assertRaisesRegex(Unsupported, 'GET branch.*found 0'):
            bindings.annotation_roles(rows)

    def source(self):
        annotation = 'LGET;'
        metadata = {'Ljava/lang/annotation/Retention;': {'value': ('Ljava/lang/annotation/RetentionPolicy;', 'RUNTIME', 'Ljava/lang/annotation/RetentionPolicy;')}}
        dex = SimpleNamespace(classes={annotation: (0, 0x2601)}, annotations=lambda typ: metadata)
        m = SimpleNamespace(ref=(annotation, 'value', '()Ljava/lang/String;'), flags=0x401, code=0)
        return SimpleNamespace(classes={annotation: dex}, methods=lambda typ: [m]), metadata

    def test_stripped_target_annotation_is_valid(self):
        source, metadata = self.source()
        self.assertTrue(bindings.verify_annotation(source, 'GET', 'LGET;')['runtime_retained'])

    def test_incompatible_target_annotation_is_rejected(self):
        source, metadata = self.source()
        metadata['Ljava/lang/annotation/Target;'] = {'value': [('Ljava/lang/annotation/ElementType;', 'FIELD', 'Ljava/lang/annotation/ElementType;')]}
        with self.assertRaisesRegex(Unsupported, 'wrong annotation target'):
            bindings.verify_annotation(source, 'GET', 'LGET;')

    def test_missing_runtime_retention_is_rejected(self):
        source, metadata = self.source()
        metadata.clear()
        with self.assertRaisesRegex(Unsupported, 'runtime retained'):
            bindings.verify_annotation(source, 'GET', 'LGET;')


class FactoryTests(unittest.TestCase):
    def fixture(self):
        owner, interface = 'Lappassert;', 'Lassertion;'
        singleton = (owner, 'instance', owner)
        ctor = (bindings.MAKER, '<init>', '(Lretro;Lbuilder;Lretro;Lbuilder;' + interface + ')V')
        body = {0: ins(0, 0x22, [0x22, 0], bindings.MAKER),
                2: ins(2, 0x1a, [0x61a, 0], bindings.HOST),
                4: ins(4, 0x62, [0x562, 0], singleton),
                6: ins(6, 0x76, [0x676, 0, 0], ctor),
                9: ins(9, 0x11, [0x11])}
        maker = method(body, ('Lfactory;', 'make', '()' + bindings.MAKER))
        assertion = method({0: ins(0, 0x1a, [0x1a, 0], bindings.ASSERTION)})
        dex = SimpleNamespace(name='classes.dex', classes={owner: (0, 0, 0, 99)}, type_list=lambda off: (interface,))
        source = SimpleNamespace(selected=[maker], classes={owner: dex}, methods=lambda typ: [assertion])
        return source, interface, body

    def test_constructor_argument_defines_factory_assertion(self):
        source, interface, body = self.fixture()
        self.assertEqual(bindings.factory_binding(source, interface)['type'], 'Lappassert;')

    def test_clobbered_argument_is_rejected(self):
        source, interface, body = self.fixture()
        call, returned = body.pop(6), body.pop(9)
        body[6] = ins(6, 0x12, [0x5512])
        body[7] = ins(7, call.op, call.words, call.ref)
        body[10] = ins(10, returned.op, returned.words)
        body = dict(sorted(body.items()))
        source.selected[0].body = lambda: body
        with self.assertRaisesRegex(Unsupported, 'provenance.*found 0'):
            bindings.factory_binding(source, interface)

    def test_returning_different_object_is_rejected(self):
        source, interface, body = self.fixture()
        body[9] = ins(9, 0x11, [0x111])
        with self.assertRaisesRegex(Unsupported, 'provenance.*found 0'):
            bindings.factory_binding(source, interface)

    def test_multiple_factories_do_not_pick_first(self):
        source, interface, body = self.fixture()
        source.selected *= 2
        with self.assertRaisesRegex(Unsupported, 'provenance.*found 2'):
            bindings.factory_binding(source, interface)


class LocaleTests(unittest.TestCase):
    def fixture(self):
        body = {0: ins(0, 0x1a, [0x1a, 0], '_'),
                2: ins(2, 0x71, [0x71, 0, 0], ('Ljava/util/Locale;', 'getDefault', '()Ljava/util/Locale;')),
                5: ins(5, 0x0c, [0x10c]),
                6: ins(6, 0x71, [0x2071, 0, 0x10], ('Lnormalizer;', 'a', '(Ljava/lang/String;Ljava/util/Locale;)Ljava/lang/String;')),
                9: ins(9, 0x0c, [0x0c]), 10: ins(10, 0x11, [0x11])}
        return method(body, ('Lmerged;', 'language', '()Ljava/lang/String;'))

    def test_normalizer_result_is_returned(self):
        self.assertIsNotNone(bindings.locale_binding(self.fixture()))

    def test_swapped_arguments_are_not_accepted(self):
        m = self.fixture()
        m.body()[6].words = (0x2071, 0, 0x01)
        self.assertIsNone(bindings.locale_binding(m))

    def test_locale_can_be_loaded_before_separator(self):
        m = self.fixture()
        body = m.body()
        body[0], body[2], body[5] = body[2], body[5], body[0]
        self.assertIsNotNone(bindings.locale_binding(m))

class DexTests(unittest.TestCase):
    def test_bad_header_refused(self):
        with self.assertRaises(Unsupported):
            Dex(b'not a dex', 'classes.dex')

    def test_checksum_and_signature_are_verified(self):
        data = bytearray(112)
        data[:8] = b'dex\n035\0'
        struct.pack_into('<III', data, 32, len(data), 112, 0x12345678)
        data[12:32] = hashlib.sha1(data[32:]).digest()
        struct.pack_into('<I', data, 8, zlib.adler32(data[12:]) & 0xffffffff)
        self.assertFalse(Dex(bytes(data), 'classes.dex').classes)
        data[-1] ^= 1
        with self.assertRaisesRegex(Unsupported, 'checksum mismatch'):
            Dex(bytes(data), 'classes.dex')
        struct.pack_into('<I', data, 8, zlib.adler32(data[12:]) & 0xffffffff)
        with self.assertRaisesRegex(Unsupported, 'signature mismatch'):
            Dex(bytes(data), 'classes.dex')

    def test_uleb_overflow_refused(self):
        dex = object.__new__(Dex)
        dex.data = bytes([255] * 5)
        with self.assertRaises(Unsupported):
            dex.uleb(0)

    def test_range_and_compact_invokes(self):
        self.assertEqual(ins(0, 0x76, [0x676, 0, 20]).registers, (20, 21, 22, 23, 24, 25))
        self.assertEqual(ins(0, 0x70, [0x3170, 0, 0x5432]).registers, (2, 3, 4))

    def test_invalid_control_flow_refused(self):
        body = {0: ins(0, 0x29, [0x29, 500])}
        with self.assertRaises(Unsupported):
            reachable(body, 0)

    def test_switch_edges_are_relative_to_switch(self):
        body = {0: ins(0, 0x2b, [0x2b, 4, 0]), 3: ins(3, 0x0e, [0x0e]),
                4: ins(4, 0, [0x100, 1, 0, 0, 3, 0])}
        self.assertEqual(successors(body, body[0]), (3, 3))

    def test_unsupported_opcode_never_skips_bytes(self):
        dex = SimpleNamespace(data=bytes(118), unpack=lambda fmt, off: (1, 0, 0, 0, 0, 1) if off == 100 else (0x3e,))
        with self.assertRaisesRegex(Unsupported, 'unsupported opcode'):
            Method(dex, ('Lx;', 'm', '()V'), 9, 100).body()


class OutputTests(unittest.TestCase):
    def test_failed_discovery_does_not_replace_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp, 'bindings.json')
            out.write_text('previous result')
            with patch.object(bindings, 'Input', side_effect=Unsupported('missing role')), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(bindings.main(['missing.apkm', '--out', str(out)]), 1)
            self.assertEqual(out.read_text(), 'previous result')

    def test_duplicate_archive_entries_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp, 'bad.apk')
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'bad')
                with self.assertWarns(UserWarning):
                    archive.writestr('classes.dex', b'bad')
            with self.assertRaisesRegex(Unsupported, 'duplicate'):
                bindings.Input(apk)


@unittest.skipUnless(os.environ.get('SPATCH_PORT_INPUTS'), 'set SPATCH_PORT_INPUTS to the directory containing the three original APKMs')
class RealInputTests(unittest.TestCase):
    def test_three_builds_against_inspected_bindings(self):
        expected = {
            '9.1.98.521': ('a610', 'dnn1', 'n', 'sil1', 'ny21', 'px30', 'wxq0', 'b160'),
            '9.2.0.26': ('o610', 'upn1', 'm', 'fkl1', 'uz21', 'ay30', 'wyq0', 'o160'),
            '9.2.0.2053': ('yt20', 'v4p1', 's', 'w7m1', 'ii31', 'x740', 'ngr0', 'pc60'),
        }
        annotation_names = {
            '9.1.98.521': 'px30 wxq0 g360 u7s0 jkb xzy0 b160',
            '9.2.0.26': 'ay30 wyq0 u360 t8s0 elb c1z0 o160',
            '9.2.0.2053': 'x740 ngr0 gf60 brs0 xmb pjz0 pc60',
        }
        for version, names in expected.items():
            with self.subTest(version=version):
                path = next(Path(os.environ['SPATCH_PORT_INPUTS']).glob('com.spotify.music_' + version + '-*.apkm'))
                result = bindings.discover(bindings.Input(path))
                rows = result['bindings']
                actual = (rows['app_factory']['type'], rows['locale']['owner'], rows['locale']['name'],
                          rows['authorization']['owner'], rows['raw_body']['type'],
                          rows['annotations']['GET']['type'], rows['annotations']['POST']['type'], rows['annotations']['HeaderMap']['type'])
                descriptors = tuple('Lp/' + n + ';' if i != 2 else n for i, n in enumerate(names))
                self.assertEqual(actual, descriptors)
                self.assertEqual(set(rows['annotations']), set(bindings.MARKERS))
                self.assertEqual([rows['annotations'][role]['type'] for role in bindings.MARKERS],
                                 ['Lp/' + name + ';' for name in annotation_names[version].split()])
                self.assertEqual(rows['request_url']['uri']['descriptor'], '()Ljava/net/URI;')
                self.assertEqual(len(result['dex_sha256']), 15)


if __name__ == '__main__':
    unittest.main()
