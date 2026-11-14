package io.github.bropines.birdsocks.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerCopyFormsTest {
    @Test
    fun everyFormOfANamedPeer() {
        val forms = peerCopyForms("laptop", "laptop.netbird.cloud.", "100.92.1.2", "fd00::2")
        assertEquals(
            listOf(
                PeerCopyKind.NAME to "laptop",
                PeerCopyKind.FQDN to "laptop.netbird.cloud",
                PeerCopyKind.IPV4 to "100.92.1.2",
                PeerCopyKind.IPV6 to "fd00::2",
                PeerCopyKind.SSH to "ssh laptop.netbird.cloud",
                PeerCopyKind.PING to "ping laptop.netbird.cloud",
            ),
            forms.map { it.kind to it.text }
        )
    }

    @Test
    fun aPeerWithoutANameIsReachedByItsAddress() {
        // shortName falls back to the address when the FQDN is empty.
        val forms = peerCopyForms("100.92.1.2", "", "100.92.1.2", "")
        assertEquals(
            listOf(
                PeerCopyKind.IPV4 to "100.92.1.2",
                PeerCopyKind.SSH to "ssh 100.92.1.2",
                PeerCopyKind.PING to "ping 100.92.1.2",
            ),
            forms.map { it.kind to it.text }
        )
    }

    @Test
    fun nothingToCopyGivesNoForms() {
        assertEquals(emptyList<PeerCopyForm>(), peerCopyForms("", "", "", ""))
    }
}
