package au.com.addstar.marketsearch;

import net.minecraft.server.v1_10_R1.NBTTagCompound;

import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_10_R1.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/**
 * This class lets you get the potion type from a potion item stack, using the NBT tags introduced in Minecraft 1.9
 *
 * Source: https://gist.github.com/forseth11/31b914badd376dbd38e2
 *
 * @author Michael Forseth
 */
public class PotionInfo {
	private PotionType type;
	private boolean strong, extended, linger, splash;

	public enum PotionType {
		FIRE_RESISTANCE, INSTANT_DAMAGE, INSTANT_HEAL, INVISIBILITY, JUMP, LUCK, NIGHT_VISION, POISON, REGEN, SLOWNESS, SPEED, STRENGTH, WATER, WATER_BREATHING, WEAKNESS, EMPTY, MUNDANE, THICK, AWKWARD;
	}

	/**
	 * Construct a new potion of the given type.
	 *
	 * @param potionType The potion type
	 */
	public PotionInfo(PotionType potionType) {
		this.type = potionType;
		this.strong = false;
		this.extended = false;
		this.linger = false;
		this.splash = false;
	}

	/**
	 * Create a new potion of the given type and strength.
	 *
	 * @param type   The type of potion.
	 * @param strong True if the potion is a strong potion
	 */
	public PotionInfo(PotionType type, boolean strong) {
		this(type);
		if (type == null)
			throw new IllegalArgumentException("Type cannot be null");
		if (type != PotionType.WATER)
			throw new IllegalArgumentException("Water bottles cannot be strong!");
		this.strong = strong;
	}

	/**
	 * This constructs an instance of PotionInfo.
	 *
	 * @param type
	 * @param strong
	 * @param extended
	 * @param linger
	 * @param splash
	 */
	public PotionInfo(PotionType type, boolean strong, boolean extended, boolean linger, boolean splash) {
		this.type = type;
		this.strong = strong;
		this.extended = extended;
		this.linger = linger;
		this.splash = splash;
	}

	/**
	 * Chain this to the constructor to make the potion a splash potion.
	 *
	 * @return The potion.
	 */
	public PotionInfo splash() {
		setSplash(true);
		return this;
	}

	/**
	 * Chain this to the constructor to extend the potion's duration.
	 *
	 * @return The potion.
	 */
	public PotionInfo extend() {
		setHasExtendedDuration(true);
		return this;
	}

	/**
	 * Chain this to the constructor to make potion a linger potion.
	 *
	 * @return The potion.
	 */
	public PotionInfo linger() {
		setLinger(true);
		return this;
	}

	/**
	 * Chain this to the constructor to make potion a strong potion.
	 *
	 * @return The potion.
	 */
	public PotionInfo strong() {
		setStrong(true);
		return this;
	}

	/**
	 * Applies the effects of this potion to the given {@link ItemStack}. The
	 * ItemStack must be a potion.
	 *
	 * @param to The itemstack to apply to
	 */
	public void apply(ItemStack to) throws Exception {
		if (to == null) {
			throw new Exception("itemstack cannot be null");
		}
		if (to.getType() != Material.POTION) {
			throw new Exception("given itemstack is not a potion");
		}
		to = toItemStack(to.getAmount()).clone();
	}

