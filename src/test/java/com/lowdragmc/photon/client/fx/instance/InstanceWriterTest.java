package com.lowdragmc.photon.client.fx.instance;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.lowdragmc.photon.client.fx.instance.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class InstanceWriterTest {
    private static final ResourceLocation A = fx("a");
    private static final ResourceLocation B = fx("b");
    private static final UUID P = id("P");

    private static InstanceTemplates templates() {
        return Fixtures.templates(new Sources().put(A, InstanceBakerTest.sourceA()), new Problems());
    }

    private static InstanceBaker.Result bake(InstanceTemplates templates, CompoundTag... outer) {
        return templates.bake(List.of(), fxData(outer));
    }

    /** The baked objects as an editor holds them: copies it is free to change. */
    private static List<CompoundTag> live(InstanceBaker.Result result) {
        var out = new ArrayList<CompoundTag>();
        for (var object : InstanceTags.objects(result.fxData())) out.add(object.copy());
        return out;
    }

    private static CompoundTag find(List<CompoundTag> objects, UUID id) {
        return objects.stream().filter(o -> id.equals(InstanceTags.id(o))).findFirst().orElseThrow();
    }

    private static CompoundTag write(InstanceBaker.Result result, List<CompoundTag> live) {
        return write(result.placements(), live, id -> Set.of());
    }

    private static CompoundTag write(Map<UUID, InstanceBaker.Placement> placements, List<CompoundTag> live,
                                     Function<UUID, Set<List<String>>> skipped) {
        return InstanceWriter.write(live, null, placements, skipped);
    }

    private static InstanceRecord recordOf(CompoundTag fxData, UUID instance) {
        var data = InstanceTags.data(find(InstanceTags.objects(fxData), instance));
        return InstanceRecord.read(data.contains(InstanceTags.OVERRIDES) ? data.getCompound(InstanceTags.OVERRIDES) : null);
    }

    private static InstanceRecord.Modification only(InstanceRecord record) {
        assertEquals(1, record.modified().size(), record.toString());
        return record.modified().getFirst();
    }

    @Test
    void aPristineInstanceSavesOnlyItself() {
        var result = bake(templates(), instance("P", null, A, null));
        var file = write(result, live(result));
        assertEquals(List.of(P), ids(file), "what the instance placed is not saved");
        var data = InstanceTags.data(InstanceTags.objects(file).getFirst());
        assertFalse(data.contains(InstanceTags.OVERRIDES));
        assertFalse(data.contains(InstanceTags.TIMELINE), "the source's timeline is the source's");
        assertEquals(List.of(), InstanceTags.children(InstanceTags.objects(file).getFirst()));
    }

    @Test
    void anEditedValueIsRecordedAndNothingElse() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        config(find(live, placed(P, "a2"))).putInt("duration", 9);
        var modification = only(recordOf(write(result, live), P));
        assertEquals(id("a2"), modification.target(), "targets are the source's ids");
        assertEquals(NbtPatch.Change.set(List.of("config", "duration"), IntTag.valueOf(9)), modification.change());
    }

    @Test
    void aValueSetBackToTheSourcesIsNotAnOverride() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        var config = config(find(live, placed(P, "a2")));
        config.putInt("duration", 9);
        config.putInt("duration", 100);
        assertTrue(recordOf(write(result, live), P).isPristine());
    }

    @Test
    void whatTheStructureOwnsIsNeverRecorded() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        var a1 = find(live, placed(P, "a1"));
        // a child added below changes _childrenId; the type stamps a version
        InstanceTags.setChildren(a1, List.of(placed(P, "a2"), id("someone")));
        InstanceTags.data(a1).putInt(InstanceTags.VERSION, 3);
        InstanceTags.transform(a1).put("localPosition", floats(1, 2, 3));
        Function<UUID, Set<List<String>>> animated = id -> id.equals(placed(P, "a1"))
                ? Set.of(InstanceDiffRules.POSITION) : Set.of();
        assertTrue(recordOf(write(result.placements(), live, animated), P).isPristine(),
                "a transform channel a timeline drives is the timeline's");
        assertFalse(recordOf(write(result.placements(), live, id -> Set.of()), P).isPristine());
    }

    @Test
    void aNestedInstancesSourceRecordAndTimelineBelongToItsOwnLevel() {
        var sources = new Sources()
                .put(A, InstanceBakerTest.sourceA())
                .put(B, fxData(instance("K", null, A, null)));
        var result = Fixtures.templates(sources, new Problems()).bake(List.of(), fxData(instance("P", null, B, null)));
        var live = live(result);
        var nested = InstanceTags.data(find(live, InstanceIds.xor(P, id("K"))));
        nested.putString(InstanceTags.FX, "test:elsewhere");
        nested.put(InstanceTags.TIMELINE, new CompoundTag());
        nested.put(InstanceTags.OVERRIDES, new CompoundTag());
        assertTrue(recordOf(write(result, live), P).isPristine());
    }

    @Test
    void someValuesOnlyChangeWhole() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        var a1 = find(live, placed(P, "a1"));
        InstanceTags.transform(a1).put("localRotation", floats(0, 0.7071f, 0, 0.7071f));
        config(a1).put("startLifetime", curve(0, 1, 1, 0));
        var noise = new CompoundTag();
        noise.putBoolean("_enable", false);
        config(a1).put("noise", noise);
        var changes = recordOf(write(result, live), P).modified().stream()
                .map(InstanceRecord.Modification::change).toList();
        assertEquals(List.of(
                NbtPatch.Change.set(List.of("config", "noise"), noise),
                NbtPatch.Change.set(List.of("config", "startLifetime"), curve(0, 1, 1, 0)),
                NbtPatch.Change.set(List.of("transform", "localRotation"), floats(0, 0.7071f, 0, 0.7071f))), changes);
    }

    @Test
    void pointingAtAnotherResourceChangesTheWholeReference() {
        var c = fx("c");
        for (var kind : List.of(List.of("ui_resource_material", "resourcePath"), List.of("resource_mesh", "resourcePath"),
                List.of("shader_graph", "graphPath"), List.of("custom_shader", "shaderLocation"))) {
            var source = emitter("c1", null);
            config(source).put("material", reference(kind, "test:one", 1));
            var result = Fixtures.templates(new Sources().put(c, fxData(source)), new Problems())
                    .bake(List.of(), fxData(instance("P", null, c, null)));
            var live = live(result);
            var config = config(find(live, placed(P, "c1")));
            config.put("material", reference(kind, "test:two", 1));
            assertEquals(NbtPatch.Change.set(List.of("config", "material"), reference(kind, "test:two", 1)),
                    only(recordOf(write(result, live), P)).change(), kind.getFirst());
            config.put("material", reference(kind, "test:one", 2));
            assertEquals(NbtPatch.Change.set(List.of("config", "material", "data", "tuning"), IntTag.valueOf(2)),
                    only(recordOf(write(result, live), P)).change(), "tuning the same " + kind.getFirst() + " stays a value");
        }
    }

    private static CompoundTag reference(List<String> kind, String target, int tuning) {
        var data = new CompoundTag();
        data.putString(kind.get(1), target);
        data.putInt("tuning", tuning);
        var reference = new CompoundTag();
        reference.putString(InstanceTags.TYPE, kind.getFirst());
        reference.put(InstanceTags.DATA, data);
        return reference;
    }

    @Test
    void onlyTheTopOfARemovedSubtreeIsRecorded() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        live.remove(find(live, placed(P, "a1")));
        live.remove(find(live, placed(P, "a2")));
        assertEquals(List.of(id("a1")), recordOf(write(result, live), P).removed());

        var onlyChild = live(result);
        onlyChild.remove(find(onlyChild, placed(P, "a2")));
        assertEquals(List.of(id("a2")), recordOf(write(result, onlyChild), P).removed());
    }

    @Test
    void addedObjectsAreSavedAsObjectsAndHungByTheRecord() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        live.add(emitter("c1", placed(P, "a1")));
        live.add(emitter("d1", P));
        live.add(emitter("c2", placed(P, "a1")));
        var a1 = find(live, placed(P, "a1"));
        InstanceTags.setChildren(a1, List.of(placed(P, "a2"), id("c2"), id("c1")));
        InstanceTags.setChildren(find(live, P), List.of(placed(P, "a1"), placed(P, "a3"), id("d1")));

        var file = write(result, live);
        assertEquals(List.of(P, id("c1"), id("d1"), id("c2")), ids(file));
        assertEquals(List.of(new InstanceRecord.Added(InstanceIds.ROOT, id("d1")),
                        new InstanceRecord.Added(id("a1"), id("c2")),
                        new InstanceRecord.Added(id("a1"), id("c1"))),
                recordOf(file, P).added(), "in sibling order");
        assertEquals(List.of(id("d1")), InstanceTags.children(find(InstanceTags.objects(file), P)));
    }

    @Test
    void unusedEntriesAreWrittenBackUnlessALiveValueSupersedesThem() {
        var gone = set(id("nowhere"), IntTag.valueOf(1), "config", "duration");
        var refused = set(id("a1"), StringTag.valueOf("old"), "config", "renamedField");
        var result = bake(templates(), instance("P", null, A,
                new InstanceRecord(List.of(gone), List.of(id("never")), List.of()).write()));
        var placement = result.placements().get(P).withUnused(List.of(refused));
        var placements = Map.of(P, placement);

        var record = recordOf(write(placements, live(result), id -> Set.of()), P);
        assertEquals(List.of(gone, refused), record.modified());
        assertEquals(List.of(id("never")), record.removed());

        var live = live(result);
        config(find(live, placed(P, "a1"))).putString("renamedField", "new");
        var superseded = recordOf(write(placements, live, id -> Set.of()), P);
        assertEquals(List.of(set(id("a1"), StringTag.valueOf("new"), "config", "renamedField"), gone),
                superseded.modified());
    }

    @Test
    void anUnusedEntryALiveChangeOverlapsIsDropped() {
        var result = bake(templates(), instance("P", null, A, null));
        var inside = set(id("a1"), FloatTag.valueOf(0.2f), "config", "noise", "strength");
        var around = set(id("a2"), constant(3), "config", "startLifetime");
        var placements = Map.of(P, result.placements().get(P).withUnused(List.of(inside, around)));
        var live = live(result);
        var noise = config(find(live, placed(P, "a1"))).getCompound("noise");
        noise.putBoolean("_enable", false);
        noise.remove("strength");
        config(find(live, placed(P, "a2"))).getCompound("startLifetime").getCompound("data").putFloat("value", 9);

        var templates = templates();
        var file = write(placements, live, id -> Set.of());
        assertEquals(List.of(
                set(id("a1"), noise, "config", "noise"),
                set(id("a2"), FloatTag.valueOf(9), "config", "startLifetime", "data", "value")), recordOf(file, P).modified(),
                "a value at, above or under an old entry's path says what the object is now");
        var objects = byId(templates.bake(List.of(), file).fxData());
        assertFalse(config(objects.get(placed(P, "a1"))).getCompound("noise").contains("strength"));
        assertEquals(9, config(objects.get(placed(P, "a2"))).getCompound("startLifetime").getCompound("data").getFloat("value"));
    }

    @Test
    void placedObjectsAreKnownByIdWhereverTheyHang() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        // a runtime parents an object whose parent it cannot find to its root
        InstanceTags.setParent(find(live, placed(P, "a2")), InstanceIds.ROOT);
        var file = write(result, live);
        assertEquals(List.of(P), ids(file), "it is still placed, so it is not saved as one of the effect's own");
        assertTrue(recordOf(file, P).isPristine(), recordOf(file, P).toString());
    }

    @Test
    void aPlacedObjectThatCouldNotBeReadIsNotSavedAsRemoved() {
        var record = new InstanceRecord(List.of(set(id("a1"), IntTag.valueOf(5), "config", "duration")), List.of(), List.of());
        var result = bake(templates(), instance("P", null, A, record.write()));
        var a1Changes = result.placements().get(P).applied().get(id("a1"));
        var placement = result.placements().get(P).withUnreadable(Set.of(id("a1")))
                .withUnused(a1Changes.stream().map(change -> new InstanceRecord.Modification(id("a1"), change)).toList());
        var live = live(result);
        live.remove(find(live, placed(P, "a1")));

        var file = write(Map.of(P, placement), live, id -> Set.of());
        var saved = recordOf(file, P);
        assertEquals(List.of(), saved.removed(), "not removed: it could not be read");
        assertEquals(record.modified(), saved.modified(), "and its changes are kept");
        assertEquals(List.of(P), ids(file));
    }

    @Test
    void anAnchorWhosePlaceWasNotFoundIsKeptWhileItsObjectWaitsUnderTheInstance() {
        var anchor = new InstanceRecord.Added(id("vanished"), id("c1"));
        var templates = templates();
        var result = templates.bake(List.of(), fxData(instance("P", null, A,
                new InstanceRecord(List.of(), List.of(), List.of(anchor)).write()), emitter("c1", placed(P, "vanished"))));
        assertEquals(List.of(anchor), result.placements().get(P).unused().added());
        var live = live(result);
        assertEquals(List.of(anchor), recordOf(write(result, live), P).added(), "kept for when its place is back");

        var c1 = find(live, id("c1"));
        InstanceTags.setParent(c1, placed(P, "a1"));
        InstanceTags.setChildren(find(live, P), List.of(placed(P, "a1"), placed(P, "a3")));
        InstanceTags.setChildren(find(live, placed(P, "a1")), List.of(placed(P, "a2"), id("c1")));
        assertEquals(List.of(new InstanceRecord.Added(id("a1"), id("c1"))), recordOf(write(result, live), P).added(),
                "hung somewhere else by hand, it is where it is now");
    }

    @Test
    void childrenAFileDoesNotListComeLastAsInARuntime() {
        var c = fx("c");
        var top = emitter("t", null, id("listed"));
        var source = fxData(top, emitter("unlisted", id("t")), emitter("listed", id("t")));
        var result = Fixtures.templates(new Sources().put(c, source), new Problems())
                .bake(List.of(), fxData(instance("P", null, c, null)));
        assertEquals(List.of(placed(P, "listed"), placed(P, "unlisted")),
                InstanceTags.children(byId(result.fxData()).get(placed(P, "t"))));

        var live = live(result);
        live.add(emitter("x2", placed(P, "t")));
        live.add(emitter("x1", placed(P, "t")));
        InstanceTags.setChildren(find(live, placed(P, "t")), List.of(placed(P, "listed"), placed(P, "unlisted"), id("x1")));
        assertEquals(List.of(new InstanceRecord.Added(id("t"), id("x1")), new InstanceRecord.Added(id("t"), id("x2"))),
                recordOf(write(result, live), P).added(), "listed first, then the rest in list order");
    }

    @Test
    void recordOfAnUnplacedInstanceIsWhatWasRead() {
        var saved = new InstanceRecord(List.of(set(id("a1"), IntTag.valueOf(1), "config", "duration")), List.of(), List.of());
        var result = Fixtures.templates(new Sources(), new Problems()).bake(List.of(), fxData(instance("P", null, A, saved.write())));
        assertEquals(saved, InstanceWriter.recordOf(live(result), result.placements().get(P), id -> Set.of()));
    }

    @Test
    void anUnplacedInstanceSavesWhatWasRead() {
        var saved = new InstanceRecord(List.of(set(id("a1"), IntTag.valueOf(1), "config", "duration")),
                List.of(), List.of()).write();
        var result = Fixtures.templates(new Sources(), new Problems())
                .bake(List.of(), fxData(instance("P", null, A, saved)));
        var file = write(result, live(result));
        assertEquals(saved, InstanceTags.data(find(InstanceTags.objects(file), P)).getCompound(InstanceTags.OVERRIDES));
    }

    @Test
    void theSameObjectsAlwaysSaveTheSameWay() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        config(find(live, placed(P, "a3"))).putInt("zeta", 1);
        config(find(live, placed(P, "a3"))).putInt("alpha", 1);
        config(find(live, placed(P, "a1"))).putInt("duration", 2);
        live.remove(find(live, placed(P, "a2")));
        assertEquals(write(result, live), write(result, live(bake(templates(), instance("P", null, A, null)))
                .stream().filter(o -> !placed(P, "a2").equals(InstanceTags.id(o)))
                .map(o -> {
                    var id = InstanceTags.id(o);
                    if (placed(P, "a3").equals(id)) {
                        config(o).putInt("alpha", 1);
                        config(o).putInt("zeta", 1);
                    } else if (placed(P, "a1").equals(id)) {
                        config(o).putInt("duration", 2);
                    }
                    return o;
                }).toList()));
    }

    /** {@code write(bake(f)) == f} for a saved file, and baking it again gives the same objects. */
    @Test
    void savingWhatWasReadGivesTheFileBack() {
        var templates = templates();
        var edited = bake(templates, instance("P", null, A, null), instance("Q", placed(P, "a1"), A, null));
        var live = live(edited);
        config(find(live, placed(P, "a2"))).putInt("duration", 7);
        InstanceTags.transform(find(live, placed(P, "a3"))).put("localPosition", floats(0, 1, 0));
        live.remove(find(live, placed(id("Q"), "a3")));
        live.add(emitter("added", placed(P, "a1")));
        var a1 = find(live, placed(P, "a1"));
        InstanceTags.setChildren(a1, List.of(placed(P, "a2"), id("Q"), id("added")));
        var file = write(edited, live);

        var read = templates.bake(List.of(), file);
        assertEquals(file, write(read, live(read)), "saving what was read gives the file back");
        var again = templates.bake(List.of(), write(read, live(read)));
        assertEquals(read.fxData(), again.fxData());
        var objects = byId(read.fxData());
        assertEquals(7, config(objects.get(placed(P, "a2"))).getInt("duration"));
        assertEquals(placed(P, "a1"), InstanceTags.parent(objects.get(id("Q"))),
                "an instance placed under another instance's object stays there");
        assertFalse(objects.containsKey(placed(id("Q"), "a3")));
        assertTrue(objects.containsKey(placed(id("Q"), "a1")));
        assertEquals(List.of(new InstanceRecord.Added(id("a1"), id("Q")), new InstanceRecord.Added(id("a1"), id("added"))),
                recordOf(file, P).added());
        assertEquals(List.of(id("a3")), recordOf(file, id("Q")).removed());
    }

    @Test
    void recordOfAndPlacedByAgreeWithTheWriter() {
        var result = bake(templates(), instance("P", null, A, null));
        var live = live(result);
        config(find(live, placed(P, "a1"))).putInt("duration", 4);
        var placement = result.placements().get(P);
        assertEquals(recordOf(write(result, live), P), InstanceWriter.recordOf(live, placement, id -> Set.of()));
        var placed = InstanceWriter.placedBy(live, placement);
        assertEquals(Map.of(placed(P, "a1"), id("a1"), placed(P, "a2"), id("a2"), placed(P, "a3"), id("a3")), placed);
    }

    @Test
    void aSwitchedOffGroupIsOneChangeAndTurningItBackOnIsNone() {
        var templates = templates();
        var result = bake(templates, instance("P", null, A, null));
        var live = live(result);
        var noise = config(find(live, placed(P, "a2"))).getCompound("noise");
        // a switched-off group saves only its switch
        noise.putBoolean("_enable", false);
        noise.remove("strength");
        var file = write(result, live);
        assertEquals(1, recordOf(file, P).modified().size(), recordOf(file, P).toString());

        var read = templates.bake(List.of(), file);
        var readLive = live(read);
        var placedNoise = config(find(readLive, placed(P, "a2"))).getCompound("noise");
        assertEquals(ByteTag.ZERO, placedNoise.get("_enable"));
        assertFalse(placedNoise.contains("strength"));

        placedNoise.putBoolean("_enable", true);
        placedNoise.putFloat("strength", 0.5f);
        assertTrue(recordOf(write(read, readLive), P).isPristine());
    }
}
