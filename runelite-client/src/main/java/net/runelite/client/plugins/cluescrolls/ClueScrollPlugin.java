/*
 * Copyright (c) 2016-2017, Seth <Sethtroll3@gmail.com>
 * Copyright (c) 2018, Lotto <https://github.com/devLotto>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.cluescrolls;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Query;
import net.runelite.api.Region;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.queries.InventoryItemQuery;
import net.runelite.api.queries.NPCQuery;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.cluescrolls.clues.AnagramClue;
import net.runelite.client.plugins.cluescrolls.clues.CipherClue;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.CoordinateClue;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.EmoteClue;
import net.runelite.client.plugins.cluescrolls.clues.FairyRingClue;
import net.runelite.client.plugins.cluescrolls.clues.HotColdClue;
import net.runelite.client.plugins.cluescrolls.clues.LocationClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;
import net.runelite.client.plugins.cluescrolls.clues.NpcClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.ObjectClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.TextClueScroll;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.QueryRunner;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Clue Scroll"
)
@Slf4j
public class ClueScrollPlugin extends Plugin
{
	private static final Duration WAIT_DURATION = Duration.ofMinutes(4);

	private static final Pattern INITIAL_STRANGE_DEVICE_MESSAGE = Pattern.compile("The device is (.*)");
	private static final Pattern STRANGE_DEVICE_MESSAGE = Pattern.compile("The device is (.*), (.*) last time\\.");
	private static final Pattern FINAL_STRANGE_DEVICE_MESSAGE = Pattern.compile("The device is visibly shaking.*");

	public static final BufferedImage CLUE_SCROLL_IMAGE;
	public static final BufferedImage MAP_ARROW;
	public static final BufferedImage EMOTE_IMAGE;
	public static final BufferedImage SPADE_IMAGE;

	@Getter
	private ClueScroll clue;

	@Getter
	private NPC[] npcsToMark;

	@Getter
	private GameObject[] objectsToMark;

	@Getter
	private Item[] equippedItems;

	@Getter
	private Instant clueTimeout;

	@Inject
	@Getter
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private QueryRunner queryRunner;

	@Inject
	private ClueScrollOverlay clueScrollOverlay;

	@Inject
	private ClueScrollEmoteOverlay clueScrollEmoteOverlay;

	@Inject
	private ClueScrollWorldOverlay clueScrollWorldOverlay;

	@Inject
	private ClueScrollConfig config;

	@Inject
	private WorldMapPointManager worldMapPointManager;

	private ClueScrollWorldMapPoint worldMapPoint;

	private Integer clueItemId;
	private boolean clueItemChanged = false;

	static
	{
		try
		{
			synchronized (ImageIO.class)
			{
				CLUE_SCROLL_IMAGE = ImageIO.read(ClueScrollPlugin.class.getResourceAsStream("clue_scroll.png"));
				MAP_ARROW = ImageIO.read(ClueScrollPlugin.class.getResourceAsStream("clue_arrow.png"));
				EMOTE_IMAGE = ImageIO.read(ClueScrollPlugin.class.getResourceAsStream("emote.png"));
				SPADE_IMAGE = ImageIO.read(ClueScrollPlugin.class.getResourceAsStream("spade.png"));
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Provides
	ClueScrollConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClueScrollConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
	}

	@Override
	protected void shutDown() throws Exception
	{
		clearMapPoint();
	}

	@Override
	public Collection<Overlay> getOverlays()
	{
		return Arrays.asList(clueScrollOverlay, clueScrollEmoteOverlay, clueScrollWorldOverlay);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.SERVER && event.getType() != ChatMessageType.FILTERED)
		{
			return;
		}

		if (clue instanceof HotColdClue && event.getMessage().startsWith("The device is"))
		{
			HotColdClue hotColdClue = (HotColdClue) clue;
			String message = event.getMessage();
			Matcher m1 = FINAL_STRANGE_DEVICE_MESSAGE.matcher(message);
			Matcher m2 = STRANGE_DEVICE_MESSAGE.matcher(message);
			Matcher m3 = INITIAL_STRANGE_DEVICE_MESSAGE.matcher(message);

			// the order that these pattern matchers are checked is important
			if (m1.find())
			{
				// final location for hot cold clue has been found
				WorldPoint localWorld = client.getLocalPlayer().getWorldLocation();

				if (localWorld != null)
				{
					hotColdClue.markFinalSpot(localWorld);
				}
			}
			else if (m2.find())
			{
				String temperature = m2.group(1);
				String difference = m2.group(2);
				WorldPoint localWorld = client.getLocalPlayer().getWorldLocation();

				if (localWorld != null)
				{
					hotColdClue.updatePossibleArea(localWorld, temperature, difference);
				}
			}
			else if (m3.find())
			{
				String temperature = m3.group(1);
				WorldPoint localWorld = client.getLocalPlayer().getWorldLocation();

				if (localWorld != null)
				{
					hotColdClue.updatePossibleArea(localWorld, temperature, "");
				}
			}
		}

		if (!event.getMessage().equals("The strange device cools as you find your treasure.")
			&& !event.getMessage().equals("Well done, you've completed the Treasure Trail!"))
		{
			return;
		}

		resetClue();
	}

	@Subscribe
	public void onMenuOptionClicked(final MenuOptionClicked event)
	{
		if (event.getMenuOption() != null && event.getMenuOption().equals("Read"))
		{
			final ItemComposition itemComposition = itemManager.getItemComposition(event.getId());

			if (itemComposition != null && itemComposition.getName().startsWith("Clue scroll"))
			{
				clueItemId = itemComposition.getId();
				clueItemChanged = true;
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(final ItemContainerChanged event)
	{
		// Check if item was removed from inventory
		if (clue != null && clueItemId != null && event.getItemContainer() == client.getItemContainer(InventoryID.INVENTORY))
		{
			final Stream<Item> items = Arrays.stream(event.getItemContainer().getItems());

			// Check if clue was removed from inventory
			if (items.noneMatch(item -> itemManager.getItemComposition(item.getId()).getId() == clueItemId))
			{
				resetClue();
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals("cluescroll") && !config.displayHintArrows())
		{
			client.clearHintArrow();
		}
	}

	@Subscribe
	public void onGameStateChanged(final GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			resetClue();
		}
	}

	@Subscribe
	public void onGameTick(final GameTick event)
	{
		npcsToMark = null;
		objectsToMark = null;
		equippedItems = null;

		// If we have location clue, set world location before all other types of clues
		// to allow NPCs and objects to override it when needed
		if (clue instanceof LocationClueScroll)
		{
			final WorldPoint location = ((LocationClueScroll) clue).getLocation();

			if (config.displayHintArrows() && location != null)
			{
				client.setHintArrow(location);
			}

			setMapPoint(location);
		}

		if (clue instanceof NpcClueScroll)
		{
			String npc = ((NpcClueScroll) clue).getNpc();

			if (npc != null)
			{
				Query query = new NPCQuery().nameEquals(npc);
				npcsToMark = queryRunner.runQuery(query);

				// Set hint arrow to first NPC found as there can only be 1 hint arrow
				if (npcsToMark.length >= 1)
				{
					if (config.displayHintArrows())
					{
						client.setHintArrow(npcsToMark[0]);
					}

					setMapPoint(npcsToMark[0].getWorldLocation());
				}
			}
		}

		if (clue instanceof ObjectClueScroll)
		{
			final ObjectClueScroll objectClueScroll = (ObjectClueScroll) clue;
			int objectId = objectClueScroll.getObjectId();

			if (objectId != -1)
			{
				// Match object with location every time
				final WorldPoint location = objectClueScroll.getLocation();

				if (location != null)
				{
					final LocalPoint localLocation = LocalPoint.fromWorld(client, location);

					if (localLocation != null)
					{
						final Region region = client.getRegion();
						final Tile[][][] tiles = region.getTiles();
						final Tile tile = tiles[client.getPlane()][localLocation.getRegionX()][localLocation.getRegionY()];

						objectsToMark = Arrays.stream(tile.getGameObjects())
							.filter(object -> object != null && object.getId() == objectId)
							.toArray(GameObject[]::new);

						// Set hint arrow to first object found as there can only be 1 hint arrow
						if (config.displayHintArrows() && objectsToMark.length >= 1)
						{
							client.setHintArrow(objectsToMark[0].getWorldLocation());
						}
					}
				}
			}
		}

		if (clue instanceof EmoteClue)
		{
			ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
			
			if (container != null)
			{
				equippedItems = container.getItems();
			}
		}

		if (clue instanceof CoordinateClue)
		{
			ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);

			if (container != null)
			{
				equippedItems = container.getItems();
			}
		}

		ClueScroll clue = findClueScroll();

		if (clue == null && this.clue != null)
		{
			// If clue window isn't open, and we don't have a map clue in inventory,
			// but we have recorded the player having a clue,
			// wait for WAIT_DURATION before discarding the knowledge of the player having a clue.
			if (Instant.now().compareTo(clueTimeout.plus(WAIT_DURATION)) < 0)
			{
				return;
			}
		}

		// If we have a clue, save that knowledge
		// so the clue window doesn't have to be open.
		if (clue != null)
		{
			if (clue != this.clue)
			{
				resetClue();
			}

			this.clue = clue;
			this.clueTimeout = Instant.now();
		}
	}

	private void resetClue()
	{
		if (!clueItemChanged)
		{
			clueItemId = null;
		}

		if (clue instanceof HotColdClue)
		{
			((HotColdClue) clue).resetHotCold();
		}

		clueItemChanged = false;
		clue = null;

		clearMapPoint();

		if (config.displayHintArrows())
		{
			client.clearHintArrow();
		}
	}

	private ClueScroll findClueScroll()
	{
		Widget clueScrollText = client.getWidget(WidgetInfo.CLUE_SCROLL_TEXT);

		if (clueScrollText != null)
		{
			// Remove line breaks and also the rare occasion where there are double line breaks
			String text = Text.removeTags(clueScrollText.getText()
					.replaceAll("-<br>", "-")
					.replaceAll("<br>", " ")
					.replaceAll("[ ]+", " ")
					.toLowerCase());

			if (clue != null && clue instanceof TextClueScroll)
			{
				if (((TextClueScroll) clue).getText().equalsIgnoreCase(text))
				{
					return clue;
				}
			}

			if (text.startsWith("this anagram reveals who to speak to next:"))
			{
				return AnagramClue.forText(text);
			}
			else if (text.startsWith("the cipher reveals who to speak to next:"))
			{
				return CipherClue.forText(text);
			}
			else if (text.contains("degrees") && text.contains("minutes"))
			{
				return coordinatesToWorldPoint(text);
			}
			else
			{
				CrypticClue crypticClue = CrypticClue.forText(text);

				if (crypticClue != null)
				{
					return crypticClue;
				}

				EmoteClue emoteClue = EmoteClue.forText(text);

				if (emoteClue != null)
				{
					return emoteClue;
				}

				final FairyRingClue fairyRingClue = FairyRingClue.forText(text);

				if (fairyRingClue != null)
				{
					return fairyRingClue;
				}

				final HotColdClue hotColdClue = HotColdClue.forText(text);

				if (hotColdClue != null)
				{
					return hotColdClue;
				}

				// We have unknown clue, reset
				resetClue();
				return null;
			}
		}

		Item[] result = queryRunner.runQuery(new InventoryItemQuery(InventoryID.INVENTORY));

		if (result == null)
		{
			return null;
		}

		for (Item item : result)
		{
			MapClue clue = MapClue.forItemId(item.getId());

			if (clue != null)
			{
				clueItemId = item.getId();
				clueItemChanged = true;
				return clue;
			}
		}

		return null;
	}

	/**
	 * Example input: "00 degrees 00 minutes north 07 degrees 13 minutes west"
	 * Note: some clues use "1 degree" instead of "01 degrees"
	 */
	private CoordinateClue coordinatesToWorldPoint(String text)
	{
		String[] splitText = text.split(" ");

		if (splitText.length != 10)
		{
			log.warn("Splitting \"" + text + "\" did not result in an array of 10 cells");
			return null;
		}

		if (!splitText[1].startsWith("degree") || !splitText[3].startsWith("minute"))
		{
			log.warn("\"" + text + "\" is not a well formed coordinate string");
			return null;
		}

		int degY = Integer.parseInt(splitText[0]);
		int minY = Integer.parseInt(splitText[2]);

		if (splitText[4].equals("south"))
		{
			degY *= -1;
			minY *= -1;
		}

		int degX = Integer.parseInt(splitText[5]);
		int minX = Integer.parseInt(splitText[7]);

		if (splitText[9].equals("west"))
		{
			degX *= -1;
			minX *= -1;
		}

		return new CoordinateClue(text, coordinatesToWorldPoint(degX, minX, degY, minY));
	}

	/**
	 * This conversion is explained on
	 * http://oldschoolrunescape.wikia.com/wiki/Treasure_Trails/Guide/Coordinates
	 */
	private WorldPoint coordinatesToWorldPoint(int degX, int minX, int degY, int minY)
	{
		// Center of the Observatory
		int x2 = 2440;
		int y2 = 3161;

		x2 += degX * 32 + Math.round(minX / 1.875);
		y2 += degY * 32 + Math.round(minY / 1.875);

		return new WorldPoint(x2, y2, 0);
	}

	private void setMapPoint(WorldPoint point)
	{
		if (worldMapPoint == null)
		{
			worldMapPoint = new ClueScrollWorldMapPoint();
			worldMapPointManager.add(worldMapPoint);
		}
		worldMapPoint.setWorldPoint(point);
	}

	private void clearMapPoint()
	{
		if (worldMapPoint != null)
		{
			worldMapPointManager.remove(worldMapPoint);
			worldMapPoint = null;
		}
	}
}