	/**
	 * This converts PotionInfo to an ItemStack
	 * NOTICE: This does not allow a way to change the level of the potion. This will work for only default minecraft potions.
	 *
	 * @param amount
	 * @return ItemStack of a potion. NULL if it fails.
	 */
	public ItemStack toItemStack(int amount) {
		ItemStack item = new ItemStack(Material.POTION, amount);
		if (splash) {
			item = new ItemStack(Material.SPLASH_POTION, amount);
		} else if (linger) {
			item = new ItemStack(Material.LINGERING_POTION, amount);
		}
		net.minecraft.server.v1_10_R1.ItemStack stack = CraftItemStack.asNMSCopy(item);
		NBTTagCompound tagCompound = stack.getTag();
		if (tagCompound == null) {
			tagCompound = new NBTTagCompound();
		}
		String tag = "";
		if (type == PotionType.FIRE_RESISTANCE) {
			if (extended) {
				tag = "long_fire_resistance";
			} else {
				tag = "fire_resistance";
			}
		} else if (type == PotionType.INSTANT_DAMAGE) {
			if (strong) {
				tag = "strong_harming";
			} else {
				tag = "harming";
			}
		} else if (type == PotionType.INSTANT_HEAL) {
			if (strong) {
				tag = "strong_healing";
			} else {
				tag = "healing";
			}
		} else if (type == PotionType.INVISIBILITY) {
			if (extended) {
				tag = "long_invisibility";
			} else {
				tag = "invisibility";
			}
		} else if (type == PotionType.JUMP) {
			if (extended) {
				tag = "long_leaping";
			} else if (strong) {
				tag = "strong_leaping";
			} else {
				tag = "leaping";
			}
		} else if (type == PotionType.LUCK) {
			tag = "luck";
		} else if (type == PotionType.NIGHT_VISION) {
			if (extended) {
				tag = "long_night_vision";
			} else {
				tag = "night_vision";
			}
		} else if (type == PotionType.POISON) {
			if (extended) {
				tag = "long_poison";
			} else if (strong) {
				tag = "strong_poison";
			} else {
				tag = "poison";
			}
		} else if (type == PotionType.REGEN) {
			if (extended) {
				tag = "long_regeneration";
			} else if (strong) {
				tag = "strong_regeneration";
			} else {
				tag = "regeneration";
			}
		} else if (type == PotionType.SLOWNESS) {
			if (extended) {
				tag = "long_slowness";
			} else {
				tag = "slowness";
			}
		} else if (type == PotionType.SPEED) {
			if (extended) {
				tag = "long_swiftness";
			} else if (strong) {
				tag = "strong_swiftness";
			} else {
				tag = "swiftness";
			}
		} else if (type == PotionType.STRENGTH) {
			if (extended) {
				tag = "long_strength";
			} else if (strong) {
				tag = "strong_strength";
			} else {
				tag = "strength";
			}
		} else if (type == PotionType.WATER_BREATHING) {
			if (extended) {
				tag = "long_water_breathing";
			} else {
				tag = "water_breathing";
			}
		} else if (type == PotionType.WATER) {
			tag = "water";
		} else if (type == PotionType.WEAKNESS) {
			if (extended) {
				tag = "long_weakness";
			} else {
				tag = "weakness";
			}
		} else if (type == PotionType.EMPTY) {
			tag = "empty";
		} else if (type == PotionType.MUNDANE) {
			tag = "mundane";
		} else if (type == PotionType.THICK) {
			tag = "thick";
		} else if (type == PotionType.AWKWARD) {
			tag = "awkward";
		} else {
			return null;
		}
		tagCompound.setString("Potion", "minecraft:" + tag);
		stack.setTag(tagCompound);
		return CraftItemStack.asBukkitCopy(stack);
	}

