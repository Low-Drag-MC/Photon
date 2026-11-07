# Changelog
## v2.2.11
* Added timeline sections (like Unreal montage sections): split a timeline into named sections that play once, repeat or loop until told to move on, so one effect can hold a start, a loop of any length and an end; edit and preview them in the timeline editor, and switch them from Java (FXRuntime.sections()) or with /photon fx section
* Added release sections: stopping an effect without force (such as destroy(false) or /photon fx remove ... false) plays its release section first, then lets it wind down
* Added a Drain exit mode to control clips: when the clip ends, its object stops emitting and the particles fade out instead of vanishing
* Added Loop and PingPong lifetime modes to particles, which keep cycling through their lifetime and only die once their emitter is stopped
* Fixed effects stopped without force misbehaving while they faded out: their timeline kept running and brought objects back, particles with a Start Lifetime of 0 never died, and lights went out early
* Fixed timeline animations stuttering when an effect plays at a rate other than 1
* Fixed /photon fx remove entity skipping the remaining targets after one that had no effects
