package de.farmbot;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class FarmBotClient implements ClientModInitializer {

	// ---- Einstellungen -------------------------------------------------
	/** Ab so viel Hungerpunkten wird gegessen (6 Punkte = 3 Hungerbalken). */
	private static final int EAT_BELOW_OR_EQUAL = 6;
	/** Hunger gilt als voll bei 20 Punkten (10 Balken). */
	private static final int FOOD_FULL = 20;
	/** Um so viele Blöcke muss sich die Seiten-Koordinate ändern, damit gedreht wird. */
	private static final double LANE_CHANGE_THRESHOLD = 1.0;
	/** Slot zum Essen (0 = Slot 1). */
	private static final int FOOD_SLOT = 0;
	/** Sicherheits-Timeout beim Essen in Ticks (20 Ticks = 1 Sekunde). */
	private static final int EAT_TIMEOUT_TICKS = 20 * 45;
	// --------------------------------------------------------------------

	private enum State { IDLE, WALK, TURN, EAT }

	private static KeyMapping toggleKey;

	private final Random random = new Random();

	private State state = State.IDLE;

	// Bahn-Erkennung
	private boolean laneIsX;
	private double laneRef;

	// Drehung
	private int turnTick;
	private int turnDelay;
	private int turnDuration;
	private float turnStartYaw;
	private float turnStartPitch;
	private float pitchWobble;

	// Essen
	private int savedSlot;
	private int eatTick;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath("farmbot", "main"));

		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.farmbot.toggle",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;

		boolean toggled = false;
		while (toggleKey.consumeClick()) {
			toggled = true;
		}

		if (player == null || mc.level == null) {
			if (state != State.IDLE) {
				state = State.IDLE;
				releaseAll(mc);
			}
			return;
		}

		if (toggled) {
			if (state == State.IDLE) {
				start(mc, player);
			} else {
				stop(mc, player, Component.translatable("farmbot.off"));
			}
			return;
		}

		if (state == State.IDLE) {
			return;
		}

		// Bei offenem Menü / Pause nichts drücken
		if (mc.screen != null) {
			releaseAll(mc);
			return;
		}

		switch (state) {
			case WALK -> tickWalk(mc, player);
			case TURN -> tickTurn(mc, player);
			case EAT -> tickEat(mc, player);
			default -> { }
		}
	}

	// ---- Start / Stop ---------------------------------------------------

	private void start(Minecraft mc, LocalPlayer player) {
		// Seiten-Achse bestimmen: Die Achse, entlang der D dich NICHT bewegt.
		double yaw = Math.toRadians(player.getYRot());
		double rightX = -Math.cos(yaw);
		double rightZ = -Math.sin(yaw);
		laneIsX = Math.abs(rightX) <= Math.abs(rightZ);
		laneRef = laneIsX ? player.getX() : player.getZ();

		state = State.WALK;
		mc.gui.setOverlayMessage(Component.translatable("farmbot.on"), false);
	}

	private void stop(Minecraft mc, LocalPlayer player, Component message) {
		if (state == State.EAT) {
			player.getInventory().setSelectedSlot(savedSlot);
		}
		state = State.IDLE;
		releaseAll(mc);
		mc.gui.setOverlayMessage(message, false);
	}

	private void releaseAll(Minecraft mc) {
		mc.options.keyRight.setDown(false);
		mc.options.keyAttack.setDown(false);
		mc.options.keyUse.setDown(false);
	}

	// ---- Laufen ---------------------------------------------------------

	private void tickWalk(Minecraft mc, LocalPlayer player) {
		// Hunger hat Vorrang
		if (player.getFoodData().getFoodLevel() <= EAT_BELOW_OR_EQUAL) {
			beginEat(mc, player);
			return;
		}

		mc.options.keyRight.setDown(true);
		mc.options.keyAttack.setDown(true);
		mc.options.keyUse.setDown(false);

		double current = laneIsX ? player.getX() : player.getZ();
		if (Math.abs(current - laneRef) >= LANE_CHANGE_THRESHOLD) {
			beginTurn(mc, player);
		}
	}

	// ---- Drehung --------------------------------------------------------

	private void beginTurn(Minecraft mc, LocalPlayer player) {
		state = State.TURN;
		turnTick = 0;
		turnDelay = 2 + random.nextInt(4);          // kleine Reaktionszeit
		turnDuration = 10 + random.nextInt(7);      // 10-16 Ticks (ca. 0,5-0,8 s)
		turnStartYaw = player.getYRot();
		turnStartPitch = player.getXRot();
		pitchWobble = (random.nextFloat() - 0.5f) * 3.0f; // max. +-1,5 Grad zwischendurch

		mc.options.keyRight.setDown(false);
		mc.options.keyAttack.setDown(false);
		mc.options.keyUse.setDown(false);
	}

	private void tickTurn(Minecraft mc, LocalPlayer player) {
		turnTick++;
		int t = turnTick - turnDelay;
		if (t <= 0) {
			return;
		}

		if (t >= turnDuration) {
			// Endwert exakt: Yaw +180, Pitch unverändert
			player.setYRot(turnStartYaw + 180.0f);
			player.setXRot(turnStartPitch);
			laneRef = laneIsX ? player.getX() : player.getZ();
			state = State.WALK;
			return;
		}

		float p = (float) t / (float) turnDuration;
		float ease = p * p * (3.0f - 2.0f * p); // sanfter Start und Ende
		player.setYRot(turnStartYaw + 180.0f * ease);
		player.setXRot(turnStartPitch + pitchWobble * (float) Math.sin(Math.PI * p));
	}

	// ---- Essen ----------------------------------------------------------

	private void beginEat(Minecraft mc, LocalPlayer player) {
		ItemStack food = player.getInventory().getItem(FOOD_SLOT);
		if (food.isEmpty() || !food.has(DataComponents.FOOD)) {
			stop(mc, player, Component.translatable("farmbot.no_food"));
			return;
		}

		savedSlot = player.getInventory().getSelectedSlot();
		player.getInventory().setSelectedSlot(FOOD_SLOT);
		eatTick = 0;
		state = State.EAT;

		mc.options.keyRight.setDown(false);
		mc.options.keyAttack.setDown(false);
		mc.options.keyUse.setDown(false);
	}

	private void tickEat(Minecraft mc, LocalPlayer player) {
		eatTick++;

		boolean full = player.getFoodData().getFoodLevel() >= FOOD_FULL;
		ItemStack food = player.getInventory().getItem(FOOD_SLOT);
		boolean noFood = food.isEmpty() || !food.has(DataComponents.FOOD);

		if (full || noFood || eatTick > EAT_TIMEOUT_TICKS) {
			mc.options.keyUse.setDown(false);
			player.getInventory().setSelectedSlot(savedSlot);

			if (full) {
				laneRef = laneIsX ? player.getX() : player.getZ();
				state = State.WALK;
			} else {
				state = State.IDLE;
				releaseAll(mc);
				mc.gui.setOverlayMessage(Component.translatable("farmbot.no_food"), false);
			}
			return;
		}

		mc.options.keyRight.setDown(false);
		mc.options.keyAttack.setDown(false);
		// erst ein paar Ticks nach dem Slot-Wechsel Rechtsklick halten
		mc.options.keyUse.setDown(eatTick >= 3);
	}
}
