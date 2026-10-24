package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.lowdragmc.photon.client.fx.instance.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class InstanceBakerTest {
    private static final Identifier A = fx("a");
    private static final Identifier B = fx("b");
    private static final Identifier C = fx("c");
    private static final UUID P = id("P");

    /** a1 ⊃ a2, a3; a3 refers to a1 and a2 the ways configs and lists do; a timeline bound to a2 and to the root. */
    static CompoundTag sourceA() {
        var a3 = emitter("a3", null);
        config(a3).putString("ref", id("a1").toString());
        var refs = new ListTag();
        refs.add(StringTag.valueOf(id("a2").toString()));
        config(a3).put("refs", refs);
        return fxDataWith(timeline(id("a2"), InstanceIds.ROOT),
                emitter("a1", null, id("a2")), emitter("a2", id("a1")), a3);
    }

    private static Sources sourcesWithA() {
        return new Sources().put(A, sourceA());
    }

    private static InstanceBaker.Result bake(InstanceTemplates templates, CompoundTag fxData) {
        return templates.bake(List.of(), fxData);
    }

    @Test
    void aPristineInstancePlacesTheSourceUnderItself() {
        var problems = new Problems();
        var result = bake(templates(sourcesWithA(), problems), fxData(instance("P", null, A, null)));

        assertEquals(List.of(P, placed(P, "a1"), placed(P, "a2"), placed(P, "a3")), ids(result.fxData()),
                "what an instance places follows it, in the source's order");
        var objects = byId(result.fxData());
        assertEquals(P, InstanceTags.parent(objects.get(placed(P, "a1"))), "the source's top hangs under the instance");
        assertEquals(placed(P, "a1"), InstanceTags.parent(objects.get(placed(P, "a2"))));
        assertEquals(P, InstanceTags.parent(objects.get(placed(P, "a3"))));
        var a3 = config(objects.get(placed(P, "a3")));
        assertEquals(placed(P, "a1").toString(), a3.getStringOr("ref", ""), "references follow the placed ids");
        assertEquals(placed(P, "a2").toString(), a3.getListOrEmpty("refs").getStringOr(0, ""));

        var instance = InstanceTags.data(objects.get(P));
        assertFalse(instance.contains(InstanceTags.OVERRIDES), "the baked form never carries a record");
        var timeline = instance.getCompoundOrEmpty(InstanceTags.TIMELINE);
        assertEquals(placed(P, "a2"), activatorTarget(timeline));
        assertEquals(P, clipTarget(timeline), "a track bound to the source's root drives the instance");
        assertEquals(List.of(placed(P, "a1"), placed(P, "a3")), InstanceTags.children(objects.get(P)));
        assertEquals(List.of(placed(P, "a2")), InstanceTags.children(objects.get(placed(P, "a1"))));

        var placement = result.placements().get(P);
        assertEquals(InstanceBaker.Status.PLACED, placement.status());
        assertTrue(placement.unused().isPristine());
        assertEquals(Set.of(A), result.dependencies());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(problems.all.isEmpty(), problems.all.toString());
    }

    /** Objects made in code save no parent at all when they are at the top; the editor saves the root's id. */
    @Test
    void aTopLevelObjectWithoutASavedParentHangsUnderTheInstance() {
        var top = emitter("t1", null, id("t2"));
        InstanceTags.transform(top).remove(InstanceTags.PARENT);
        var sources = new Sources().put(B, fxData(top, emitter("t2", id("t1"))));
        var result = bake(templates(sources, new Problems()), fxData(instance("P", null, B, null)));
        var objects = byId(result.fxData());
        assertEquals(P, InstanceTags.parent(objects.get(placed(P, "t1"))));
        assertEquals(placed(P, "t1"), InstanceTags.parent(objects.get(placed(P, "t2"))));
        var live = new ArrayList<>(InstanceTags.objects(result.fxData()));
        var file = InstanceWriter.write(live, null, result.placements(), id -> Set.of());
        assertEquals(List.of(P), ids(file), "what it placed is known as placed, so it is not written");
    }

    @Test
    void theRecordsChangesAreMadeOnTheSourcesValues() {
        var record = new InstanceRecord(List.of(
                set(id("a2"), IntTag.valueOf(7), "config", "duration"),
                set(id("a1"), StringTag.valueOf("renamed"), "name"),
                new InstanceRecord.Modification(id("a3"), NbtPatch.Change.remove(List.of("config", "looping")))),
                List.of(), List.of());
        var result = bake(templates(sourcesWithA(), new Problems()), fxData(instance("P", null, A, record.write())));
        var objects = byId(result.fxData());
        assertEquals(7, config(objects.get(placed(P, "a2"))).getIntOr("duration", 0));
        assertEquals("renamed", InstanceTags.name(objects.get(placed(P, "a1"))));
        assertFalse(config(objects.get(placed(P, "a3"))).contains("looping"));
        assertEquals(100, config(objects.get(placed(P, "a1"))).getIntOr("duration", 0), "the rest follows the source");
        var placement = result.placements().get(P);
        assertTrue(placement.unused().isPristine());
        assertEquals(3, placement.applied().values().stream().mapToInt(List::size).sum());
    }

    @Test
    void aRemovedObjectTakesItsSubtreeWithIt() {
        var record = new InstanceRecord(List.of(), List.of(id("a1")), List.of());
        var result = bake(templates(sourcesWithA(), new Problems()), fxData(instance("P", null, A, record.write())));
        assertEquals(List.of(P, placed(P, "a3")), ids(result.fxData()));
        assertEquals(List.of(placed(P, "a3")), InstanceTags.children(byId(result.fxData()).get(P)));
        assertTrue(result.placements().get(P).unused().isPristine());
    }

    @Test
    void whatDoesNotApplyIsKeptAsUnused() {
        var gone = set(id("nowhere"), IntTag.valueOf(1), "config", "duration");
        var brokenPath = set(id("a1"), IntTag.valueOf(1), "config", "absent", "deeper");
        var structural = set(id("a1"), StringTag.valueOf(id("x").toString()), "transform", "_parentId");
        var underRemoved = set(id("a2"), IntTag.valueOf(3), "config", "duration");
        var record = new InstanceRecord(List.of(gone, brokenPath, structural, underRemoved),
                List.of(id("a1"), id("never")), List.of());
        var result = bake(templates(sourcesWithA(), new Problems()), fxData(instance("P", null, A, record.write())));
        var unused = result.placements().get(P).unused();
        assertEquals(Set.of(gone, brokenPath, structural, underRemoved), Set.copyOf(unused.modified()));
        assertEquals(List.of(id("never")), unused.removed());
    }

    @Test
    void addedObjectsHangWhereTheRecordSaysAfterTheSourcesChildren() {
        var record = new InstanceRecord(List.of(), List.of(), List.of(
                new InstanceRecord.Added(id("a1"), id("c1")),
                new InstanceRecord.Added(InstanceIds.ROOT, id("d1")),
                new InstanceRecord.Added(id("a1"), id("c2"))));
        var outer = fxData(instance("P", null, A, record.write()),
                emitter("c2", placed(P, "a1")), emitter("c1", placed(P, "a1")), emitter("d1", P));
        var result = bake(templates(sourcesWithA(), new Problems()), outer);
        var objects = byId(result.fxData());
        assertEquals(List.of(placed(P, "a2"), id("c1"), id("c2")), InstanceTags.children(objects.get(placed(P, "a1"))),
                "added children come after the source's, in recorded order");
        assertEquals(List.of(placed(P, "a1"), placed(P, "a3"), id("d1")), InstanceTags.children(objects.get(P)));
        assertEquals(placed(P, "a1"), InstanceTags.parent(objects.get(id("c1"))));
        assertEquals(P, InstanceTags.parent(objects.get(id("d1"))));
    }

    @Test
    void anAddedObjectWhosePlaceIsGoneMovesUnderTheInstance() {
        var record = new InstanceRecord(List.of(), List.of(), List.of(new InstanceRecord.Added(id("vanished"), id("c1"))));
        var outer = fxData(instance("P", null, A, record.write()), emitter("c1", placed(P, "vanished")));
        var result = bake(templates(sourcesWithA(), new Problems()), outer);
        var objects = byId(result.fxData());
        assertEquals(P, InstanceTags.parent(objects.get(id("c1"))));
        assertTrue(InstanceTags.children(objects.get(P)).contains(id("c1")));
        assertEquals(1, result.problems().size(), result.problems().toString());
        assertEquals(record.added(), result.placements().get(P).unused().added(), "its anchor waits for its place");
    }

    @Test
    void anObjectAddedUnderARemovedObjectMovesUnderTheInstanceForGood() {
        var record = new InstanceRecord(List.of(), List.of(id("a1")), List.of(new InstanceRecord.Added(id("a2"), id("c1"))));
        var result = bake(templates(sourcesWithA(), new Problems()),
                fxData(instance("P", null, A, record.write()), emitter("c1", placed(P, "a2"))));
        assertEquals(P, InstanceTags.parent(byId(result.fxData()).get(id("c1"))));
        assertEquals(List.of(), result.placements().get(P).unused().added(),
                "its place was removed on purpose, so the anchor is not waiting for it");
    }

    @Test
    void anAnchorForAnObjectTheEffectDoesNotHaveIsIgnored() {
        var record = new InstanceRecord(List.of(), List.of(), List.of(new InstanceRecord.Added(id("a1"), id("ghost"))));
        var result = bake(templates(sourcesWithA(), new Problems()), fxData(instance("P", null, A, record.write())));
        assertEquals(List.of(P, placed(P, "a1"), placed(P, "a2"), placed(P, "a3")), ids(result.fxData()));
        assertEquals(List.of(placed(P, "a2")), InstanceTags.children(byId(result.fxData()).get(placed(P, "a1"))));
        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    @Test
    void aChangeToWhatHoldsTheStructureOrToANestedInstancesOwnKeysIsUnused() {
        var sources = new Sources()
                .put(C, fxData(emitter("c1", null)))
                .put(B, fxData(emitter("b1", null), instance("K", null, C, null)));
        var wholeTransform = set(id("b1"), new CompoundTag(), "transform");
        var nestedSource = set(id("K"), StringTag.valueOf("test:elsewhere"), "fx");
        var nestedRecord = set(id("K"), new CompoundTag(), "overrides");
        var record = new InstanceRecord(List.of(wholeTransform, nestedSource, nestedRecord), List.of(), List.of());
        var result = bake(templates(sources, new Problems()), fxData(instance("P", null, B, record.write())));
        assertEquals(Set.of(wholeTransform, nestedSource, nestedRecord), Set.copyOf(result.placements().get(P).unused().modified()));
        var objects = byId(result.fxData());
        assertEquals(P, InstanceTags.parent(objects.get(placed(P, "b1"))));
        assertEquals(placed(P, "b1"), InstanceTags.id(objects.get(placed(P, "b1"))));
        assertEquals(C.toString(), InstanceTags.data(objects.get(placed(P, "K"))).getStringOr(InstanceTags.FX, ""));
    }

    @Test
    void anInstanceSavedTwicePlacesOnce() {
        var result = bake(templates(sourcesWithA(), new Problems()),
                fxData(instance("P", null, A, null), instance("P", null, A, null)));
        assertEquals(List.of(P, placed(P, "a1"), placed(P, "a2"), placed(P, "a3"), P), ids(result.fxData()));
        assertEquals(1, result.problems().size(), result.problems().toString());
    }

    @Test
    void aPlacedIdTheEffectAlreadyUsesIsReported() {
        var clash = emitter("clash", null);
        InstanceTags.transform(clash).putString(InstanceTags.ID, placed(P, "a1").toString());
        var result = bake(templates(sourcesWithA(), new Problems()), fxData(instance("P", null, A, null), clash));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("already used")), result.problems().toString());
    }

    @Test
    void fourZerosInAnObjectAreAValueWhileTheTimelinesRootIsTheInstance() {
        var c1 = emitter("c1", null);
        config(c1).put("zeros", new IntArrayTag(new int[4]));
        var sources = new Sources().put(C, fxDataWith(timeline(id("c1"), InstanceIds.ROOT), c1));
        var objects = byId(bake(templates(sources, new Problems()), fxData(instance("P", null, C, null))).fxData());
        assertArrayEquals(new int[4], config(objects.get(placed(P, "c1"))).getIntArray("zeros").orElseThrow());
        assertEquals(P, clipTarget(InstanceTags.data(objects.get(P)).getCompoundOrEmpty(InstanceTags.TIMELINE)));
    }

    @Test
    void aParentLoopInTheSourceStillHangsUnderTheInstance() {
        var sources = new Sources().put(C, fxData(emitter("x", id("y"), id("y")), emitter("y", id("x"), id("x"))));
        var result = bake(templates(sources, new Problems()), fxData(instance("P", null, C, null)));
        var objects = byId(result.fxData());
        for (var name : List.of("x", "y")) {
            var at = placed(P, name);
            for (int depth = 0; !P.equals(at); depth++) {
                assertTrue(depth < 3, name + " reaches the instance");
                at = InstanceTags.parent(objects.get(at));
            }
        }
        var file = InstanceWriter.write(new ArrayList<>(InstanceTags.objects(result.fxData())), null, result.placements(),
                id -> Set.of());
        assertEquals(List.of(P), ids(file), "all of it is placed, so none of it is saved as the effect's own");
    }

    @Test
    void theInputIsNotModified() {
        var record = new InstanceRecord(List.of(set(id("a1"), IntTag.valueOf(7), "config", "duration")), List.of(id("a3")),
                List.of(new InstanceRecord.Added(id("a1"), id("c1"))));
        var outer = fxData(instance("P", null, A, record.write()), emitter("c1", placed(P, "a1")));
        var before = outer.copy();
        bake(templates(sourcesWithA(), new Problems()), outer);
        assertEquals(before, outer);
    }

    @Test
    void theSameSourceTwicePlacesTwoDisjointCopies() {
        var other = id("Q");
        var result = bake(templates(sourcesWithA(), new Problems()),
                fxData(instance("P", null, A, null), instance("Q", null, A, null)));
        var ids = ids(result.fxData());
        assertEquals(8, ids.size());
        assertEquals(8, Set.copyOf(ids).size(), "no id is placed twice");
        var objects = byId(result.fxData());
        assertEquals(placed(other, "a2"), activatorTarget(InstanceTags.data(objects.get(other)).getCompoundOrEmpty(InstanceTags.TIMELINE)));
        assertEquals(placed(P, "a2"), activatorTarget(InstanceTags.data(objects.get(P)).getCompoundOrEmpty(InstanceTags.TIMELINE)));
    }

    @Test
    void nestedInstancesComposeTheirIds() {
        var k = id("K");
        var sources = new Sources()
                .put(C, fxDataWith(timeline(id("c1"), InstanceIds.ROOT), emitter("c1", null)))
                .put(B, fxData(emitter("b1", null), instance("K", null, C, null)));
        // the outer FX changes an object two levels down, addressed by B's own id for it
        var deep = InstanceIds.xor(k, id("c1"));
        var record = new InstanceRecord(List.of(set(deep, IntTag.valueOf(42), "config", "duration")), List.of(), List.of());
        var result = bake(templates(sources, new Problems()), fxData(instance("P", null, B, record.write())));

        var objects = byId(result.fxData());
        var placedK = InstanceIds.xor(P, k);
        var placedC1 = InstanceIds.xor(placedK, id("c1"));
        assertEquals(placedC1, InstanceIds.xor(P, deep), "XOR composes");
        assertTrue(objects.containsKey(placed(P, "b1")));
        assertEquals(placedK, InstanceTags.parent(objects.get(placedC1)));
        assertEquals(P, InstanceTags.parent(objects.get(placedK)));
        assertEquals(42, config(objects.get(placedC1)).getIntOr("duration", 0));
        var nested = InstanceTags.data(objects.get(placedK));
        assertEquals(placedC1, activatorTarget(nested.getCompoundOrEmpty(InstanceTags.TIMELINE)),
                "the nested instance's own timeline is placed with it");
        assertEquals(placedK, clipTarget(nested.getCompoundOrEmpty(InstanceTags.TIMELINE)));
        assertFalse(nested.contains(InstanceTags.OVERRIDES));
        assertEquals(Set.of(B, C), result.dependencies());
        assertEquals(1, result.placements().size(), "only the outermost instance has a placement");
    }

    @Test
    void aMissingSourceKeepsTheInstanceAsSaved() {
        var record = new InstanceRecord(List.of(set(id("a1"), IntTag.valueOf(1), "config", "duration")), List.of(), List.of());
        var saved = record.write();
        var result = bake(templates(new Sources(), new Problems()), fxData(instance("P", null, A, saved)));
        assertEquals(List.of(P), ids(result.fxData()));
        var placement = result.placements().get(P);
        assertEquals(InstanceBaker.Status.MISSING, placement.status());
        assertEquals(saved, placement.saved());
        assertFalse(InstanceTags.data(byId(result.fxData()).get(P)).contains(InstanceTags.TIMELINE));
        assertEquals(Set.of(A), result.dependencies(), "a missing source is still depended on");
        assertEquals(1, result.problems().size());
    }

    @Test
    void anInstanceWithoutASourceIsBrokenNotFatal() {
        var instance = instance("P", null, A, null);
        InstanceTags.data(instance).remove(InstanceTags.FX);
        var result = bake(templates(sourcesWithA(), new Problems()), fxData(instance, emitter("e", null)));
        assertEquals(List.of(P, id("e")), ids(result.fxData()));
        assertEquals(InstanceBaker.Status.BROKEN, result.placements().get(P).status());
    }

    @Test
    void aSourceThatPlacesItselfIsACycleNotARecursion() {
        var problems = new Problems();
        var sources = new Sources().put(A, fxData(emitter("a1", null), instance("self", null, A, null)));
        var templates = templates(sources, problems);
        var result = templates.bake(List.of(A), sources.read(A));
        var placement = result.placements().get(id("self"));
        assertEquals(InstanceBaker.Status.CYCLIC, placement.status());
        assertNull(templates.cached(A), "a cycle's member is not cached");
        assertFalse(result.problems().isEmpty());
    }

    @Test
    void aMutualCycleIsCaughtFromEitherEnd() {
        var problems = new Problems();
        var sources = new Sources()
                .put(A, fxData(emitter("a1", null), instance("toB", null, B, null)))
                .put(B, fxData(emitter("b1", null), instance("toA", null, A, null)))
                .put(C, fxData(instance("holder", null, A, null)));
        var templates = templates(sources, problems);

        var fromA = templates.bake(List.of(A), sources.read(A));
        assertEquals(InstanceBaker.Status.PLACED, fromA.placements().get(id("toB")).status());
        var objects = byId(fromA.fxData());
        var placedToA = InstanceIds.xor(id("toB"), id("toA"));
        assertTrue(objects.containsKey(placedToA), "B is placed, and the instance closing the cycle is kept");
        assertEquals(4, objects.size(), "a1, toB, and what toB placed: b1 and the empty toA");
        assertTrue(objects.values().stream().noneMatch(o -> placedToA.equals(InstanceTags.parent(o))),
                "the instance closing the cycle places nothing");
        assertNull(templates.cached(A));
        assertNull(templates.cached(B));
        assertTrue(problems.all.stream().anyMatch(p -> p.contains("->")), problems.all.toString());

        var fromB = templates.bake(List.of(B), sources.read(B));
        assertEquals(InstanceBaker.Status.PLACED, fromB.placements().get(id("toA")).status());

        // a source holding the cycle bakes, and is cached
        var holder = templates.lookup(C);
        assertInstanceOf(InstanceBaker.Found.class, holder);
        assertNotNull(templates.cached(C));
    }

    @Test
    void aThreeWayCycleTerminates() {
        var sources = new Sources()
                .put(A, fxData(instance("ab", null, B, null)))
                .put(B, fxData(instance("bc", null, C, null)))
                .put(C, fxData(instance("ca", null, A, null)));
        var problems = new Problems();
        var templates = templates(sources, problems);
        var result = templates.bake(List.of(A), sources.read(A));
        assertEquals(InstanceBaker.Status.PLACED, result.placements().get(id("ab")).status());
        // A places B places C, whose instance of A closes the cycle and places nothing
        var objects = byId(result.fxData());
        var ab = id("ab");
        var bc = InstanceIds.xor(ab, id("bc"));
        var ca = InstanceIds.xor(bc, id("ca"));
        assertTrue(objects.containsKey(ca));
        assertEquals(3, objects.size());
        assertTrue(problems.all.stream().anyMatch(p -> p.contains(A + " -> " + B + " -> " + C + " -> " + A)),
                problems.all.toString());
    }

    @Test
    void aCachedTemplateDoesNotHideACycle() {
        var sources = new Sources().put(A, fxData(instance("ab", null, B, null))).put(B, fxData(emitter("b1", null)));
        var templates = templates(sources, new Problems());
        templates.lookup(A);
        var throughA = templates.bake(List.of(B), fxData(instance("x", null, A, null)));
        assertEquals(InstanceBaker.Status.CYCLIC, throughA.placements().get(id("x")).status(), "B places A, which places B");
        var self = templates.bake(List.of(A), fxData(instance("y", null, A, null)));
        assertEquals(InstanceBaker.Status.CYCLIC, self.placements().get(id("y")).status(), "A places itself");
        assertNotNull(templates.cached(A), "on its own, A is still fine");
    }

    @Test
    void cachedTemplatesFindNoCycleWhereThereIsNone() {
        var d = fx("d");
        var sources = new Sources()
                .put(d, fxData(emitter("d1", null)))
                .put(A, fxData(instance("ad", null, d, null)))
                .put(B, fxData(instance("bd", null, d, null)))
                .put(C, fxData(instance("ca", null, A, null), instance("cb", null, B, null), instance("cd", null, d, null)));
        var problems = new Problems();
        var templates = templates(sources, problems);
        templates.lookup(d);
        templates.lookup(A);
        var result = templates.bake(List.of(C), sources.read(C));
        for (var name : List.of("ca", "cb", "cd")) {
            assertEquals(InstanceBaker.Status.PLACED, result.placements().get(id(name)).status(),
                    name + ": two paths to the same source and the same source at two depths are no cycle");
        }
        assertTrue(problems.all.isEmpty(), problems.all.toString());
    }

    @Test
    void eachCycleIsReportedOnce() {
        var sources = new Sources().put(A, fxData(instance("self", null, A, null)));
        var problems = new Problems();
        var templates = templates(sources, problems);
        templates.lookup(A);
        templates.lookup(A);
        templates.lookup(A);
        assertEquals(1, problems.all.size(), problems.all.toString());
        assertEquals(3, sources.reads.get(A), "a cycle's member is baked again each time, never cached");
    }

    @Test
    void sourceChangesFlowIntoWhatIsNotOverridden() {
        var sources = sourcesWithA();
        var templates = templates(sources, new Problems());
        var record = new InstanceRecord(List.of(set(id("a1"), IntTag.valueOf(5), "config", "duration")), List.of(), List.of());
        var outer = fxData(instance("P", null, A, record.write()));
        bake(templates, outer);

        var changed = sourceA();
        for (var object : InstanceTags.objects(changed)) {
            config(object).putInt("duration", 300);
        }
        sources.put(A, changed);
        assertEquals(Set.of(A), templates.invalidate(List.of(A)));
        var objects = byId(bake(templates, outer).fxData());
        assertEquals(5, config(objects.get(placed(P, "a1"))).getIntOr("duration", 0), "the override stays");
        assertEquals(300, config(objects.get(placed(P, "a2"))).getIntOr("duration", 0), "the rest follows the source");
    }

    @Test
    void invalidatingASourceForgetsEverythingThatPlacesIt() {
        var sources = new Sources()
                .put(A, sourceA())
                .put(B, fxData(instance("ba", null, A, null)))
                .put(C, fxData(instance("cb", null, B, null)));
        var templates = templates(sources, new Problems());
        templates.lookup(C);
        templates.noteDependencies(fx("definition"), Set.of(C));
        assertNotNull(templates.cached(A));
        assertNotNull(templates.cached(B));
        assertEquals(Set.of(A, B, C, fx("definition")), templates.invalidate(List.of(A)));
        assertNull(templates.cached(A));
        assertNull(templates.cached(B));
        assertNull(templates.cached(C));
        // and a template is baked once until then
        templates.lookup(C);
        templates.lookup(C);
        assertEquals(2, sources.reads.get(C));
    }

    @Test
    void theClosureOfABakedSourceIsWhatItsTemplateKnows() {
        var sources = new Sources().put(A, sourceA()).put(B, fxData(instance("ba", null, A, null)));
        var templates = templates(sources, new Problems());
        templates.lookup(B);
        var reads = Map.copyOf(sources.reads);
        assertEquals(Set.of(A), templates.closure(B));
        assertEquals(reads, sources.reads, "nothing was read again");
    }

    @Test
    void aSourceBakedAgainUnchangedHasTheSameContent() {
        var sources = sourcesWithA();
        var templates = templates(sources, new Problems());
        var first = ((InstanceBaker.Found) templates.lookup(A)).template();
        templates.invalidate(List.of(A));
        var again = ((InstanceBaker.Found) templates.lookup(A)).template();
        assertNotSame(first, again);
        assertTrue(first.sameContent(again));
        var changed = sourceA();
        config(InstanceTags.objects(changed).getFirst()).putInt("duration", 1);
        sources.put(A, changed);
        templates.invalidate(List.of(A));
        assertFalse(first.sameContent(((InstanceBaker.Found) templates.lookup(A)).template()));
    }

    @Test
    void theClosureIsReadFromWhatIsSaved() {
        var sources = new Sources()
                .put(A, sourceA())
                .put(B, fxData(instance("ba", null, A, null)))
                .put(C, fxData(instance("cb", null, B, null), instance("missing", null, fx("nothing"), null)));
        var templates = templates(sources, new Problems());
        assertEquals(Set.of(B, A, fx("nothing")), templates.closure(C));
        assertNull(templates.cached(C), "the closure bakes nothing");
    }
}
