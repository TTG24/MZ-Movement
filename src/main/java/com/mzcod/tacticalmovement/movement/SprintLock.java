package com.mzcod.tacticalmovement.movement;

// Client-only: after running out of stamina the sprint key must be released before sprinting works again,
// so a held key does not instantly resume sprinting the moment stamina recovers.
public final class SprintLock {
	public static volatile boolean locked;

	private SprintLock() {
	}
}
