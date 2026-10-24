package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXData;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXInstances;
import com.lowdragmc.photon.client.fx.instance.InstanceBaker;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceRecord;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.instance.InstanceTemplate;
import com.lowdragmc.photon.client.fx.instance.InstanceWriter;
import com.lowdragmc.photon.client.fx.instance.NbtPatch;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The editor's side of FX instances (Unity's prefab instances): which objects of the hierarchy an instance
 * placed, what it overrides, and the edits that only make sense on them. Overrides are not tracked as edits
 * happen: they are what the objects differ from their template in, worked out when asked (and kept for a
 * moment, since rows and hierarchy lines ask every frame). Every edit here goes through the history, and a
 * value edit never moves an object: what hangs where only changes through the transforms.
 */
public final class FXInstanceEditing {
    public enum Role {
        NONE,
        INSTANCE,
        /** An instance whose source could not be placed; it keeps its saved record. */
        MISSING,
        PLACED,
        /** An object of this effect hanging inside an instance. */
        ADDED
    }

    private static final long ROLES_MS = 100;
    private static final long RECORDS_MS = 250;

    private final FXEditor editor;
    private final Map<IFXObject, Role> roles = new IdentityHashMap<>();
    private final Map<IFXObject, FXInstanceObject> owners = new IdentityHashMap<>();
    /** Per instance, the tops of what this effect added inside it, instances included. */
    private final Map<FXInstanceObject, List<IFXObject>> addedTops = new IdentityHashMap<>();
    private final Set<IFXObject> anyAddedTop = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<FXInstanceObject> instances = new ArrayList<>();
    private long rolesAt = -1;
    private final Map<FXInstanceObject, InstanceRecord> records = new IdentityHashMap<>();
    private long recordsAt;
    private int generation = FXHelper.generation();

    public FXInstanceEditing(FXEditor editor) {
        this.editor = editor;
    }

    /** Something in the effect changed: roles and overrides are worked out again when next asked. */
    public void changed() {
        rolesAt = -1;
        roles.clear();
        owners.clear();
        addedTops.clear();
        anyAddedTop.clear();
        instances.clear();
        records.clear();
    }

    public Role role(IFXObject object) {
        refreshRoles();
        return roles.getOrDefault(object, Role.NONE);
    }

    /** The outermost instance {@code object} was placed by or hangs inside; the instance itself for an instance. */
    @Nullable
    public FXInstanceObject owner(IFXObject object) {
        refreshRoles();
        if (object instanceof FXInstanceObject instance && roles.get(instance) instanceof Role role
                && (role == Role.INSTANCE || role == Role.MISSING)) {
            return instance;
        }
        return owners.get(object);
    }

    /** The id {@code object} has in its source, for an object an instance placed. */
    @Nullable
    public UUID sourceOf(IFXObject object) {
        var owner = role(object) == Role.PLACED ? owners.get(object) : null;
        return owner == null ? null : InstanceIds.xor(object.id(), owner.id());
    }

    /** An object this effect added straight under an instance or under something it placed: "+" in the hierarchy. */
    public boolean isAddedTop(IFXObject object) {
        refreshRoles();
        return anyAddedTop.contains(object);
    }

    public List<IFXObject> addedTops(FXInstanceObject instance) {
        refreshRoles();
        return List.copyOf(addedTops.getOrDefault(instance, List.of()));
    }

    /** The outermost instances, those this effect saves as records. */
    public List<FXInstanceObject> instances() {
        refreshRoles();
        return List.copyOf(instances);
    }

    private void refreshRoles() {
        var now = System.currentTimeMillis();
        if (rolesAt >= 0 && now - rolesAt < ROLES_MS) return;
        rolesAt = now;
        roles.clear();
        owners.clear();
        addedTops.clear();
        anyAddedTop.clear();
        instances.clear();
        var runtime = editor.runtime;
        if (runtime == null) return;
        for (var object : runtime.objects.values()) {
            if (object instanceof FXInstanceObject instance && instance.getPlacement() != null && !placedByAncestor(instance)) {
                instances.add(instance);
            }
        }
        var outermost = Collections.newSetFromMap(new IdentityHashMap<IFXObject, Boolean>());
        outermost.addAll(instances);
        for (var instance : instances) {
            var placement = instance.getPlacement();
            roles.put(instance, placement.isPlaced() ? Role.INSTANCE : Role.MISSING);
            var tops = new ArrayList<IFXObject>();
            walk(instance, instance, placement.isPlaced() ? placement.template().ids() : Set.of(), outermost, tops);
            addedTops.put(instance, tops);
            anyAddedTop.addAll(tops);
        }
    }

