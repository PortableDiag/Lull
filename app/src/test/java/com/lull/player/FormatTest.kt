package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conversions with exact right answers. They look too small to test right up until a track
 * over an hour long shows up as `1:01` and nobody can say whether that is a minute or an hour.
 */
class FormatTest {

    @Test
    fun `durations below an hour are m colon ss`() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("0:01", Format.duration(1_000))
        assertEquals("0:59", Format.duration(59_999))
        assertEquals("1:00", Format.duration(60_000))
        assertEquals("5:21", Format.duration(321_000))
        assertEquals("59:59", Format.duration(3_599_000))
    }

    @Test
    fun `an hour brings a third field, with the minutes zero-padded`() {
        assertEquals("1:00:00", Format.duration(3_600_000))
        assertEquals("1:01:01", Format.duration(3_661_000))
        assertEquals("2:30:00", Format.duration(9_000_000))
    }

    /** A duration MediaStore never filled in arrives as 0 or -1; neither may render as a time. */
    @Test
    fun `a missing duration is not rendered as a negative time`() {
        assertEquals("0:00", Format.duration(-1))
        assertEquals("0:00", Format.duration(Long.MIN_VALUE))
    }

    @Test
    fun `sizes step up a unit at 1024, not 1000`() {
        assertEquals("", Format.size(0))
        assertEquals("512 B", Format.size(512))
        assertEquals("1000 B", Format.size(1000))
        assertEquals("1.0 KB", Format.size(1024))
        assertEquals("1.0 MB", Format.size(1024L * 1024))
        assertEquals("1.5 MB", Format.size(1024L * 1536))
        assertEquals("1.0 GB", Format.size(1024L * 1024 * 1024))
    }
}
