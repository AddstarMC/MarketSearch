package au.com.addstar.marketsearch;

import org.bukkit.enchantments.Enchantment;

import java.util.*;
import java.util.stream.Collectors;

public class EnchantAliasRegistry {
    // EnumMap is slightly more efficient than HashMap for enum keys
    private final Map<Enchantment, List<String>> enchantToAliases = new HashMap<>();
    private final Map<String, Enchantment> aliasToEnchant   = new HashMap<>();

    private final MarketSearch plugin;
    public EnchantAliasRegistry(MarketSearch plugin) {
        this.plugin = plugin;
    }

    public void loadEnchants() {
        enchantToAliases.clear();
        aliasToEnchant.clear();

        // register each enchant with one or more aliases:
        register(Enchantment.ARROW_DAMAGE,              "power", "arrowdmg", "arrowdamage");
        register(Enchantment.ARROW_FIRE,                "flame", "firearrow");
        register(Enchantment.ARROW_INFINITE,            "inf", "infinity", "infini");
        register(Enchantment.ARROW_KNOCKBACK,           "punch", "arrowknock");
        register(Enchantment.BINDING_CURSE,             "bind", "binding");
        register(Enchantment.CHANNELING,                "chan", "channel", "channeling");
        register(Enchantment.DAMAGE_ALL,                "dmg", "sharp", "sharpness");
        register(Enchantment.DAMAGE_ARTHROPODS,         "bane", "arthro", "arthropods");
        register(Enchantment.DAMAGE_UNDEAD,             "smite", "undead");
        register(Enchantment.DEPTH_STRIDER,             "strider", "depth");
        register(Enchantment.DIG_SPEED,                 "eff", "efficiency");
        register(Enchantment.DURABILITY,                "dura", "durability", "unbreaking", "unbreak");
        register(Enchantment.FIRE_ASPECT,               "fire", "aspect", "fireaspect");
        register(Enchantment.FROST_WALKER,              "frost", "frostwalker", "frostwalk");
        register(Enchantment.IMPALING,                  "impale", "impaling");
        register(Enchantment.KNOCKBACK,                 "knock", "knockback");
        register(Enchantment.LOOT_BONUS_BLOCKS,         "fort", "fortune");
        register(Enchantment.LOOT_BONUS_MOBS,           "loot", "looting");
        register(Enchantment.LOYALTY,                   "loyal", "loyalty");
        register(Enchantment.LUCK,                      "luck", "luckofsea");
        register(Enchantment.LURE,                      "lure");
        register(Enchantment.MENDING,                   "mend", "mending");
        register(Enchantment.MULTISHOT,                 "multi", "multishot");
        register(Enchantment.OXYGEN,                    "air", "respiration", "oxygenation", "oxygen");
        register(Enchantment.PIERCING,                  "pierce", "piercing");
        register(Enchantment.PROTECTION_ENVIRONMENTAL,  "prot", "protection", "protect");
        register(Enchantment.PROTECTION_EXPLOSIONS,     "blast", "blastprot", "blastprotection");
        register(Enchantment.PROTECTION_FALL,           "fall", "feather", "featherfall");
        register(Enchantment.PROTECTION_FIRE,           "fireprot", "fireprotection", "fireprotect");
        register(Enchantment.PROTECTION_PROJECTILE,     "proj", "projectile", "projprot", "projprotection");
        register(Enchantment.QUICK_CHARGE,              "charge", "quickcharge");
        register(Enchantment.RIPTIDE,                   "rip", "riptide");
        register(Enchantment.SILK_TOUCH,                "silk", "silktouch");
        register(Enchantment.SOUL_SPEED,                "soul", "soulspeed");
        register(Enchantment.SWIFT_SNEAK,               "swift", "swiftsneak");
        register(Enchantment.SWEEPING_EDGE,             "sweep", "sweeping", "sweepingedge");
        register(Enchantment.THORNS,                    "thorn", "thorns");
        register(Enchantment.VANISHING_CURSE,           "vanish", "vanishing", "curseofvanishing");
        register(Enchantment.WATER_WORKER,              "aqua", "water", "waterworker", "aquaaffinity");

        // ---- validation: warn on any missing alias ----
        for (Enchantment ench : Enchantment.values()) {
            if (!enchantToAliases.containsKey(ench)) {
                plugin.getLogger().warning(
                        "No alias registered for enchantment: " + ench.getKey()
                );
            }
        }
    }

    private void register(Enchantment enchant, String... aliases) {
        // store the forward mapping
        List<String> list = Arrays.stream(aliases)
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableList());
        enchantToAliases.put(enchant, list);

        // populate the reverse map
        for (String alias : list) {
            if (aliasToEnchant.put(alias, enchant) != null) {
                throw new IllegalArgumentException("Alias “" + alias + "” registered twice");
            }
        }
    }

    /** @return all registered aliases for this enchant, or an empty list */
    public List<String> getAliases(Enchantment enchant) {
        return enchantToAliases.getOrDefault(enchant, List.of());
    }

    /** @return the first/main registered alias for this enchant, or null */
    public String getMainAlias(Enchantment enchant) {
        List<String> aliases = enchantToAliases.getOrDefault(enchant, null);
        if (aliases == null || aliases.isEmpty()) {
            return null;
        } else {
            return aliases.get(0);
        }
    }

    /** @return the enchant for this alias, or null if none */
    public Enchantment fromAlias(String alias) {
        if (alias == null) return null;
        return aliasToEnchant.get(alias.toLowerCase());
    }
}