    private void walk(IFXObject parent, FXInstanceObject instance, Set<UUID> template, Set<IFXObject> outermost,
                      List<IFXObject> tops) {
        for (var child : parent.transform().children()) {
            if (!(child.sceneObject() instanceof IFXObject object)) continue;
            if (outermost.contains(object)) {
                // an instance added here: an added object that saves its own record
                tops.add(object);
            } else if (template.contains(InstanceIds.xor(object.id(), instance.id()))) {
                roles.put(object, Role.PLACED);
                owners.put(object, instance);
                walk(object, instance, template, outermost, tops);
            } else {
                tops.add(object);
                markAdded(object, instance, outermost);
            }
        }
    }

    private void markAdded(IFXObject object, FXInstanceObject instance, Set<IFXObject> outermost) {
        roles.put(object, Role.ADDED);
        owners.put(object, instance);
        for (var child : object.transform().children()) {
            if (child.sceneObject() instanceof IFXObject next && !outermost.contains(next)) {
                markAdded(next, instance, outermost);
            }
        }
    }

    private static boolean placedByAncestor(FXInstanceObject instance) {
        for (var at = instance.transform().parent(); at != null; at = at.parent()) {
            if (at.sceneObject() instanceof FXInstanceObject outer && outer.getPlacement() != null
                    && outer.getPlacement().isPlaced()
                    && outer.getPlacement().template().contains(InstanceIds.xor(instance.id(), outer.id()))) {
                return true;
            }
        }
        return false;
    }

    /** What {@code instance} would be saved with now, as of a moment ago. */
    public InstanceRecord recordOf(FXInstanceObject instance) {
        var now = System.currentTimeMillis();
        if (now - recordsAt > RECORDS_MS) {
            records.clear();
            recordsAt = now;
        }
        return records.computeIfAbsent(instance, this::computeRecord);
    }

    private InstanceRecord computeRecord(FXInstanceObject instance) {
        var placement = instance.getPlacement();
        if (placement == null) return InstanceRecord.EMPTY;
        var objects = new ArrayList<CompoundTag>();
        instance.executeAll(object -> {
            if (object instanceof IFXObject fxObject) {
                var tag = fxObject.serializeWrapper();
                if (tag != null) objects.add(tag);
            }
        });
        var animated = editor.runtime == null ? Map.<UUID, Set<List<String>>>of() : editor.runtime.fxData.animatedTransforms();
        return InstanceWriter.recordOf(objects, placement, id -> animated.getOrDefault(id, Set.of()));
    }

    /** The changes {@code object}'s instance records for it; empty for an object no instance placed. */
    public List<NbtPatch.Change> changesOf(IFXObject object) {
        var owner = owner(object);
        var source = sourceOf(object);
        if (owner == null || source == null) return List.of();
        var out = new ArrayList<NbtPatch.Change>();
        for (var modification : recordOf(owner).modified()) {
            if (modification.target().equals(source)) out.add(modification.change());
        }
        return out;
    }

    /** Whether anything at, under or above {@code path} of {@code object} differs from its source. */
    public boolean isOverridden(IFXObject object, List<String> path) {
        for (var change : changesOf(object)) {
            if (NbtPatch.overlaps(change.path(), path)) return true;
        }
        return false;
    }

    /** Whether {@code object}'s own values differ from its source, or for an instance, whether it overrides anything. */
    public boolean isOverridden(IFXObject object) {
        if (object instanceof FXInstanceObject instance && owner(instance) == instance) {
            return !recordOf(instance).isPristine();
        }
        return !changesOf(object).isEmpty();
    }

    /** Overrides that did not apply when the effect was read, kept and written back. */
    public InstanceRecord unusedOf(FXInstanceObject instance) {
        var placement = instance.getPlacement();
        return placement == null ? InstanceRecord.EMPTY : placement.unused();
    }

    /**
     * The fx id a {@code .fx} file is loaded by: the file under the last {@code assets/<namespace>/fx/} of its
     * path, which the editor's assets folder and resource packs share; null for any other file, or a name no id
     * can have (see {@link #isUnderFxFolder}).
     */
    @Nullable
    public static ResourceLocation idOf(File file) {
        var parts = fxPath(file);
        return parts == null ? null : ResourceLocation.tryBuild(parts[0], parts[1]);
    }

