package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSearch;
import com.lowdragmc.lowdraglib2.configurator.annotation.DefaultValue;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SearchComponentConfigurator;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.search.IResultHandler;
import com.lowdragmc.photon.client.fx.FXHelper;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class CollisionSettings implements IConfigurable, IPersistedSerializable {
    public enum Backend { NATIVE, CACHED_SWEEP }
    public enum Response { INHERIT, REMOVE, STOP, BOUNCE, SLIDE, PIERCE }
    public enum EntityFilter { ALL, LIVING, PLAYERS, NON_LIVING }

    @Configurable(name = "photon.collision.backend", tips = "photon.collision.backend.tips") @Persisted
    @DefaultValue(stringValue = "NATIVE")
    public Backend backend = Backend.NATIVE;
    @Configurable(name = "photon.collision.blocks", tips = "photon.collision.blocks.tips") @Persisted
    @DefaultValue(booleanValue = true)
    public boolean blocks = true;
    @Configurable(name = "photon.collision.entities", tips = "photon.collision.entities.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean entities = false;
    @Configurable(name = "photon.collision.fluids", tips = "photon.collision.fluids.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean fluids = false;
    @Configurable(name = "photon.collision.includedBlockTypes", tips = "photon.collision.includedBlockTypes.tips") @Persisted
    @DefaultValue(stringValue = "")
    public String includedBlockTypes = "";
    @Configurable(name = "photon.collision.excludedBlockTypes", tips = "photon.collision.excludedBlockTypes.tips") @Persisted
    @DefaultValue(stringValue = "")
    public String excludedBlockTypes = "";
    @Configurable(name = "photon.collision.ignoreOwner", tips = "photon.collision.ignoreOwner.tips") @Persisted
    @DefaultValue(booleanValue = true)
    public boolean ignoreOwner = true;
    @Configurable(name = "photon.collision.ownerEntityId", tips = "photon.collision.ownerEntityId.tips") @Persisted
    @DefaultValue(numberValue = -1)
    @ConfigNumber(range = {-1, 2147483647}, wheel = 1)
    public int ownerEntityId = -1;
    @Configurable(name = "photon.collision.sourceEntityExclusionRadius", tips = "photon.collision.sourceEntityExclusionRadius.tips") @Persisted
    @DefaultValue(numberValue = 0)
    @ConfigNumber(range = {0, 1000000}, wheel = 0.25)
    public double sourceEntityExclusionRadius = 0;
    @Configurable(name = "photon.collision.ignoreSpectators", tips = "photon.collision.ignoreSpectators.tips") @Persisted
    @DefaultValue(booleanValue = true)
    public boolean ignoreSpectators = true;
    @Configurable(name = "photon.collision.pickableOnly", tips = "photon.collision.pickableOnly.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean pickableOnly = false;
    @Configurable(name = "photon.collision.entityFilter", tips = "photon.collision.entityFilter.tips") @Persisted
    @DefaultValue(stringValue = "LIVING")
    public EntityFilter entityFilter = EntityFilter.LIVING;
    @Configurable(name = "photon.collision.includedEntityTypes", tips = "photon.collision.includedEntityTypes.tips") @Persisted
    @DefaultValue(stringValue = "")
    public String includedEntityTypes = "";
    @Configurable(name = "photon.collision.excludedEntityTypes", tips = "photon.collision.excludedEntityTypes.tips") @Persisted
    @DefaultValue(stringValue = "")
    public String excludedEntityTypes = "";
    @Configurable(name = "photon.collision.sweepEntityMotion", tips = "photon.collision.sweepEntityMotion.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean sweepEntityMotion = false;
    @Configurable(name = "photon.collision.response", tips = "photon.collision.response.tips") @Persisted
    @DefaultValue(stringValue = "INHERIT")
    public Response response = Response.INHERIT;
    @Configurable(name = "photon.collision.restitution", tips = "photon.collision.restitution.tips") @Persisted
    @DefaultValue(numberValue = 0.32)
    @ConfigNumber(range = {0, 1}, wheel = 0.05)
    public double restitution = 0.32;
    @Configurable(name = "photon.collision.maxBounces", tips = "photon.collision.maxBounces.tips") @Persisted
    @DefaultValue(numberValue = 0)
    @ConfigNumber(range = {0, 2147483647}, wheel = 1)
    public int maxBounces = 0;
    @Configurable(name = "photon.collision.maxHitsPerStep", tips = "photon.collision.maxHitsPerStep.tips") @Persisted
    @DefaultValue(numberValue = 32)
    @ConfigNumber(range = {0, 2147483647}, wheel = 1)
    public int maxHitsPerStep = 32;
    @Configurable(name = "photon.collision.contactCooldownTicks", tips = "photon.collision.contactCooldownTicks.tips") @Persisted
    @DefaultValue(numberValue = 0)
    @ConfigNumber(range = {0, 2147483647}, wheel = 1)
    public int contactCooldownTicks = 0;
    @Configurable(name = "photon.collision.deduplicateParticleHits", tips = "photon.collision.deduplicateParticleHits.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean deduplicateParticleHits = false;
    @Configurable(name = "photon.collision.deduplicateEmitterHits", tips = "photon.collision.deduplicateEmitterHits.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean deduplicateEmitterHits = false;
    @Configurable(name = "photon.collision.deduplicationGroup", tips = "photon.collision.deduplicationGroup.tips") @Persisted
    @DefaultValue(stringValue = "")
    public String deduplicationGroup = "";
    @Configurable(name = "photon.collision.rayProbe", tips = "photon.collision.rayProbe.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean rayProbe = false;
    @Configurable(name = "photon.collision.detectStationaryOverlaps", tips = "photon.collision.detectStationaryOverlaps.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean detectStationaryOverlaps = false;
    @Configurable(name = "photon.collision.alignEffectsToNormal", tips = "photon.collision.alignEffectsToNormal.tips") @Persisted
    @DefaultValue(booleanValue = false)
    public boolean alignEffectsToNormal = false;
    @Configurable(name = "photon.collision.blockEffect", tips = "photon.collision.blockEffect.tips") @Persisted
    @DefaultValue(stringValue = "")
    @ConfigSearch(searchConfiguratorMethod = "searchEffects")
    public String blockEffect = "";
    @Configurable(name = "photon.collision.entityEffect", tips = "photon.collision.entityEffect.tips") @Persisted
    @DefaultValue(stringValue = "")
    @ConfigSearch(searchConfiguratorMethod = "searchEffects")
    public String entityEffect = "";
    @Configurable(name = "photon.collision.fluidEffect", tips = "photon.collision.fluidEffect.tips") @Persisted
    @DefaultValue(stringValue = "")
    @ConfigSearch(searchConfiguratorMethod = "searchEffects")
    public String fluidEffect = "";
    @Configurable(name = "photon.collision.cachedTileLimit", tips = "photon.collision.cachedTileLimit.tips") @Persisted
    @DefaultValue(numberValue = 16384)
    @ConfigNumber(range = {1, 2147483647}, wheel = 256)
    public int cachedTileLimit = 16384;

    static String sectionFor(String field) {
        return switch (field) {
            case "backend" -> "backend";
            case "blocks", "entities", "fluids", "rayProbe", "detectStationaryOverlaps" -> "detection";
            case "includedBlockTypes", "excludedBlockTypes" -> "block_filters";
            case "ignoreOwner", "ownerEntityId", "sourceEntityExclusionRadius", "ignoreSpectators", "pickableOnly", "entityFilter", "includedEntityTypes", "excludedEntityTypes", "sweepEntityMotion" -> "entity_filters";
            case "response", "restitution", "maxBounces", "maxHitsPerStep" -> "response";
            case "contactCooldownTicks", "deduplicateParticleHits", "deduplicateEmitterHits", "deduplicationGroup" -> "events";
            case "alignEffectsToNormal", "blockEffect", "entityEffect", "fluidEffect" -> "effects";
            case "cachedTileLimit" -> "cache";
            default -> throw new IllegalArgumentException("Missing editor section for " + field);
        };
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IConfigurable.super.buildConfigurator(father);
        var groups = new LinkedHashMap<String, ConfiguratorGroup>();
        for (var row : List.copyOf(father.getConfigurators())) {
            var field = row.getSourceField();
            if (field == null || field.getDeclaringClass() != CollisionSettings.class) continue;
            row.setTips(field.getAnnotation(Configurable.class).tips());
            String section = sectionFor(field.getName());
            if (section.equals("backend")) continue;
            father.removeConfigurator(row);
            groups.computeIfAbsent(section, key -> {
                var group = new ConfiguratorGroup("photon.collision.group." + key);
                group.setTips("photon.collision.cached_only");
                father.addConfigurator(group);
                return group;
            }).addConfigurator(row);
        }
    }

    private SearchComponentConfigurator.ISearchConfigurator<String> searchEffects() {
        List<String> candidates = new ArrayList<>();
        candidates.add("");
        FXHelper.listAllFX().forEach(location -> candidates.add(location.toString()));
        return new SearchComponentConfigurator.ISearchConfigurator<>() {
            @Override public String defaultValue() { return ""; }
            @Override public String resultText(String value) { return value; }
            @Override public Component mapping(String value) { return value.isBlank() ? Component.translatable("photon.collision.no_effect") : Component.literal(value); }
            @Override public void search(String word, IResultHandler<String> handler) {
                String query = word.toLowerCase(Locale.ROOT);
                for (String candidate : candidates) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (candidate.toLowerCase(Locale.ROOT).contains(query)) handler.accept(candidate);
                }
            }
        };
    }
}

