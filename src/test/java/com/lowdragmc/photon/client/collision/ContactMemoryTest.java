package com.lowdragmc.photon.client.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ContactMemoryTest {

    @Test
    void withoutACooldownATargetReportsOnce() {
        var memory = new ContactMemory();
        assertTrue(memory.tryReport(7, 0, 0));
        assertFalse(memory.tryReport(7, 0, 0));
        assertFalse(memory.tryReport(7, 100_000, 0));
    }

    @Test
    void aCooldownLetsItReportAgainOnceItHasPassed() {
        var memory = new ContactMemory();
        assertTrue(memory.tryReport(7, 10, 20));
        assertFalse(memory.tryReport(7, 29, 20), "19 ticks later");
        assertTrue(memory.tryReport(7, 30, 20), "20 ticks later");
        assertFalse(memory.tryReport(7, 31, 20), "the cooldown restarts from the last report");
        assertTrue(memory.tryReport(7, 50, 20));
    }

    @Test
    void targetsAreIndependent() {
        var memory = new ContactMemory();
        assertTrue(memory.tryReport(1, 0, 0));
        assertTrue(memory.tryReport(2, 0, 0));
        assertTrue(memory.tryReport(-5, 0, 0), "identities may be negative (packed block positions)");
        assertFalse(memory.tryReport(1, 0, 0));
    }

    @Test
    void clearingForgetsEverything() {
        var memory = new ContactMemory();
        memory.tryReport(1, 0, 0);
        memory.clear();
        assertTrue(memory.tryReport(1, 0, 0));
    }

    @Test
    void targetsPastTheirCooldownAreForgottenWithoutChangingAnswers() {
        var memory = new ContactMemory();
        for (int i = 0; i < 1000; i++) assertTrue(memory.tryReport(i, 0, 10));
        for (int i = 1000; i < 2000; i++) assertTrue(memory.tryReport(i, 20, 10));
        assertEquals(1000, memory.size(), "the targets from tick 0 were forgotten at tick 20");
        assertFalse(memory.tryReport(1500, 25, 10), "a target still cooling down is remembered");
        assertTrue(memory.tryReport(3, 25, 10), "a forgotten target reports as it would have anyway");
        assertFalse(memory.tryReport(3, 26, 10));
    }

    @Test
    void sharedByDifferentCooldownsEachReportKeepsItsOwn() {
        var memory = new ContactMemory();
        assertTrue(memory.tryReport(-1, 0, 0), "once and for all");
        assertTrue(memory.tryReport(-2, 0, 500));
        for (int i = 0; i < 3000; i++) assertTrue(memory.tryReport(i, i, 1), "another emitter, a 1 tick cooldown");
        assertFalse(memory.tryReport(-1, 3000, 1), "a short cooldown's pruning keeps the report made for good");
        assertTrue(memory.tryReport(-2, 3000, 1), "and lets the 500 tick one expire on time");
        assertFalse(memory.tryReport(-2, 3000, 1));
    }

    @Test
    void withoutACooldownNothingIsForgotten() {
        var memory = new ContactMemory();
        for (int i = 0; i < 5000; i++) memory.tryReport(i, i, 0);
        assertEquals(5000, memory.size());
        assertFalse(memory.tryReport(0, 1_000_000, 0));
    }

    @Test
    void anEventsIdentityMatchesItsContacts() {
        var contact = new Contact();
        contact.kind = Contact.ENTITY;
        contact.key = 42;
        var event = CollisionEvent.of(CollisionEvent.Type.IMPACT, contact, 0.5);
        assertEquals(contact.identity(), event.identity());
        assertEquals(42, event.key());
    }
}