    /** Whether {@code file} is an {@code .fx} under an {@code assets/<namespace>/fx/} folder, whatever its name. */
    public static boolean isUnderFxFolder(File file) {
        return fxPath(file) != null;
    }

    @Nullable
    private static String[] fxPath(File file) {
        if (!file.getName().endsWith(FX.SUFFIX)) return null;
        var path = file.toPath().toAbsolutePath().normalize();
        for (int i = path.getNameCount() - 4; i >= 0; i--) {
            if ("assets".equals(path.getName(i).toString()) && "fx".equals(path.getName(i + 2).toString())) {
                var rest = path.subpath(i + 3, path.getNameCount()).toString().replace(File.separatorChar, '/');
                return new String[]{path.getName(i + 1).toString(), rest.substring(0, rest.length() - FX.SUFFIX.length())};
            }
        }
        return null;
    }

    /**
     * Place a new instance of {@code fx} under {@code parent}, as one undoable step.
     *
     * @return the instance, or null when it cannot be placed (the author is told why)
     */
    @Nullable
    public FXInstanceObject create(ResourceLocation fx, Transform parent) {
        if (editor.runtime == null) return null;
        if (refusedAsCycle(fx)) return null;
        var fresh = new FXInstanceObject(fx);
        fresh.setName(defaultName(fx));
        var placed = place(Objects.requireNonNull(fresh.serializeWrapper()), List.of());
        if (placed == null) {
            FXFileActions.notify(editor, "photon.fx_instance", Component.translatable("photon.fx_instance.unreadable", fx.toString()));
            return null;
        }
        var instance = placed.instance();
        editor.historyView.pushHistory(Component.translatable("photon.fx_instance.add"), EditAction.of(
                () -> {
                    instance.transform().parent(parent, false);
                    editor.hierarchyView.addSceneObject(instance);
                    changed();
                    editor.reloadEffect();
                },
                () -> {
                    editor.hierarchyView.removeSceneObject(instance);
                    changed();
                    editor.reloadEffect();
                }));
        return instance;
    }

