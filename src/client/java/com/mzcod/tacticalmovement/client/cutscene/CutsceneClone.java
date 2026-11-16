package com.mzcod.tacticalmovement.client.cutscene;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;

import java.util.UUID;

// A client-only copy of the local player for the game-over cutscene: same skin (it borrows the player's tab-list entry,
// which holds the skin), cape and skin layers, under its own id so it does not clash with the real player.
public final class CutsceneClone extends RemotePlayer {
	private final PlayerInfo info;

	private CutsceneClone(ClientLevel level, GameProfile profile, PlayerInfo info) {
		super(level, profile);
		this.info = info;
	}

	public static CutsceneClone of(Minecraft mc) {
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
		CutsceneClone clone = new CutsceneClone(mc.level, new GameProfile(UUID.randomUUID(), mc.player.getGameProfile().name()), info);
		clone.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, mc.player.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION));
		return clone;
	}

	@Override
	protected PlayerInfo getPlayerInfo() {
		return info;
	}

	// The borrowed tab-list entry says the player is spectating (they are, meanwhile): spectators are drawn as a head only
	@Override
	public boolean isSpectator() {
		return false;
	}

	@Override
	public boolean isCreative() {
		return false;
	}
}
