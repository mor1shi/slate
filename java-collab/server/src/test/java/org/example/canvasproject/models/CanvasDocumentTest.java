package org.example.canvasproject.models;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasDocumentTest {

    @Test
    void ownerIsOwnerEvenWithoutExplicitRoleEntry() {
        CanvasDocument canvas = new CanvasDocument("Board", "owner-1");

        assertEquals(CanvasDocument.ROLE_OWNER, canvas.roleOf("owner-1"));
        assertTrue(canvas.isOwner("owner-1"));
        assertTrue(canvas.canEdit("owner-1"));
    }

    @Test
    void memberWithoutRoleDefaultsToEditorForLegacyBoards() {
        CanvasDocument canvas = new CanvasDocument("Board", "owner-1");
        canvas.setMemberIds(List.of("owner-1", "legacy-user"));

        assertEquals(CanvasDocument.ROLE_EDITOR, canvas.roleOf("legacy-user"));
        assertTrue(canvas.canEdit("legacy-user"));
    }

    @Test
    void viewerCannotEdit() {
        CanvasDocument canvas = new CanvasDocument("Board", "owner-1");
        canvas.setMemberIds(List.of("owner-1", "viewer-1"));
        canvas.setMemberRoles(Map.of("viewer-1", CanvasDocument.ROLE_VIEWER));

        assertEquals(CanvasDocument.ROLE_VIEWER, canvas.roleOf("viewer-1"));
        assertFalse(canvas.canEdit("viewer-1"));
        assertFalse(canvas.isOwner("viewer-1"));
    }

    @Test
    void ownerCannotBeDowngradedByMemberRolesMap() {
        CanvasDocument canvas = new CanvasDocument("Board", "owner-1");
        canvas.setMemberRoles(Map.of("owner-1", CanvasDocument.ROLE_VIEWER));

        assertEquals(CanvasDocument.ROLE_OWNER, canvas.roleOf("owner-1"));
        assertTrue(canvas.canEdit("owner-1"));
    }

    @Test
    void nonMemberAndNullHaveNoRole() {
        CanvasDocument canvas = new CanvasDocument("Board", "owner-1");

        assertNull(canvas.roleOf("stranger"));
        assertNull(canvas.roleOf(null));
        assertFalse(canvas.canEdit("stranger"));
        assertFalse(canvas.isOwner(null));
    }

    @Test
    void defaultSheetSizeIsFullHd() {
        CanvasDocument canvas = new CanvasDocument("Board", "owner-1");

        assertEquals(1920, canvas.getCanvasWidth());
        assertEquals(1080, canvas.getCanvasHeight());
    }
}
