package com.movie.ticket_booking.service.ticket;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TicketQrServiceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef-qr";
    private final TicketQrService qr = new TicketQrServiceImpl(SECRET);

    @Test
    void aSignedPayloadVerifiesToItsTicketId() {
        String payload = qr.sign(42L, Instant.now().plusSeconds(3600));

        assertTrue(payload.startsWith("CETA1.42."));
        assertEquals(42L, qr.verify(payload));
        assertEquals(42L, qr.verify("  " + payload + "\n"), "scanners may add whitespace");
    }

    @Test
    void anyTamperingIsRejected() {
        Instant exp = Instant.now().plusSeconds(3600);
        String[] parts = qr.sign(42L, exp).split("\\.");

        for (String forged : new String[]{
                "CETA1.43." + parts[2] + "." + parts[3],                          // another ticket
                "CETA1.42." + (exp.getEpochSecond() + 86400) + "." + parts[3],    // longer validity
                "CETA1.42." + parts[2] + "." + parts[3].substring(1) + "A",       // bad signature
                "CETA2.42." + parts[2] + "." + parts[3],                          // unknown version
                "CETA1.42." + parts[2],                                           // missing part
                "CETA1.x." + parts[2] + "." + parts[3],                           // not a number
                "", "garbage"}) {
            assertThrows(TicketQrService.InvalidQrException.class, () -> qr.verify(forged), forged);
        }
        assertThrows(TicketQrService.InvalidQrException.class, () -> qr.verify(null));
    }

    @Test
    void anotherSecretCannotForgeOrAccept() {
        String foreign = new TicketQrServiceImpl("another-secret-another-secret-12345").sign(42L, Instant.now().plusSeconds(60));

        assertThrows(TicketQrService.InvalidQrException.class, () -> qr.verify(foreign));
    }

    @Test
    void anExpiredPayloadIsRejectedEvenWithAGenuineSignature() {
        String old = qr.sign(42L, Instant.now().minusSeconds(5));

        TicketQrService.InvalidQrException ex = assertThrows(TicketQrService.InvalidQrException.class, () -> qr.verify(old));
        assertEquals("expired", ex.getMessage());
    }

    @Test
    void aWeakSecretIsRefusedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new TicketQrServiceImpl("short"));
    }
}