	/**
	 * This parses an ItemStack into an instance of PotionInfo.
	 * This lets you get the potion type, if the potion is strong, if the potion is long,
	 * if the potion is lingering, and if the potion is a splash potion.
	 *
	 * @param item
	 * @return PotionInfo. If it fails to parse, or the item argument is not a valid potion this will return null.
	 */
	public static PotionInfo fromItemStack(ItemStack item) {
		if (item == null) {
			throw new IllegalArgumentException("item cannot be null");
		}

		if (item.getType() != Material.POTION &&
				item.getType() != Material.SPLASH_POTION &&
				item.getType() != Material.LINGERING_POTION) {
			throw new IllegalArgumentException("item is not a potion");
		}
		net.minecraft.server.v1_10_R1.ItemStack stack = CraftItemStack.asNMSCopy(item);
		NBTTagCompound tagCompound = stack.getTag();
		if (tagCompound != null && !tagCompound.getString("Potion").isEmpty()) {

			String tag = tagCompound.getString("Potion").replace("minecraft:", "");
			PotionType type = null;
			boolean strong = tag.contains("strong");
			boolean extended = tag.contains("long");
			if (tag.equals("fire_resistance") || tag.equals("long_fire_resistance")) {
				type = PotionType.FIRE_RESISTANCE;
			} else if (tag.equals("harming") || tag.equals("strong_harming")) {
				type = PotionType.INSTANT_DAMAGE;
			} else if (tag.equals("healing") || tag.equals("strong_healing")) {
				type = PotionType.INSTANT_HEAL;
			} else if (tag.equals("invisibility") || tag.equals("long_invisibility")) {
				type = PotionType.INVISIBILITY;
			} else if (tag.equals("leaping") || tag.equals("long_leaping") || tag.equals("strong_leaping")) {
				type = PotionType.JUMP;
			} else if (tag.equals("luck")) {
				type = PotionType.LUCK;
			} else if (tag.equals("night_vision") || tag.equals("long_night_vision")) {
				type = PotionType.NIGHT_VISION;
			} else if (tag.equals("poison") || tag.equals("long_poison") || tag.equals("strong_poison")) {
				type = PotionType.POISON;
			} else if (tag.equals("regeneration") || tag.equals("long_regeneration") || tag.equals("strong_regeneration")) {
				type = PotionType.REGEN;
			} else if (tag.equals("slowness") || tag.equals("long_slowness")) {
				type = PotionType.SLOWNESS;
			} else if (tag.equals("swiftness") || tag.equals("long_swiftness") || tag.equals("strong_swiftness")) {
				type = PotionType.SPEED;
			} else if (tag.equals("strength") || tag.equals("long_strength") || tag.equals("strong_strength")) {
				type = PotionType.STRENGTH;
			} else if (tag.equals("water_breathing") || tag.equals("long_water_breathing")) {
				type = PotionType.WATER_BREATHING;
			} else if (tag.equals("water")) {
				type = PotionType.WATER;
			} else if (tag.equals("weakness") || tag.equals("long_weakness")) {
				type = PotionType.WEAKNESS;
			} else if (tag.equals("empty")) {
				type = PotionType.EMPTY;
			} else if (tag.equals("mundane")) {
				type = PotionType.MUNDANE;
			} else if (tag.equals("thick")) {
				type = PotionType.THICK;
			} else if (tag.equals("awkward")) {
				type = PotionType.AWKWARD;
			} else {
				return null;
			}

			return new PotionInfo(type, strong, extended,
					item.getType() == Material.LINGERING_POTION,
					item.getType() == Material.SPLASH_POTION);
		} else {
			return null;
		}
	}

	/**
	 * This gets the potion type
	 *
	 * @return PotionType
	 */
	public PotionType getType() {
		return type;
	}

	/**
	 * Sets the PotionType for this PotionInfo
	 *
	 * @param type
	 */
	public void setType(PotionType type) {
		this.type = type;
	}

	/**
	 * A strong potion is a potion which is level II.
	 *
	 * @return boolean. True if the potion is strong.
	 */
	public boolean isStrong() {
		return strong;
	}

	/**
	 * This sets if the PotionInfo is strong.
	 *
	 * @param strong
	 */
	public void setStrong(boolean strong) {
		this.strong = strong;
	}

	/**
	 * A long potion is an extended duration potion.
	 *
	 * @return boolen. True if the potion is the extended type.
	 */
	public boolean isExtendedDuration() {
		return extended;
	}

	/**
	 * This changes the extended value for PotionInfo.
	 *
	 * @param extended
	 */
	public void setHasExtendedDuration(boolean extended) {
		this.extended = extended;
	}

	/**
	 * This lets you know if PotionInfo is a lingering potion.
	 *
	 * @return boolean. True if the potion is a lingering potion.
	 */
	public boolean isLinger() {
		return linger;
	}

	/**
	 * Set linger to true or false.
	 *
	 * @param linger
	 */
	public void setLinger(boolean linger) {
		this.linger = linger;
	}

	/**
	 * Checks if a potion is a splash potion.
	 *
	 * @return boolean. True if the potion is a splash potion.
	 */
	public boolean isSplash() {
		return splash;
	}

