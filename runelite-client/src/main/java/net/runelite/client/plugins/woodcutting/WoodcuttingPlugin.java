/*
 * Copyright (c) 2017, Seth <Sethtroll3@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.woodcutting;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.api.ObjectID;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.woodcutting.config.ClueNestTier;
import net.runelite.client.plugins.xptracker.XpTrackerPlugin;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Woodcutting",
	description = "Show woodcutting statistics and/or bird nest notifications",
	tags = {"birds", "nest", "notifications", "overlay", "skilling", "wc", "forestry"},
	enabledByDefault = false
)
@PluginDependency(XpTrackerPlugin.class)
@Slf4j
public class WoodcuttingPlugin extends Plugin
{
	private static final Pattern WOOD_CUT_PATTERN = Pattern.compile("You get (?:some|an)[\\w ]+(?:logs?|mushrooms)\\.");

	@Inject
	private Notifier notifier;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WoodcuttingOverlay overlay;

	@Inject
	private WoodcuttingTreesOverlay treesOverlay;

	@Inject
	private WoodcuttingConfig config;

	@Getter
	@Nullable
	@Setter(AccessLevel.PACKAGE)
	private WoodcuttingSession session;

	@Getter
	@Nullable
	private Axe axe;

	@Getter
	private final Set<GameObject> treeObjects = new HashSet<>();

	@Getter(AccessLevel.PACKAGE)
	private final List<GameObject> roots = new ArrayList<>();
	private final List<NPC> flowers = new ArrayList<>();
	@Getter(AccessLevel.PACKAGE)
	private final List<GameObject> saplingIngredients = new ArrayList<>(5);
	@Getter(AccessLevel.PACKAGE)
	private final GameObject[] saplingOrder = new GameObject[3];

	@Getter(AccessLevel.PACKAGE)
	private final List<TreeRespawn> respawns = new ArrayList<>();
	private boolean recentlyLoggedIn;
	private int currentPlane;
	private ClueNestTier clueTierSpawned;

	@Provides
	WoodcuttingConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WoodcuttingConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);
		overlayManager.add(treesOverlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		overlayManager.remove(treesOverlay);
		respawns.clear();
		treeObjects.clear();
		roots.clear();
		flowers.clear();
		saplingIngredients.clear();
		Arrays.fill(saplingOrder, null);
		session = null;
		axe = null;
		clueTierSpawned = null;
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		recentlyLoggedIn = false;
		clueTierSpawned = null;
		currentPlane = client.getPlane();

		respawns.removeIf(TreeRespawn::isExpired);

		if (session == null || session.getLastChopping() == null)
		{
			return;
		}

		if (axe != null && axe.matchesChoppingAnimation(client.getLocalPlayer()))
		{
			session.setLastChopping();
			return;
		}

		Duration statTimeout = Duration.ofMinutes(config.statTimeout());
		Duration sinceCut = Duration.between(session.getLastChopping(), Instant.now());

		if (sinceCut.compareTo(statTimeout) >= 0)
		{
			session = null;
			axe = null;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.SPAM && event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		if (WOOD_CUT_PATTERN.matcher(event.getMessage()).matches())
		{
			if (session == null)
			{
				session = new WoodcuttingSession();
			}

			session.setLastChopping();
		}

		var msg = event.getMessage();
		if (msg.contains("A bird's nest falls out of the tree") && config.showNestNotification())
		{
			if (clueTierSpawned == null || clueTierSpawned.ordinal() >= config.clueNestNotifyTier().ordinal())
			{
				notifier.notify("A bird nest has spawned!");
			}
			// Clear the clue tier that has previously spawned
			clueTierSpawned = null;
		}

		if (msg.startsWith("The sapling seems to love"))
		{
			int ingredientNum = msg.contains("first") ? 1 : (msg.contains("second") ? 2 : (msg.contains("third") ? 3 : -1));
			if (ingredientNum == -1)
			{
				log.debug("unable to find ingredient index from message: {}", msg);
				return;
			}

			GameObject ingredientObj = saplingIngredients.stream()
				.filter(obj -> msg.contains(client.getObjectDefinition(obj.getId()).getName().toLowerCase()))
				.findAny()
				.orElse(null);
			if (ingredientObj == null)
			{
				log.debug("unable to find ingredient from message: {}", msg);
				return;
			}

			saplingOrder[ingredientNum - 1] = ingredientObj;
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned itemSpawned)
	{
		if (clueTierSpawned == null)
		{
			// This will be set only if one of the clue nests has spawned. It will then be reset the next game tick.
			clueTierSpawned = ClueNestTier.getTierFromItem(itemSpawned.getItem().getId());
		}
	}

	@Subscribe
	public void onGameObjectSpawned(final GameObjectSpawned event)
	{
		GameObject gameObject = event.getGameObject();
		Tree tree = Tree.findTree(gameObject.getId());

		if (tree == Tree.REDWOOD)
		{
			treeObjects.add(gameObject);
		}

		switch (gameObject.getId())
		{
			case ObjectID.TREE_ROOTS:
			case ObjectID.TREE_ROOTS_47483: // glowing roots
				if (roots.isEmpty() && config.forestryRisingRootsNotification())
				{
					notifier.notify("A Rising Roots Forestry event spawned!");
				}

				roots.add(gameObject);
				break;
			case ObjectID.STRUGGLING_SAPLING:
			case ObjectID.STRUGGLING_SAPLING_47485:
			case ObjectID.STRUGGLING_SAPLING_47487:
			case ObjectID.STRUGGLING_SAPLING_47488:
			case ObjectID.STRUGGLING_SAPLING_47490:
			case ObjectID.STRUGGLING_SAPLING_47491:
				if (config.forestryStrugglingSaplingNotification())
				{
					notifier.notify("A Struggling Sapling Forestry event spawned!");
				}
				break;
			case ObjectID.ROTTING_LEAVES:
			case ObjectID.GREEN_LEAVES:
			case ObjectID.DROPPINGS:
			case ObjectID.WILD_MUSHROOMS:
			case ObjectID.WILD_MUSHROOMS_47497:
			case ObjectID.WILD_MUSHROOMS_47498:
			case ObjectID.SPLINTERED_BARK:
				saplingIngredients.add(gameObject);
				break;
		}
	}

	@Subscribe
	public void onGameObjectDespawned(final GameObjectDespawned event)
	{
		final GameObject object = event.getGameObject();

		Tree tree = Tree.findTree(object.getId());
		if (tree != null)
		{
			if (tree.getRespawnTime() != null && !recentlyLoggedIn && currentPlane == object.getPlane())
			{
				log.debug("Adding respawn timer for {} tree at {}", tree, object.getLocalLocation());

				Point min = object.getSceneMinLocation();
				WorldPoint base = WorldPoint.fromScene(client, min.getX(), min.getY(), client.getPlane());
				TreeRespawn treeRespawn = new TreeRespawn(tree, object.sizeX() - 1, object.sizeY() - 1,
					base, Instant.now(), (int) tree.getRespawnTime(base.getRegionID()).toMillis());
				respawns.add(treeRespawn);
			}

			if (tree == Tree.REDWOOD)
			{
				treeObjects.remove(event.getGameObject());
			}
		}

		switch (object.getId())
		{
			case ObjectID.TREE_ROOTS:
			case ObjectID.TREE_ROOTS_47483: // glowing roots
				roots.remove(object);
				break;
			case ObjectID.ROTTING_LEAVES:
			case ObjectID.GREEN_LEAVES:
			case ObjectID.DROPPINGS:
			case ObjectID.WILD_MUSHROOMS:
			case ObjectID.WILD_MUSHROOMS_47497:
			case ObjectID.WILD_MUSHROOMS_47498:
			case ObjectID.SPLINTERED_BARK:
				saplingIngredients.remove(object);
				if (saplingIngredients.isEmpty())
				{
					Arrays.fill(saplingOrder, null);
					log.debug("Struggling Sapling event is over");
				}
				break;
		}
	}

	@Subscribe
	public void onGameStateChanged(final GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case HOPPING:
				respawns.clear();
			case LOADING:
				treeObjects.clear();
				roots.clear();
				saplingIngredients.clear();
				Arrays.fill(saplingOrder, null);
				break;
			case LOGGED_IN:
				// After login trees that are depleted will be changed,
				// wait for the next game tick before watching for
				// trees to despawn
				recentlyLoggedIn = true;
				break;
		}
	}

	@Subscribe
	public void onAnimationChanged(final AnimationChanged event)
	{
		Player local = client.getLocalPlayer();

		if (event.getActor() != local)
		{
			return;
		}

		int animId = local.getAnimation();
		Axe axe = Axe.findAxeByAnimId(animId);
		if (axe != null)
		{
			this.axe = axe;
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (npc.getId() == NpcID.FLOWERING_BUSH)
		{
			if (flowers.isEmpty() && config.forestryFloweringTreeNotification())
			{
				notifier.notify("A Flowering Tree Forestry event spawned!");
			}

			flowers.add(npc);
		}
		else if (npc.getId() == NpcID.WOODCUTTING_LEPRECHAUN && config.forestryLeprechaunNotification())
		{
			notifier.notify("A Leprechaun event spawned!");
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		flowers.remove(npc);
	}
}
