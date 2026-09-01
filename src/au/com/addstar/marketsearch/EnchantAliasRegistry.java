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
        register(Enchantment.POWER,              "power", "arrowdmg", "arrowdamage");
        register(Enchantment.FLAME,                "flame", "firearrow");
        register(Enchantment.INFINITY,            "inf", "infinity", "infini");
        register(Enchantment.PUNCH,           "punch", "arrowknock");
        register(Enchantment.BINDING_CURSE,             "bind", "binding");
        register(Enchantment.CHANNELING,                "chan", "channel", "channeling");
        register(Enchantment.SHARPNESS,                "dmg", "sharp", "sharpness");
        register(Enchantment.BANE_OF_ARTHROPODS,         "bane", "arthro", "arthropods");
        register(Enchantment.SMITE,             "smite", "undead");
        register(Enchantment.DEPTH_STRIDER,             "strider", "depth");
        register(Enchantment.EFFICIENCY,                 "eff", "efficiency");
        register(Enchantment.UNBREAKING,                "dura", "durability", "unbreaking", "unbreak");
        register(Enchantment.FIRE_ASPECT,               "fire", "aspect", "fireaspect");
        register(Enchantment.FROST_WALKER,              "frost", "frostwalker", "frostwalk");
        register(Enchantment.IMPALING,                  "impale", "impaling");
        register(Enchantment.KNOCKBACK,                 "knock", "knockback");
        register(Enchantment.FORTUNE,         "fort", "fortune");
        register(Enchantment.LOOTING,           "loot", "looting");
        register(Enchantment.LOYALTY,                   "loyal", "loyalty");
        register(Enchantment.LUCK_OF_THE_SEA,                      "luck", "luckofsea");
        register(Enchantment.LURE,                      "lure");
        register(Enchantment.MENDING,                   "mend", "mending");
        register(Enchantment.MULTISHOT,                 "multi", "multishot");
        register(Enchantment.RESPIRATION,                    "air", "respiration", "oxygenation", "oxygen");
        register(Enchantment.PIERCING,                  "pierce", "piercing");
        register(Enchantment.PROTECTION,  "prot", "protection", "protect");
        register(Enchantment.BLAST_PROTECTION,     "blast", "blastprot", "blastprotection");
        register(Enchantment.FEATHER_FALLING,           "fall", "feather", "featherfall");
        register(Enchantment.FIRE_PROTECTION,           "fireprot", "fireprotection", "fireprotect");
        register(Enchantment.PROJECTILE_PROTECTION,     "proj", "projectile", "projprot", "projprotection");
        register(Enchantment.QUICK_CHARGE,              "charge", "quickcharge");
        register(Enchantment.RIPTIDE,                   "rip", "riptide");
        register(Enchantment.SILK_TOUCH,                "silk", "silktouch");
        register(Enchantment.SOUL_SPEED,                "soul", "soulspeed");
        register(Enchantment.SWIFT_SNEAK,               "swift", "swiftsneak");
        register(Enchantment.SWEEPING_EDGE,             "sweep", "sweeping", "sweepingedge");
        register(Enchantment.THORNS,                    "thorn", "thorns");
        register(Enchantment.VANISHING_CURSE,           "vanish", "vanishing", "curseofvanishing");
        register(Enchantment.AQUA_AFFINITY,              "aqua", "water", "waterworker", "aquaaffinity");

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