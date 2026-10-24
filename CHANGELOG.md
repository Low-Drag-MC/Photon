# Changelog
## v2.2.9
* Added FX instances (like Unity prefabs): place an .fx inside another effect, at any depth, and override, disable or delete what it places; only the overrides are saved, so it keeps following its source
* Added a new particle collision system with colliders, triggers, entities and fluids; collision is swept, so fast particles no longer pass through walls
* Added preloading effects ahead of their first play (FXHelper.preload), warming their shaders, textures and sub-emitter effects in the background
* Added converting an .fx into an editable project, from the File menu or the FXPack manager, and removing effects from an FXPack
* Fixed FXPack updates not taking effect until a resource reload
* Fixed stacked screen distortion falling back under after-pack shader packs
* Bump up ldlib2 to 2.2.43
