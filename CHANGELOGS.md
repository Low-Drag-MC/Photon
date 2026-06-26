## ChangeLogs
# v2.1.5
* Added billboard facing, render mode None, and shape arc controls (Thanks for the PR #50, @Shane77)
* Added auto load models under the photon models
* Improved zh_cn lang (Thanks for the PR #48, @Moflop)
* Added billboard stretch (Thanks for the PR #48, @Moflop)
* Bump up ldlib2

# v2.1.4
* bump up ldlib2 and remove deprecated APIs
* added command to covert photon1 fx into photon2 fx format (thanks @Cdogsnappy)
    * `/photon_client convert`, which will grab all `.fx` files from the directory in `ldlib2/assets/photon/fx_old` and convert them to their photon 2 equivalent, placing the result in the neighboring /fx directory.

# v2.1.3.a
* Added animation tiles compat for previous versions
* Added ViewPort uniform

# v2.1.3
* Bump up ldlib to v2.1.7, better editor performance, and qol.
* Added collided friction
* Added random value for additional gpu data

# v2.1.2
* Bump up ldlib to v2.1.4, better shader support

# v2.1.1
* Bump up LDLib2 version to 2.1.3+
* Fix the timer exception of BeamEmitter and any bugs of AraTrail. (Thanks @dfdyz)
* Added `colorOverSegmentTime` and `thicknessOverSegmentTime` for AraTrail. (Thanks @dfdyz)

# v2.1.0
* Bump up LDLib2 version to 2.1.0+
* Fixed UV Animation tiles definition and behavior
* Fixed GPU Instance rendering error while having multiple particles greater than max particle number
* Added zh_cn localization (Thanks @Arcomit)
* Added lang for config (Thanks @hi4444)
* Added Raycast mode for beam particles (Thanks @OmbreMoon)

# v2.0.6
* bump up ldlib2 to fix ResourcePack loading

# v2.0.5
* add mesh sorter for all vertex format
* merge materials to reduce useless drawcall
* better initial config value

# v2.0.4
* Added AraTrail
* Added SpriteMaterial
* Added wireframe mode
* Added multiple materials rendering supports
* Added DataFixer for LTS
* Added Particle Emitter prewarm
* Added Particle Radial Velocity
* Added Animation Tool Panel

# v2.0.3.b
* Fixed Try to access MeshData during reloading

# v2.0.3.a
* Fixed Random Color crash
* Fixed Gradient Resource drag drop

# v2.0.3
* Fixed crash
* Fixed delay doesn't work
* Fixed invalid immediately buffer after using GPU Instance
* Fixed Function Shape with zero speed
* Bump up ldlib2

# v2.0.2
* Fixed uv animation settings crash
* Added more lang entries for configs

# v2.0.1
* Cache draw target depth texture
* Fixed Registries loading
* Added Game Time Control
* Fixed resource releasing
* Check LDLib2 version