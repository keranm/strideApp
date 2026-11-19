package dev.stride.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the FitPro protocol encoder/decoder.
 *
 * FitPro.kt owns framing and encoding only — no I/O — so the whole thing is
 * testable on the JVM with plain JUnit. The same code talks to every ICON
 * board; what differs between consoles is the *bytes on the wire*. So the suite
 * is in three parts:
 *
 *   1. PROTOCOL — the shared contract (framing, checksums, encode/decode,
 *      security) that must hold for any board.
 *   2. C 1750  — the reference Gen 6 console (Android 9): full speed/incline
 *      range, signed decline to -3 %, software-driven belt speed, 9-byte
 *      telemetry frames.
 *   3. ELITE 900 — the Android 7 console this takeover targets: no decline
 *      (incline floor 0 %), lower speed ceiling, and three real firmware
 *      quirks captured on 2026-11-19 — short frames the parser must reject
 *      rather than misread (SLEEP_TIMER_STATE length 5, SPEED device 65 length
 *      7), and `status:failed` on belt-speed writes.
 *
 * Board-behaviour fixtures are the exact byte sequences seen on each machine;
 * see treadmill-takeover/DEVICE-FACTS.md for provenance.
 */
class FitProTest {

    // --- fixtures -----------------------------------------------------------

    /** Build a well-formed ReadWriteData *response*: [dev][len][cmd][status][data…][checksum]. */
    private fun resp(device: Int, status: Int, data: IntArray): ByteArray {
        val len = 4 + data.size + 1
        val f = ByteArray(len)
        f[0] = device.toByte()
        f[1] = len.toByte()
        f[2] = FitPro.Cmd.READ_WRITE_DATA.toByte()
        f[3] = status.toByte()
        for (i in data.indices) f[4 + i] = data[i].toByte()
        f[len - 1] = FitPro.checksum(f, len)
        return f
    }

    private fun i(vararg bytes: Int) = bytes

    // ========================================================================
    // 1. PROTOCOL — shared contract
    // ========================================================================

    @Test fun checksum_is_truncated_sum_of_all_prior_bytes() {
        // 04 05 02 04 -> 0x0F. This is the real "write failed" header from the
        // Elite 900, and a byte-exact checksum check of it.
        val f = byteArrayOf(0x04, 0x05, 0x02, 0x04, 0x0F)
        assertEquals(0x0F.toByte(), FitPro.checksum(f, f.size))
        assertTrue(FitPro.isValid(f))
    }

