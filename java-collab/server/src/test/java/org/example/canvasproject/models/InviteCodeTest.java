package org.example.canvasproject.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteCodeTest {

    private static final long HOUR = 60L * 60L * 1000L;

    @Test
    void freshSingleUseCodeIsValid() {
        InviteCode invite = new InviteCode("ABCDEFGH", "canvas-1", "owner-1", HOUR, 1);

        assertTrue(invite.isValid());
        assertEquals(CanvasDocument.ROLE_EDITOR, invite.getRole());
        assertEquals(0, invite.getUsedCount());
    }

    @Test
    void codeStopsBeingValidAfterAllUsesAreSpent() {
        InviteCode invite = new InviteCode("ABCDEFGH", "canvas-1", "owner-1", HOUR, 2);
        invite.setUsedCount(1);
        assertTrue(invite.isValid());

        invite.setUsedCount(2);
        assertFalse(invite.isValid());
    }

    @Test
    void nonPositiveMaxUsesMeansUnlimitedUntilExpiry() {
        InviteCode invite = new InviteCode("ABCDEFGH", "canvas-1", "owner-1", HOUR, 0);
        invite.setUsedCount(10_000);

        assertTrue(invite.isValid());
    }

    @Test
    void expiredCodeIsInvalidEvenWithFreeUses() {
        InviteCode invite = new InviteCode("ABCDEFGH", "canvas-1", "owner-1", HOUR, 1);
        invite.setExpiresAt(System.currentTimeMillis() - 1);

        assertFalse(invite.isValid());
    }

    @Test
    void viewerRoleIsPreservedAndNullRoleFallsBackToEditor() {
        assertEquals(CanvasDocument.ROLE_VIEWER,
                new InviteCode("ABCDEFGH", "c", "o", CanvasDocument.ROLE_VIEWER, HOUR, 1).getRole());

        InviteCode legacy = new InviteCode();
        assertEquals(CanvasDocument.ROLE_EDITOR, legacy.getRole());
    }
}
