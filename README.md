# MarketSearch

A Bukkit/Paper plugin that adds helper commands for **player-run QuickShop market worlds**. It lets
players quickly find the cheapest in-stock shops for an item and teleport straight to them, gives
shop owners a summary of their stock levels, and (optionally) keeps the market healthy over time by
slowly reducing prices on shops owned by players who have gone offline.

Built for **Paper 1.20.4** (Java 21) and **QuickShop-Hikari**. PlotSquared and Slimefun are
supported when present.

## Requirements

- Paper/Spigot 1.20.4+
- [QuickShop-Hikari](https://github.com/QuickShop-Community/QuickShop-Hikari) 6.2.0.11
- Optional: PlotSquared 7.x (maps shops to plot owners), Slimefun (search Slimefun items), Monolith

## Configuration

`plugins/MarketSearch/config.yml`:

```yaml
world: "market"     # the market world that /ms find/sell/buy searches
```

See [Price Reduction](#price-reduction-for-offline-players) below for the additional
`price-reduction` and `database` sections.

## Commands

All commands are under `/marketsearch` (alias `/ms`).

| Command | Description | Permission |
| --- | --- | --- |
| `/ms find <item> [page]` | Find shops **selling** an item, cheapest in-stock first. Click a result to teleport. | `marketsearch.find` |
| `/ms sell <item> [page]` | Find shops **buying** an item (where you can sell). | `marketsearch.find` |
| `/ms find/sell hand` | Search using the item type you are holding. | `marketsearch.find` |
| `/ms find/sell handexact` | Search using the exact held item (durability, NBT, etc). | `marketsearch.find` |
| `/ms find diamond_sword:fire` | Filter results by enchantment alias (e.g. `fire`, `sharp`, `silk`). | `marketsearch.find` |
| `/ms find sf_<item>` | Search for a Slimefun item (requires Slimefun). | `marketsearch.find` |
| `/ms stock [empty\|lowest]` | Summary of your own shops' stock levels. | `marketsearch.stock` |
| `/ms pstock <player> [empty\|lowest]` | Another player's stock levels. | `marketsearch.stock.others` |
| `/ms tpto <owner> <world> <x> <y> <z>` | Teleport in front of a shop (used by clickable results). | `marketsearch.tpto` |
| `/ms debug [1\|2]` | Toggle debug logging. | `marketsearch.debug` |
| `/ms pricereduce …` | Manage the offline price reduction routine (see below). | `marketsearch.pricereduce.admin` |

## Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `marketsearch.find` | Search the market (`/ms find/sell/buy`). | — |
| `marketsearch.stock` | Check your own stock levels. | — |
| `marketsearch.stock.others` | Check other players' stock levels. | — |
| `marketsearch.tpto` | Teleport to a shop. | — |
| `marketsearch.tptodelay.bypass` | Skip the teleport delay. | — |
| `marketsearch.debug` | Toggle debug logging. | — |
| `marketsearch.pricereduce.admin` | Manage the price reduction routine. | op |

---

## Price reduction for offline players

In long-lived, player-only economies, players eventually leave but their QuickShops stay frozen:
stock sits unsold and prices stay locked at old, non-competitive levels. As the world matures and
items become abundant, fair prices fall — but abandoned shops never follow, so the market looks
deep while much of that "stock" is dead inventory at stale high prices.

This feature gently self-corrects the market by **slowly reducing prices on shops owned by
long-offline players**, while leaving active players untouched.

### How it works

- A daily routine runs at a configurable time (default **02:00**, server local time).
- A player's shops become eligible once they have been offline for **`offline-threshold-days`**
  (default **60**).
- Each run drops each eligible shop's price by **`percent`** (default **2%**), with a minimum drop
  of **`min-drop`** (default **$0.01**), never going below the global floor **`min-price`**.
- **One drop per shop per day** — restarts and missed days never double-apply; a long outage results
  in a single drop on the next run.
- **Only SELLING shops in the market world are ever adjusted.** Buying shops, shops in other worlds,
  and shops with **no stock** are skipped.
- When a player returns, their countdown resets, reductions stop, and on their next join they get a
  generic (non-itemised) message that some prices were reduced while they were away.
- Players are **never charged** the QuickShop `fee-for-price-change` for reductions applied by this
  feature (it uses the shop API directly, not the player price command).

Performance: eligibility and all bookkeeping happen asynchronously against the database; the actual
QuickShop reads/writes run on the main thread, paced by a configurable **`shops-per-tick`** budget so
the work is spread across ticks.

### Storage & seeding (MySQL)

The feature requires **MySQL** (via a bundled, relocated HikariCP pool). It maintains three tables
(prefixed by `table-prefix`, default `ms_`):

- `ms_player_activity` — each player's last login (drives offline-age) and last reset time.
- `ms_shop_reduction` — per-shop state: last/new price, last reduction amount, location, item,
  reductions since the owner's last login, and the date last processed (the one-drop-per-day guard).
- `ms_price_audit` — append-only audit of every adjustment (and dry-run previews).

> **Seeding:** so existing offline players are recognised immediately, seed `ms_player_activity`
> from your proxy-wide player database (e.g. geSuit) once before the first live run. Players with no
> activity record are skipped (never reduced) until MS sees them log in.

### Configuration

```yaml
price-reduction:
  enabled: false              # master switch (opt-in per server)
  run-time: "02:00"           # daily run time, local server time (HH:mm)
  offline-threshold-days: 60  # reductions start after a player is offline this long
  percent: 0.02               # fraction to drop each run (0.02 = 2%)
  min-drop: 0.01              # minimum absolute drop applied per run
  min-price: 1.0              # global price floor; never reduce below this
  shops-per-tick: 20          # main-thread budget; shops processed per tick
  return-message: "&eSome of your shop prices were reduced due to your absence."
  return-message-delay-ticks: 30
  audit:
    database: true            # write each adjustment to the audit table
    log-file: true            # write each adjustment to price-reduction.log

database:                     # only used when price-reduction.enabled is true
  host: localhost
  port: 3306
  name: marketsearch
  user: marketsearch
  password: ""
  table-prefix: "ms_"
  pool-size: 4
```

If the database is unreachable at startup, the feature disables itself gracefully — search and the
rest of the plugin are unaffected.

### Commands

All require `marketsearch.pricereduce.admin` (default: op).

| Command | Description |
| --- | --- |
| `/ms pricereduce run` | Run the reduction immediately (respects the per-tick budget). |
| `/ms pricereduce dryrun [player]` | Preview what would change — computes and audits with `dry_run=1` but **never** changes prices. Optionally scope to one player. |
| `/ms pricereduce status <player>` | Show a player's days offline and reductions since last login. |

> Run `dryrun` first on an established market to review the impact before enabling live runs.