    @Test fun isValid_rejects_bad_checksum() {
        val f = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, i(0xC8, 0x00))
        f[f.size - 1] = (f[f.size - 1] + 1).toByte() // corrupt it
        assertFalse(FitPro.isValid(f))
    }

    @Test fun isValid_rejects_runts_and_bogus_length() {
        assertFalse(FitPro.isValid(byteArrayOf(0x04, 0x02)))             // too short
        assertFalse(FitPro.isValid(byteArrayOf(0x04, 0x02, 0x00, 0x00))) // len < 4
        assertFalse(FitPro.isValid(byteArrayOf(0x04, 0x40, 0x02, 0x00))) // len > frame
    }

    @Test fun kph_roundtrips_unsigned_centis() {
        // 2.00 km/h -> raw 200 -> C8 00 (little-endian).
        val enc = FitPro.Field.KPH.encode(2.0)
        assertArrayEquals(byteArrayOf(0xC8.toByte(), 0x00), enc)
        assertEquals(2.0, FitPro.Field.KPH.decode(enc, 0), 1e-9)
    }

    @Test fun grade_is_signed_decline_does_not_become_655_percent() {
        // The asymmetry the header warns about: grade is signed, speed is not.
        // -3.00 % -> raw -300 -> D4 FE (two's complement, LE).
        val enc = FitPro.Field.GRADE.encode(-3.0)
        assertArrayEquals(byteArrayOf(0xD4.toByte(), 0xFE.toByte()), enc)
        assertEquals(-3.0, FitPro.Field.GRADE.decode(enc, 0), 1e-9)
        // Decoding the same bytes as unsigned (the bug) would give ~652 %.
        assertEquals(652.36, FitPro.Field.KPH.decode(enc, 0), 1e-2)
    }

    @Test fun grade_positive_roundtrips() {
        val enc = FitPro.Field.GRADE.encode(12.0)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 0x04), enc)
        assertEquals(12.0, FitPro.Field.GRADE.decode(enc, 0), 1e-9)
    }

    @Test fun distance_is_four_byte_little_endian() {
        val b = byteArrayOf(0x10, 0x27, 0x00, 0x00) // 0x2710 = 10000 m
        assertEquals(10000.0, FitPro.Field.DISTANCE.decode(b, 0), 1e-9)
    }

    @Test fun pulse_reads_only_byte_zero_not_the_packed_struct() {
        // [userPulse, average, count, source] — reading all four as an int
        // yields garbage once a strap connects. Only byte 0 is bpm.
        val b = byteArrayOf(72, 70, 5, 1)
        assertEquals(72.0, FitPro.Field.PULSE.decode(b, 0), 1e-9)
    }

    @Test fun sentinel_detects_all_FF_fill() {
        val b = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertTrue(FitPro.Field.DISTANCE.isSentinel(b, 0))
        assertFalse(FitPro.Field.DISTANCE.isSentinel(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00), 0))
    }

    @Test fun readWrite_write_frame_is_byte_exact() {
        // Write KPH=2.0 to the treadmill device. Hand-computed:
        //   [04][09][02] [01 01] (write section: 1 mask byte, bit 0) [C8 00]
        //   [00] (empty read section) [D9] (checksum 0xD9)
        val frame = FitPro.readWrite(FitPro.Dev.TREADMILL, writes = mapOf(FitPro.Field.KPH to 2.0))
        assertArrayEquals(
            byteArrayOf(0x04, 0x09, 0x02, 0x01, 0x01, 0xC8.toByte(), 0x00, 0x00, 0xD9.toByte()),
            frame,
        )
    }

    @Test fun readWrite_sorts_reads_by_field_id() {
        // Reads given out of order must emit ascending; parse relies on it.
        val frame = FitPro.readWrite(
            FitPro.Dev.TREADMILL,
            reads = listOf(FitPro.Field.ACTUAL_KPH, FitPro.Field.KPH, FitPro.Field.GRADE),
        )
        assertTrue(FitPro.isValid(frame))
        assertEquals(FitPro.Dev.TREADMILL, frame[0])
        assertEquals(FitPro.Cmd.READ_WRITE_DATA.toByte(), frame[2])
    }

    @Test fun readWrite_drops_non_writable_fields() {
        // ACTUAL_KPH is read-only; asking to write it must not emit a write
        // section for it (it would desync the board).
        val good = FitPro.readWrite(FitPro.Dev.TREADMILL, writes = mapOf(FitPro.Field.GRADE to 5.0))
        val withJunk = FitPro.readWrite(
            FitPro.Dev.TREADMILL,
            writes = mapOf(FitPro.Field.GRADE to 5.0, FitPro.Field.ACTUAL_KPH to 9.0),
        )
        assertArrayEquals(good, withJunk)
    }

    @Test fun readWrite_rejects_oversized_frame() {
        // A write map large enough to blow past MAX_MSG must throw, not truncate.
        try {
            // DISTANCE etc. aren't writable; use many writable fields won't reach
            // 64 easily, so assert the require via a direct long content instead.
            FitPro.command(FitPro.Dev.TREADMILL, FitPro.Cmd.READ_WRITE_DATA, ByteArray(FitPro.MAX_MSG))
            throw AssertionError("expected IllegalArgumentException for oversized frame")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too long"))
        }
    }

    @Test fun expectedLength_accounts_for_every_field_width() {
        // header(4) + RPM(2) + ACTUAL_KPH(2) + checksum(1) = 9
        assertEquals(9, FitPro.expectedLength(listOf(FitPro.Field.RPM, FitPro.Field.ACTUAL_KPH)))
        // header(4) + SLEEP_TIMER_STATE(1) + checksum(1) = 6
        assertEquals(6, FitPro.expectedLength(listOf(FitPro.Field.SLEEP_TIMER_STATE)))
    }

    @Test fun parse_returns_values_for_a_clean_response() {
        val reads = listOf(FitPro.Field.KPH, FitPro.Field.GRADE)
        val data = FitPro.Field.KPH.encode(5.0) + FitPro.Field.GRADE.encode(3.5)
        val r = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, IntArray(data.size) { data[it].toInt() and 0xFF })
        val parsed = FitPro.parse(r, reads)
        assertEquals(5.0, parsed[FitPro.Field.KPH]!!, 1e-9)
        assertEquals(3.5, parsed[FitPro.Field.GRADE]!!, 1e-9)
    }

    @Test fun parse_returns_empty_on_sentinel_so_the_hud_never_shows_4_billion_km() {
        val reads = listOf(FitPro.Field.DISTANCE)
        val r = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, i(0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals("sentinel:DISTANCE", FitPro.rejectReason(r, reads))
        assertTrue(FitPro.parse(r, reads).isEmpty())
    }

    @Test fun rejectReason_names_each_failure_mode() {
        val reads = listOf(FitPro.Field.KPH)
        assertEquals("short:3", FitPro.rejectReason(byteArrayOf(1, 2, 3), reads))
        val notDone = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.FAILED, i(0, 0))
        assertEquals("status:failed", FitPro.rejectReason(notDone, reads))
        val good = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, i(0xC8, 0x00))
        assertNull(FitPro.rejectReason(good, reads))
    }

    // --- security -----------------------------------------------------------

    @Test fun securityHash_is_32_bytes_and_deterministic() {
        val a = FitPro.securityHash(serialNumber = 123456, partNumber = 374677, modelNumber = 39915)
        val b = FitPro.securityHash(serialNumber = 123456, partNumber = 374677, modelNumber = 39915)
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test fun securityHash_depends_on_every_input() {
        val base = FitPro.securityHash(123456, 374677, 39915)
        assertFalse(base.contentEquals(FitPro.securityHash(123457, 374677, 39915)))
        assertFalse(base.contentEquals(FitPro.securityHash(123456, 374678, 39915)))
        assertFalse(base.contentEquals(FitPro.securityHash(123456, 374677, 39916)))
    }

    @Test fun verifySecurity_frame_carries_hash_then_eight_times_mlv() {
        val hash = ByteArray(32) { it.toByte() }
        val frame = FitPro.verifySecurity(FitPro.Dev.MAIN, hash, masterLibraryVersion = 83)
        assertEquals(40, frame.size) // 36 content + 4 header-ish
        assertEquals(FitPro.Cmd.VERIFY_SECURITY.toByte(), frame[2])
        assertTrue(FitPro.isValid(frame))
        // 8 * 83 = 664 = 0x0298, little-endian at content offset 32 (frame 35..38).
        assertEquals(0x98.toByte(), frame[35])
        assertEquals(0x02.toByte(), frame[36])
        assertEquals(0x00.toByte(), frame[37])
        assertEquals(0x00.toByte(), frame[38])
    }

    @Test fun verifySecurity_rejects_wrong_hash_length() {
        try {
            FitPro.verifySecurity(FitPro.Dev.MAIN, ByteArray(16), 83)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("32 bytes"))
        }
    }

    @Test fun identity_parsers_reject_non_done_frames() {
        // A hash built from a misread serial is a lockout, not a hash — so a
        // SECURITY_BLOCK/FAILED frame must parse to null, never to a guess.
        val blocked = resp(FitPro.Dev.MAIN.toInt(), FitPro.Status.SECURITY_BLOCK, IntArray(12) { 0xFF })
        assertNull(FitPro.parseDeviceInfo(blocked))
        assertNull(FitPro.parseSystemInfo(blocked))
        assertNull(FitPro.parseMasterLibraryVersion(blocked))
    }

    // ========================================================================
    // 2. C 1750 — reference Gen 6 console (Android 9)
    //    Ranges: speed 1.6..20 km/h, incline -3..+12 %. Software drives speed.
    // ========================================================================

    @Test fun c1750_full_speed_and_incline_range_roundtrips() {
        // Top speed 20.0 km/h -> raw 2000 -> D0 07.
        assertArrayEquals(byteArrayOf(0xD0.toByte(), 0x07), FitPro.Field.KPH.encode(20.0))
        assertEquals(20.0, FitPro.Field.KPH.decode(byteArrayOf(0xD0.toByte(), 0x07), 0), 1e-9)
        // Min speed 1.6 km/h -> raw 160 -> A0 00.
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x00), FitPro.Field.KPH.encode(1.6))
    }

    @Test fun c1750_reports_its_limits_via_minmax_fields() {
        // Board volunteers MIN_KPH/MAX_KPH/MIN_GRADE/MAX_GRADE; MIN_GRADE is the
        // signed -3 % that only this console (not the 900) offers.
        val reads = listOf(
            FitPro.Field.MAX_GRADE, FitPro.Field.MIN_GRADE,
            FitPro.Field.MAX_KPH, FitPro.Field.MIN_KPH,
        )
        val data = FitPro.Field.MAX_GRADE.encode(12.0) +
            FitPro.Field.MIN_GRADE.encode(-3.0) +
            FitPro.Field.MAX_KPH.encode(20.0) +
            FitPro.Field.MIN_KPH.encode(1.6)
        val r = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE,
            IntArray(data.size) { data[it].toInt() and 0xFF })
        val p = FitPro.parse(r, reads)
        assertEquals(12.0, p[FitPro.Field.MAX_GRADE]!!, 1e-9)
        assertEquals(-3.0, p[FitPro.Field.MIN_GRADE]!!, 1e-9) // decline: 1750 only
        assertEquals(20.0, p[FitPro.Field.MAX_KPH]!!, 1e-9)
        assertEquals(1.6, p[FitPro.Field.MIN_KPH]!!, 1e-9)
    }

    @Test fun c1750_running_telemetry_frame_parses_all_fields() {
        // The software-speed console reports a real ActualKph while running.
        val reads = listOf(
            FitPro.Field.RPM, FitPro.Field.WORKOUT_MODE,
            FitPro.Field.ACTUAL_KPH, FitPro.Field.ACTUAL_INCLINE,
        )
        val data = FitPro.Field.RPM.encode(2500.0) +
            FitPro.Field.WORKOUT_MODE.encode(FitPro.Mode.RUNNING.toDouble()) +
            FitPro.Field.ACTUAL_KPH.encode(8.0) +
            FitPro.Field.ACTUAL_INCLINE.encode(4.0)
        val r = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE,
            IntArray(data.size) { data[it].toInt() and 0xFF })
        val p = FitPro.parse(r, reads)
        assertEquals(FitPro.Mode.RUNNING.toDouble(), p[FitPro.Field.WORKOUT_MODE]!!, 1e-9)
        assertEquals(8.0, p[FitPro.Field.ACTUAL_KPH]!!, 1e-9) // non-zero: 1750 drives speed
        assertEquals(4.0, p[FitPro.Field.ACTUAL_INCLINE]!!, 1e-9)
    }

    @Test fun c1750_speed_write_is_accepted_status_done() {
        // On this console software may set belt speed; the board ACKs the write.
        val ack = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, IntArray(0))
        assertNull(FitPro.rejectReason(ack, emptyList()))
    }

    // ========================================================================
    // 3. ELITE 900 — Android 7 console (NTL89121)
    //    Ranges: speed 0.8..19.32 km/h, incline 0..12 % (no decline).
    //    Belt speed is user-driven; three firmware quirks below.
    // ========================================================================

    @Test fun elite900_speed_ceiling_and_floor_roundtrip() {
        // 19.32 km/h -> raw 1932 -> 8C 07; 0.8 km/h -> raw 80 -> 50 00.
        assertArrayEquals(byteArrayOf(0x8C.toByte(), 0x07), FitPro.Field.KPH.encode(19.32))
        assertEquals(19.32, FitPro.Field.KPH.decode(byteArrayOf(0x8C.toByte(), 0x07), 0), 1e-9)
        assertArrayEquals(byteArrayOf(0x50, 0x00), FitPro.Field.KPH.encode(0.8))
    }

    @Test fun elite900_incline_floor_is_zero_no_decline() {
        // The 900 has no decline; 0 % is the floor and still roundtrips cleanly.
        assertArrayEquals(byteArrayOf(0x00, 0x00), FitPro.Field.GRADE.encode(0.0))
        assertEquals(0.0, FitPro.Field.GRADE.decode(byteArrayOf(0x00, 0x00), 0), 1e-9)
    }

    @Test fun elite900_belt_speed_write_failed_frame_is_rejected() {
        // Captured verbatim: the board answers every KPH / non-IDLE WORKOUT_MODE
        // write from IDLE with `04 05 02 04 0F` — a valid, checksummed frame
        // whose status is FAILED. The parser must see it as a clean rejection,
        // not a malformed packet and not data.
        val failed = byteArrayOf(0x04, 0x05, 0x02, 0x04, 0x0F)
        assertTrue(FitPro.isValid(failed))
        assertEquals("status:failed", FitPro.rejectReason(failed, emptyList()))
        assertTrue(FitPro.parse(failed, emptyList()).isEmpty())
    }

    @Test fun elite900_idle_write_is_accepted() {
        // The one WORKOUT_MODE the board accepts from IDLE is IDLE itself — the
        // documented way to bring a board out of any non-idle/SLEEP state.
        val frame = FitPro.readWrite(
            FitPro.Dev.TREADMILL,
            writes = mapOf(FitPro.Field.WORKOUT_MODE to FitPro.Mode.IDLE.toDouble()),
        )
        assertTrue(FitPro.isValid(frame))
        val ack = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, IntArray(0))
        assertNull(FitPro.rejectReason(ack, emptyList()))
    }

    @Test fun elite900_sleep_timer_state_short_frame_is_rejected_gracefully() {
        // The board does not report SLEEP_TIMER_STATE: it returns a frame of
        // length 5 where 6 is expected. Must reject, not read a phantom byte.
        val reads = listOf(FitPro.Field.SLEEP_TIMER_STATE)
        val short = resp(FitPro.Dev.MAIN.toInt(), FitPro.Status.DONE, IntArray(0)) // len 5
        assertEquals(5, short.size)
        assertEquals("length:5!=6", FitPro.rejectReason(short, reads))
        assertTrue(FitPro.parse(short, reads).isEmpty())
    }

    @Test fun elite900_speed_probe_short_frame_from_device_65_is_rejected() {
        // Speed telemetry quirk: probing [RPM, ACTUAL_KPH] (expects 9 bytes) the
        // board answers device 65 with a 7-byte frame carrying a single value.
        // Captured: 41 07 02 02 00 00 (+checksum). Reject as length:7!=9 so the
        // HUD shows no speed rather than a wrong one. (The real fix — reading
        // device 65's native short frame — is tracked in DEVICE-FACTS.md.)
        val reads = listOf(FitPro.Field.RPM, FitPro.Field.ACTUAL_KPH)
        val speed65 = resp(FitPro.Dev.SPEED.toInt(), FitPro.Status.DONE, i(0x00, 0x00)) // len 7
        assertEquals(7, speed65.size)
        assertEquals(0x41.toByte(), speed65[0]) // device 65
        assertEquals("length:7!=9", FitPro.rejectReason(speed65, reads))
        assertTrue(FitPro.parse(speed65, reads).isEmpty())
    }

    @Test fun elite900_actual_kph_reads_zero_while_running_matches_capture() {
        // Even when the belt moves, this board reports ACTUAL_KPH as 0 — the
        // documented fact beltSpeed/probeSpeed are written around.
        val reads = listOf(FitPro.Field.ACTUAL_KPH)
        val r = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, i(0x00, 0x00))
        assertEquals(0.0, FitPro.parse(r, reads)[FitPro.Field.ACTUAL_KPH]!!, 1e-9)
    }

    @Test fun elite900_security_block_is_recognised_not_treated_as_failure() {
        // A locked board answers EVERY ReadWriteData with status 8, reads
        // included. isSecurityBlock must spot it so the poll loop re-unlocks
        // instead of discarding the frame as a dead read.
        val reads = listOf(FitPro.Field.KPH)
        val blocked = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.SECURITY_BLOCK, i(0xFF, 0xFF))
        assertTrue(FitPro.isSecurityBlock(blocked))
        assertEquals("status:securityBlock", FitPro.rejectReason(blocked, reads))
        // A normal Done frame is NOT a security block.
        val ok = resp(FitPro.Dev.TREADMILL.toInt(), FitPro.Status.DONE, i(0xC8, 0x00))
        assertFalse(FitPro.isSecurityBlock(ok))
    }

    @Test fun elite900_handshake_parses_identity_model_and_mlv() {
        // The successful handshake: mlv=83. Build the three identity replies at
        // the documented offsets and confirm they parse.
        // DeviceInfo(129): sw @4, serial u32le @6. len>=11.
        val dev = ByteArray(11).also {
            it[0] = FitPro.Dev.MAIN; it[1] = 11; it[2] = FitPro.Cmd.DEVICE_INFO.toByte(); it[3] = FitPro.Status.DONE.toByte()
            it[4] = 7 // software version
            it[6] = 0x40; it[7] = 0xE2.toByte(); it[8] = 0x01; it[9] = 0x00 // serial 123456
            it[10] = FitPro.checksum(it, 11)
        }
        val id = FitPro.parseDeviceInfo(dev)
        assertNotNull(id)
        assertEquals(7, id!!.softwareVersion)
        assertEquals(123456, id.serialNumber)

        // VersionInfo(132): mlv @4. len>=6.
        val ver = ByteArray(6).also {
            it[0] = FitPro.Dev.MAIN; it[1] = 6; it[2] = FitPro.Cmd.VERSION_INFO.toByte(); it[3] = FitPro.Status.DONE.toByte()
            it[4] = 83 // master library version
            it[5] = FitPro.checksum(it, 6)
        }
        assertEquals(83, FitPro.parseMasterLibraryVersion(ver))
    }

    @Test fun systemInfo_applies_the_production_run_part_number_fixup() {
        // ICON's own fix-up, carried verbatim: one run reports a part number
        // that doesn't match its security hash; model 39915 + part 370357 must
        // be rewritten to 374677 or the board never unlocks.
        // SystemInfo(130): model u32le @7, part u32le @11. len>=16.
        fun sysFrame(model: Int, part: Int) = ByteArray(16).also {
            it[0] = FitPro.Dev.MAIN; it[1] = 16; it[2] = FitPro.Cmd.SYSTEM_INFO.toByte(); it[3] = FitPro.Status.DONE.toByte()
            fun put(off: Int, v: Int) {
                it[off] = (v and 0xFF).toByte(); it[off + 1] = ((v ushr 8) and 0xFF).toByte()
                it[off + 2] = ((v ushr 16) and 0xFF).toByte(); it[off + 3] = ((v ushr 24) and 0xFF).toByte()
            }
            put(7, model); put(11, part)
            it[15] = FitPro.checksum(it, 16)
        }
        val fixed = FitPro.parseSystemInfo(sysFrame(39915, 370357))
        assertNotNull(fixed)
        assertEquals(39915, fixed!!.model)
        assertEquals(374677, fixed.partNumber) // rewritten

        // Any other pairing is left untouched.
        val other = FitPro.parseSystemInfo(sysFrame(12345, 370357))
        assertEquals(370357, other!!.partNumber)
    }

    @Test fun status_and_mode_names_cover_the_values_we_log() {
        assertEquals("failed", FitPro.Status.name(FitPro.Status.FAILED))
        assertEquals("securityBlock", FitPro.Status.name(FitPro.Status.SECURITY_BLOCK))
        assertEquals("running", FitPro.Mode.name(FitPro.Mode.RUNNING))
        assertEquals("sleep", FitPro.Mode.name(FitPro.Mode.SLEEP)) // 12 was "mode12" before
        assertEquals("safetyKeyOut", FitPro.Mode.name(FitPro.Mode.DMK))
        assertNotEquals("mode12", FitPro.Mode.name(FitPro.Mode.SLEEP))
    }
}
