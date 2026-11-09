package com.mzcod.tacticalmovement.movement;

// Stamina tuning (all per server tick; 20 ticks = 1 second). Live-tunable with /tacticaledbg stamina.
public final class StaminaConfig {
	public static volatile float max = 100.0F;
	public static volatile float sprintDrain = 1.0F;   // per tick while sprinting (100 stamina lasts ~5s)
	public static volatile float regen = 1.5F;         // per tick while resting (empty -> full in ~3.3s)
	public static volatile int regenDelay = 30;        // ticks after sprinting/diving before regen starts
	public static volatile float diveCost = 20.0F;     // one-off cost of a dolphin dive
	public static volatile float recoverThreshold = 25.0F; // after running dry you cannot sprint/dive until back above this
	public static volatile int exhaustedDelay = 40;    // regen delay after running dry

	private StaminaConfig() {
	}
}