    private static String defaultName(ResourceLocation fx) {
        var path = fx.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * @param instance     the instance with what it placed under it, linked to each other and to nothing else
     * @param addedParents for each object of this effect the record anchors, the id the bake hung it under
     */
    private record Placed(FXInstanceObject instance, Map<UUID, UUID> addedParents) {
    }

    /**
     * Bake an instance in its saved form into live objects.
     *
     * @param others the objects of this effect its record anchors; they are not made again, only told where to hang
     * @return null when its source cannot be placed
     */
    @Nullable
    private Placed place(CompoundTag instanceTag, List<CompoundTag> others) {
        var all = new ArrayList<CompoundTag>();
        all.add(instanceTag);
        all.addAll(others);
        var result = FXInstances.bake(knownAs(), InstanceTags.fxData(all, null));
        var id = InstanceTags.id(instanceTag);
        var placement = result.placements().get(id);
        if (placement == null || !placement.isPlaced()) return null;
        var otherIds = new HashSet<UUID>();
        for (var other : others) otherIds.add(InstanceTags.id(other));
        var addedParents = new HashMap<UUID, UUID>();
        var byId = new HashMap<UUID, IFXObject>();
        var made = new ArrayList<IFXObject>();
        for (var tag : InstanceTags.objects(result.fxData())) {
            var objectId = InstanceTags.id(tag);
            if (otherIds.contains(objectId)) {
                var parent = InstanceTags.parent(tag);
                if (parent != null) addedParents.put(objectId, parent);
                continue;
            }
            var object = IFXObject.deserializeWrapper(tag);
            if (object == null) continue;
            made.add(object);
            byId.put(object.id(), object);
        }
        FXData.attach(made, result, addedParents);
        for (var object : made) {
            var parentId = object.transform()._getInternalParentID();
            var parent = parentId == null || object.id().equals(id) ? null : byId.get(parentId);
            if (parent != null) object.transform().parent(parent.transform(), false);
        }
        made.forEach(object -> object.transform().rebuildChildOrder());
        if (!(byId.get(id) instanceof FXInstanceObject instance)) return null;
        return new Placed(instance, addedParents);
    }

    /** The ids this project is known by: what it was converted from and exported to. */
    public Set<ResourceLocation> knownAs() {
        return editor.getCurrentProject() instanceof FXProject project ? project.getKnownAs() : Set.of();
    }

    /** The id through which placing {@code fx} here would place this project inside itself, or null. */
    @Nullable
    public ResourceLocation cycleThrough(ResourceLocation fx) {
        var self = knownAs();
        if (self.isEmpty()) return null;
        if (self.contains(fx)) return fx;
        for (var placed : FXInstances.templates().closure(fx)) {
            if (self.contains(placed)) return placed;
        }
        return null;
    }

    private boolean refusedAsCycle(ResourceLocation fx) {
        var cycle = cycleThrough(fx);
        if (cycle == null) return false;
        FXFileActions.notify(editor, "photon.fx_instance", Component.translatable("photon.fx_instance.cycle", fx.toString(), cycle.toString()));
        return true;
    }

    /** Tell the author about pasted instances that place nothing because they would place this effect inside itself. */
    public void reportCycles(FXData pasted) {
        for (var object : pasted.objects()) {
            if (object instanceof FXInstanceObject instance && instance.getFx() != null && instance.getPlacement() != null
                    && instance.getPlacement().status() == InstanceBaker.Status.CYCLIC) {
                var cycle = cycleThrough(instance.getFx());
                FXFileActions.notify(editor, "photon.fx_instance", Component.translatable("photon.fx_instance.cycle",
                        instance.getFx().toString(), (cycle == null ? instance.getFx() : cycle).toString()));
                return;
            }
        }
    }

    /** The source's data of an object an instance placed, in this effect's ids. */
    @Nullable
    private CompoundTag pristineData(IFXObject object) {
        var owner = owner(object);
        var source = sourceOf(object);
        if (owner == null || source == null || owner.getPlacement() == null || !owner.getPlacement().isPlaced()) return null;
        var template = owner.getPlacement().template();
        var sourceTag = template.object(source);
        if (sourceTag == null) return null;
        return InstanceTags.data(new InstanceIds.Remapper(template.idMap(owner.id())).apply(sourceTag));
    }

    /** Put {@code path} of {@code object} back to its source's value, and whatever overlapping override covers it. */
    public void revert(IFXObject object, List<String> path) {
        var pristine = pristineData(object);
        if (pristine == null) return;
        var before = currentData(object);
        var after = before.copy();
        var reverted = new LinkedHashSet<List<String>>();
        for (var change : changesOf(object)) {
            if (!NbtPatch.overlaps(change.path(), path)) continue;
            // the longer of the two is inside the shorter: revert the shorter, which covers both
            reverted.add(change.path().size() <= path.size() ? change.path() : path);
        }
        for (var at : reverted) {
            var value = NbtPatch.get(pristine, at);
            var change = value == null ? NbtPatch.Change.remove(at) : NbtPatch.Change.set(at, value);
            after = NbtPatch.apply(after, List.of(change)).tag();
        }
        load(object, before, after, "photon.fx_instance.revert");
    }

    /** Put every value of {@code object} back to its source's. */
    public void revertObject(IFXObject object) {
        var pristine = pristineData(object);
        if (pristine != null) load(object, currentData(object), pristine, "photon.fx_instance.revert_object");
    }

    private static CompoundTag currentData(IFXObject object) {
        var tag = object.serializeWrapper();
        return tag == null ? new CompoundTag() : InstanceTags.data(tag).copy();
    }

    private void load(IFXObject object, CompoundTag before, CompoundTag after, String history) {
        if (withLiveStructure(object, after).equals(before)) return;
        editor.historyView.pushHistory(Component.translatable(history), EditAction.of(
                () -> read(object, after), () -> read(object, before)));
    }

    /** In place, so the inspector's rows (which poll their values) and the timeline's bindings keep the object. */
    private void read(IFXObject object, CompoundTag data) {
        object.deserializeNBT(Platform.getFrozenRegistry(), withLiveStructure(object, data));
        changed();
    }

    /**
     * {@code data} with where {@code object} hangs now and what hangs under it: a snapshot of values must not put
     * back a structure the transforms no longer have.
     */
    private static CompoundTag withLiveStructure(IFXObject object, CompoundTag data) {
        var live = currentData(object).getCompound(InstanceTags.TRANSFORM);
        var out = data.copy();
        var transform = out.getCompound(InstanceTags.TRANSFORM);
        for (var key : List.of(InstanceTags.ID, InstanceTags.PARENT, InstanceTags.CHILDREN)) {
            if (live.contains(key)) {
                transform.put(key, live.get(key).copy());
            } else {
                transform.remove(key);
            }
        }
        out.put(InstanceTags.TRANSFORM, transform);
        return out;
    }

    /** Switch an object on or off, the saved switch an instance may override. */
    public void setEnabled(IFXObject object, boolean enabled) {
        if (object.isEnabled() == enabled || !(object instanceof FXObject fxObject)) return;
        editor.historyView.pushHistory(Component.translatable(enabled ? "photon.fx_instance.enable" : "photon.fx_instance.disable"),
                EditAction.of(() -> {
                    fxObject.setEnabled(enabled);
                    changed();
                }, () -> {
                    fxObject.setEnabled(!enabled);
                    changed();
                }));
    }

    /** Put back an object of the source that the instance removed, with what is under it. */
    public void restoreRemoved(FXInstanceObject instance, UUID source) {
        var restored = recreate(instance, source);
        if (restored == null) return;
        editor.historyView.pushHistory(Component.translatable("photon.fx_instance.restore"), EditAction.of(
                () -> {
                    attach(instance, source, restored);
                    changed();
                    editor.reloadEffect();
                },
                () -> {
                    editor.hierarchyView.removeSceneObject(restored);
                    changed();
                    editor.reloadEffect();
                }));
    }

    /** Hang a recreated object where the source has it: under its placed parent, among its siblings in the source's order. */
    private void attach(FXInstanceObject instance, UUID source, IFXObject restored) {
        var template = instance.getPlacement().template();
        var parentSource = template.parent(source);
        var runtime = editor.runtime;
        var parent = InstanceIds.ROOT.equals(parentSource) || runtime == null ? null
                : runtime.objects.get(InstanceIds.xor(instance.id(), parentSource));
        var parentTransform = (parent == null ? instance : parent).transform();
        restored.transform().parent(parentTransform, false);
        editor.hierarchyView.addSceneObject(restored);
        var order = template.children(parentSource);
        int index = 0;
        for (var sibling : parentTransform.children()) {
            if (sibling == restored.transform()) break;
            var siblingSource = InstanceIds.xor(sibling.sceneObject().id(), instance.id());
            if (order.contains(siblingSource) && order.indexOf(siblingSource) < order.indexOf(source)) index++;
        }
        restored.transform().setSiblingIndex(index);
    }

    /** The source's subtree at {@code source}, placed again with this instance's ids and linked, not yet in the scene. */
    @Nullable
    private IFXObject recreate(FXInstanceObject instance, UUID source) {
        var placement = instance.getPlacement();
        if (placement == null || !placement.isPlaced() || !placement.template().contains(source)) return null;
        var template = placement.template();
        var remapper = new InstanceIds.Remapper(template.idMap(instance.id()));
        var subtree = new ArrayList<UUID>();
        collect(template, source, subtree);
        var made = new HashMap<UUID, IFXObject>();
        for (var id : subtree) {
            var tag = remapper.apply(template.object(id));
            var parent = template.parent(id);
            InstanceTags.setParent(tag, InstanceIds.ROOT.equals(parent) ? instance.id() : InstanceIds.xor(instance.id(), parent));
            var object = IFXObject.deserializeWrapper(tag);
            if (object != null) made.put(id, object);
        }
        for (var id : subtree) {
            var object = made.get(id);
            var parent = made.get(template.parent(id));
            if (object != null && parent != null && !id.equals(source)) {
                object.transform().parent(parent.transform(), false);
            }
        }
        return made.get(source);
    }

    private static void collect(InstanceTemplate template, UUID id, List<UUID> out) {
        out.add(id);
        for (var child : template.children(id)) collect(template, child, out);
    }

    /** Put the whole instance back to its source: values, removed objects back, added objects out. */
    public void revertAll(FXInstanceObject instance) {
        var placement = instance.getPlacement();
        if (placement == null || !placement.isPlaced()) return;
        refreshRoles();
        var reverts = new ArrayList<Runnable>();
        var undos = new ArrayList<Runnable>();
        instance.executeAll(object -> {
            if (object instanceof IFXObject fxObject && role(fxObject) == Role.PLACED && owners.get(fxObject) == instance) {
                var pristine = pristineData(fxObject);
                if (pristine == null) return;
                var before = currentData(fxObject);
                if (withLiveStructure(fxObject, pristine).equals(before)) return;
                reverts.add(() -> read(fxObject, pristine));
                undos.add(() -> read(fxObject, before));
            }
        });
        for (var removed : recordOf(instance).removed()) {
            var restored = recreate(instance, removed);
            if (restored == null) continue;
            reverts.add(() -> attach(instance, removed, restored));
            undos.add(() -> editor.hierarchyView.removeSceneObject(restored));
        }
        for (var object : addedTops(instance)) {
            var parent = object.transform().parent();
            var index = object.transform().getSiblingIndex();
            reverts.add(() -> editor.hierarchyView.removeSceneObject(object));
            undos.add(() -> {
                object.transform().parent(parent, false);
                editor.hierarchyView.addSceneObject(object);
                object.transform().setSiblingIndex(index);
            });
        }
        if (reverts.isEmpty()) return;
        editor.historyView.pushHistory(Component.translatable("photon.fx_instance.revert_all"), EditAction.of(
                () -> {
                    reverts.forEach(Runnable::run);
                    changed();
                    editor.reloadEffect();
                },
                () -> {
                    for (int i = undos.size() - 1; i >= 0; i--) undos.get(i).run();
                    changed();
                    editor.reloadEffect();
                }));
        editor.inspectorView.clear();
    }

    /** Forget the overrides that did not apply when the effect was read. */
    public void removeUnused(FXInstanceObject instance) {
        var placement = instance.getPlacement();
        if (placement == null || placement.unused().isPristine()) return;
        var cleared = placement.withUnused(InstanceRecord.EMPTY);
        editor.historyView.pushHistory(Component.translatable("photon.fx_instance.remove_unused"), EditAction.of(
                () -> {
                    instance.setPlacement(cleared);
                    changed();
                },
                () -> {
                    instance.setPlacement(placement);
                    changed();
                }));
    }

    /** Called every editor tick: once sources were forgotten (re-exported, a pack rewritten), place the instances again. */
    public void tick() {
        var now = FXHelper.generation();
        if (now == generation) return;
        generation = now;
        for (var instance : instances()) {
            refresh(instance);
        }
    }

    /**
     * Place {@code instance} again from its source as it is now, keeping its overrides: the record it would be
     * saved with is laid over the new template. A source baked again without having changed (a cache was
     * cleared) changes nothing, so it is no step in the history either.
     */
    public void refresh(FXInstanceObject instance) {
        var placement = instance.getPlacement();
        if (placement == null || placement.fx() == null) return;
        if (!(FXInstances.templates().lookup(placement.fx()) instanceof InstanceBaker.Found found)
                || found.template() == placement.template()) {
            return;
        }
        if (placement.isPlaced() && found.template().sameContent(placement.template())) {
            instance.setPlacement(placement.withTemplate(found.template()));
            changed();
            return;
        }
        var placed = placeAgain(instance, placement.fx());
        if (placed != null) swap(instance, placed, null, "photon.fx_instance.refresh");
    }

    /** Place {@code fx} in {@code instance}'s stead, keeping the overrides that still find their objects. */
    public void replace(FXInstanceObject instance, ResourceLocation fx) {
        var placement = instance.getPlacement();
        if (placement == null || fx.equals(placement.fx()) || refusedAsCycle(fx)) return;
        var placed = placeAgain(instance, fx);
        if (placed == null) {
            FXFileActions.notify(editor, "photon.fx_instance", Component.translatable("photon.fx_instance.unreadable", fx.toString()));
            return;
        }
        swap(instance, placed, fx, "photon.fx_instance.replace");
    }

    /** {@code instance} as it would be saved now, placing {@code fx}, baked with the objects its record anchors. */
    @Nullable
    private Placed placeAgain(FXInstanceObject instance, ResourceLocation fx) {
        var record = computeRecord(instance);
        var tag = Objects.requireNonNull(instance.serializeWrapper()).copy();
        var data = InstanceTags.data(tag);
        data.remove(InstanceTags.TIMELINE);
        data.remove(InstanceTags.OVERRIDES);
        data.putString(InstanceTags.FX, fx.toString());
        if (!record.isPristine()) data.put(InstanceTags.OVERRIDES, record.write());
        // only where they hang matters to the bake: an instance among them would place its source again for nothing
        var anchored = new ArrayList<CompoundTag>();
        var runtime = editor.runtime;
        for (var added : record.added()) {
            var object = runtime == null ? null : runtime.objects.get(added.object());
            if (object == null) continue;
            var transform = new CompoundTag();
            transform.putString(InstanceTags.ID, object.id().toString());
            var parent = object.transform().parent();
            if (parent != null) transform.putString(InstanceTags.PARENT, parent.sceneObject().id().toString());
            var stub = new CompoundTag();
            stub.put(InstanceTags.DATA, new CompoundTag());
            stub.getCompound(InstanceTags.DATA).put(InstanceTags.TRANSFORM, transform);
            anchored.add(stub);
        }
        return place(tag, anchored);
    }

    /** Where each object hangs and in which order, for an instance and everything under it. */
    private record Layout(Map<IFXObject, IFXObject> parents, Map<IFXObject, List<IFXObject>> children) {
    }

    private static Layout layoutOf(FXInstanceObject instance) {
        var parents = new LinkedHashMap<IFXObject, IFXObject>();
        var children = new LinkedHashMap<IFXObject, List<IFXObject>>();
        instance.executeAll(scene -> {
            if (!(scene instanceof IFXObject object)) return;
            var kids = new ArrayList<IFXObject>();
            for (var child : object.transform().children()) {
                if (child.sceneObject() instanceof IFXObject kid) kids.add(kid);
            }
            children.put(object, kids);
            var parent = object.transform().parent();
            if (object != instance && parent != null && parent.sceneObject() instanceof IFXObject parentObject) {
                parents.put(object, parentObject);
            }
        });
        return new Layout(parents, children);
    }

    /** Make {@code instance}'s subtree {@code layout}: move what stays, remove what it does not have, add what it has. */
    private void apply(FXInstanceObject instance, Layout layout) {
        var current = layoutOf(instance);
        // out of the way first, so nothing is ever hung under its own subtree
        layout.parents().forEach((object, parent) -> {
            var at = object.transform().parent();
            if (at != null && at.sceneObject() != parent) object.transform().parent(null, false);
        });
        current.parents().forEach((object, parent) -> {
            if (!layout.parents().containsKey(object) && (parent == instance || layout.parents().containsKey(parent))) {
                editor.hierarchyView.removeSceneObject(object);
            }
        });
        var arriving = new ArrayList<IFXObject>();
        layout.parents().forEach((object, parent) -> {
            if (object.transform().parent() != null) return;
            if (object.getScene() != editor.runtime) arriving.add(object);
            object.transform().parent(parent.transform(), false);
        });
        for (var object : arriving) {
            if (!arriving.contains(layout.parents().get(object))) editor.hierarchyView.addSceneObject(object);
        }
        // an arriving object brings the child list it was baked with, in an order its children did not arrive in
        layout.children().keySet().forEach(parent -> parent.transform()._setInternalChildID(
                parent.transform().children().stream().map(Transform::id).toList()));
        layout.children().forEach((parent, kids) -> {
            int index = 0;
            for (var kid : kids) {
                if (kid.transform().parent() == parent.transform()) kid.transform().setSiblingIndex(index++);
            }
        });
    }

    /**
     * Make {@code instance} place what {@code placed} does. An object placed both times stays the same object and
     * takes the new values; one only placed before goes; one only placed now arrives. Placed objects hang where
     * the new template has them, and what this effect added where the bake anchored it.
     */
    private void swap(FXInstanceObject instance, Placed placed, @Nullable ResourceLocation newFx, String history) {
        if (editor.runtime == null) return;
        changed();
        refreshRoles();
        var fresh = placed.instance();
        var newPlacement = fresh.getPlacement();
        var template = newPlacement.template();
        var before = withAnchoredOutside(layoutOf(instance), instance, placed.addedParents().keySet());
        var placedBefore = new HashMap<UUID, IFXObject>();
        before.parents().keySet().forEach(object -> {
            if (role(object) == Role.PLACED && owners.get(object) == instance) placedBefore.put(object.id(), object);
        });
        var target = new HashMap<UUID, IFXObject>();
        var values = new IdentityHashMap<IFXObject, CompoundTag[]>();
        var standIns = new ArrayList<IFXObject>();
        fresh.executeAll(scene -> {
            if (scene == fresh || !(scene instanceof IFXObject copy)) return;
            var kept = placedBefore.get(copy.id());
            if (kept != null) {
                values.put(kept, new CompoundTag[]{currentData(kept), currentData(copy)});
                standIns.add(copy);
            }
            target.put(copy.id(), kept != null ? kept : copy);
        });

        // placed objects hang where the bake hung them, which skips what could not be read
        var parents = new LinkedHashMap<IFXObject, IFXObject>();
        fresh.executeAll(scene -> {
            if (scene == fresh || !(scene instanceof IFXObject copy)) return;
            var at = copy.transform().parent();
            var parent = at == null || at.sceneObject() == fresh ? null : target.get(at.sceneObject().id());
            parents.put(target.get(copy.id()), parent == null ? instance : parent);
        });
        // a stand-in for a kept object must not come into the scene with an arriving parent
        standIns.forEach(copy -> copy.transform().parent(null, false));
        before.parents().forEach((object, parent) -> {
            if (placedBefore.containsKey(object.id())) return;
            var anchor = placed.addedParents().get(object.id());
            if (anchor != null) {
                var at = anchor.equals(instance.id()) ? null : target.get(anchor);
                parents.put(object, at == null ? instance : at);
            } else {
                parents.put(object, placedBefore.containsKey(parent.id()) && !target.containsKey(parent.id()) ? instance : parent);
            }
        });
        var children = new LinkedHashMap<IFXObject, List<IFXObject>>();
        parents.forEach((object, parent) -> children.computeIfAbsent(parent, key -> new ArrayList<>()).add(object));
        children.forEach((parent, kids) -> kids.sort((a, b) -> Integer.compare(
                rank(instance, template, before, parent, a), rank(instance, template, before, parent, b))));
        var after = new Layout(parents, children);

        var oldPlacement = instance.getPlacement();
        var oldFx = instance.getFx();
        var oldTracks = new ArrayList<>(instance.getTimeline().tracks());
        var newTracks = new ArrayList<>(fresh.getTimeline().tracks());
        editor.historyView.pushHistory(Component.translatable(history), EditAction.of(
                () -> {
                    apply(instance, after);
                    values.forEach((object, data) -> read(object, data[1]));
                    setTimeline(instance, newTracks);
                    instance.setPlacement(newPlacement);
                    if (newFx != null) instance.setFx(newFx);
                    changed();
                    editor.reloadEffect();
                },
                () -> {
                    apply(instance, before);
                    values.forEach((object, data) -> read(object, data[0]));
                    setTimeline(instance, oldTracks);
                    instance.setPlacement(oldPlacement);
                    instance.setFx(oldFx);
                    changed();
                    editor.reloadEffect();
                }));
    }

    /**
     * {@code layout} plus the anchored objects hanging outside the instance, where they hang now: what a source that
     * could not be read left at the effect's root, and that the bake hangs back inside.
     */
    private Layout withAnchoredOutside(Layout layout, FXInstanceObject instance, Set<UUID> anchored) {
        var runtime = editor.runtime;
        if (runtime == null) return layout;
        var parents = new LinkedHashMap<>(layout.parents());
        var children = new LinkedHashMap<>(layout.children());
        for (var id : anchored) {
            var object = runtime.objects.get(id);
            if (object == null || object == instance || parents.containsKey(object)) continue;
            var parent = object.transform().parent();
            if (parent == null || !(parent.sceneObject() instanceof IFXObject parentObject)) continue;
            parents.put(object, parentObject);
            children.computeIfAbsent(parentObject, key -> {
                var kids = new ArrayList<IFXObject>();
                for (var child : parent.children()) {
                    if (child.sceneObject() instanceof IFXObject kid) kids.add(kid);
                }
                return kids;
            });
        }
        return new Layout(parents, children);
    }

    /** Placed objects first, in the template's order, then the others as they were among each other (new arrivals last). */
    private static int rank(FXInstanceObject instance, InstanceTemplate template, Layout before, IFXObject parent, IFXObject child) {
        var source = InstanceIds.xor(child.id(), instance.id());
        if (template.contains(source)) {
            return template.children(template.parent(source)).indexOf(source);
        }
        var was = before.children().getOrDefault(parent, List.of()).indexOf(child);
        return was < 0 ? Integer.MAX_VALUE : 1_000_000 + was;
    }

    private static void setTimeline(FXInstanceObject instance, List<Track> tracks) {
        instance.getTimeline().tracks().clear();
        instance.getTimeline().tracks().addAll(tracks);
    }
}