	/**
	 * This sets this PotionInfo to a splash potion.
	 *
	 * @param splash
	 */
	public void setSplash(boolean splash) {
		this.splash = splash;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object object) {
		if (object instanceof PotionInfo) {
			PotionInfo test = (PotionInfo) object;
			if (test.type.equals(type) && test.extended == extended && test.linger == linger && test.splash == splash) {
				return true;
			}
		}
		return false;
	}

	/**
	 * This will make this instance of PotionInfo act as if it was brewed with an ingredient.
	 *
	 * @param ingredient
	 */
	public void brew(ItemStack ingredient) {
		if (ingredient != null) {
			if (ingredient.getType().equals(Material.NETHER_WARTS)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.AWKWARD;
				}
			} else if (ingredient.getType().equals(Material.SULPHUR)) {
				splash = true;
			} else if (ingredient.getType().equals(Material.GLOWSTONE_DUST)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.THICK;
					if (!extended) {
						strong = true;
					}
				} else {
					if (!extended) {
						strong = true;
					}
				}
			} else if (ingredient.getType().equals(Material.REDSTONE)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
					extended = true;
				} else {
					if (!strong) {
						extended = true;
					}
				}
			} else if (ingredient.getType().equals(Material.DRAGONS_BREATH)) {
				if (splash) {
					linger = true;
				}
			} else if (ingredient.getType().equals(Material.SPIDER_EYE)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
				} else if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.POISON;
				}
			} else if (ingredient.getType().equals(Material.BLAZE_POWDER)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
				} else if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.STRENGTH;
				}
			} else if (ingredient.getType().equals(Material.GHAST_TEAR)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
				} else if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.REGEN;
				}
			} else if (ingredient.getType().equals(Material.SPECKLED_MELON)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
				} else if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.INSTANT_HEAL;
				}
			} else if (ingredient.getType().equals(Material.RABBIT_FOOT)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
				} else if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.JUMP;
				}
			} else if (ingredient.getType().equals(Material.SUGAR)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
				} else if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.SPEED;
				}
			} else if (ingredient.getType().equals(Material.MAGMA_CREAM)) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.MUNDANE;
				} else if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.FIRE_RESISTANCE;
				}
			} else if (ingredient.getType().equals(Material.FERMENTED_SPIDER_EYE) && !splash && !linger) {
				if (type.equals(PotionType.WATER)) {
					type = PotionType.WEAKNESS;
				} else if (type.equals(PotionType.NIGHT_VISION)) {
					type = PotionType.INVISIBILITY;
				} else if (type.equals(PotionType.INSTANT_HEAL)) {
					type = PotionType.INSTANT_DAMAGE;
				} else if (type.equals(PotionType.POISON)) {
					type = PotionType.INSTANT_DAMAGE;
				} else if (type.equals(PotionType.JUMP) && !strong && !extended && !splash) {
					type = PotionType.SLOWNESS;
				} else if (type.equals(PotionType.FIRE_RESISTANCE)) {
					type = PotionType.SLOWNESS;
				} else if (type.equals(PotionType.SPEED)) {
					type = PotionType.SLOWNESS;
				}
			} else if (ingredient.getType().equals(Material.GOLDEN_CARROT)) {
				if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.NIGHT_VISION;
				}
			} else if (ingredient.getType().equals(Material.RAW_FISH) && ingredient.getDurability() == 3) {
				if (type.equals(PotionType.AWKWARD)) {
					type = PotionType.WATER_BREATHING;
				}
			}
		}
	}

	@Override
	public String toString() {
		String potionName = this.type.toString().replace("_", " ");

		// Change the potion name to initial caps
		String potionDescription = potionName.substring(0, 1) + potionName.substring(1).toLowerCase();

		if (this.splash)
			potionDescription = "Splash " + potionDescription;

		if (this.linger)
			potionDescription = "Lingering " + potionDescription;

		if (this.strong)
			potionDescription += " II";

		if (this.extended)
			return potionDescription + " (extended)";
		else
			return potionDescription;
	}
}