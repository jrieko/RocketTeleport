package io.github.alshain01.rocketteleport;

import io.github.alshain01.flags.*;
import io.github.alshain01.flags.System;
import io.github.alshain01.flags.area.Area;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;


class RocketListener implements Listener {
    private final Set<Material> TRIGGER_TYPES = new HashSet<Material>(Arrays.asList(
            Material.WOOD_BUTTON,
            Material.WOOD_PLATE,
            Material.STONE_BUTTON,
            Material.STONE_PLATE));

    private final int retries;
    private boolean easterEggTimeout;
    private Set<Material> exclusions = new HashSet<Material>();
    private final RocketTeleport plugin;
    private Map<String, Object> flags = new HashMap<String, Object>();

    RocketListener(RocketTeleport plugin) {
        this.plugin = plugin;
        this.retries = plugin.getConfig().getInt("Retries");

        // Grab the list of materials to not teleport players to
        List<?> list = plugin.getConfig().getList("Exclusions");
        for(Object o : list) {
            this.exclusions.add(Material.valueOf((String)o));
        }

        if(Bukkit.getServer().getPluginManager().isPluginEnabled("Flags")) {
            plugin.getLogger().info("Enabling Flags Integration");
            Set<Flag> flagSet = Flags.getRegistrar().register(new ModuleYML(plugin, "flags.yml"), plugin.getName());
            for(Flag f : flagSet) {
                flags.put(f.getName(), f);
            }
        }
    }

    /*
     * Removes any partially created rocket from the queue if the player quits
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent e) {
        plugin.commandQueue.remove(e.getPlayer().getUniqueId());
        plugin.rocketQueue.remove(e.getPlayer().getUniqueId());
    }

    /*
     * Handles destruction of a rocket launch pad
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onBlockBreak(BlockBreakEvent e) {
        plugin.launchPad.removeRocket(e.getPlayer(), e.getBlock().getLocation());
   }

    /*
     * Handles the player creating a trigger or landing zone
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onSetDestination(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        UUID pID = player.getUniqueId();
        Location block = e.getClickedBlock().getLocation();

        if(e.getAction() != Action.LEFT_CLICK_BLOCK
                || !plugin.commandQueue.containsKey(pID)) { return; }
        PluginCommandType action = plugin.commandQueue.get(pID);

        switch(action) {
            case LAND:
                if(isFlagSet(flags.get("SetRocketLanding"), player, block)) { break; }
                plugin.launchPad.addRocket(plugin.rocketQueue.get(pID).addDestination(block));
                player.sendMessage(Message.ROCKET_CREATED.get());
                plugin.rocketQueue.remove(pID);
                break;
            case SOFT:
            case HARD:
            case RANDOM:
            case VILLAGER14:
                if(!TRIGGER_TYPES.contains(e.getClickedBlock().getType())) { return; }
                if(isFlagSet(flags.get("CreateRocket"), player, block)) { break; }
                plugin.rocketQueue.put(pID, plugin.rocketQueue.get(pID).setTrigger(block));
                player.sendMessage(Message.COMMAND_INSTRUCTION.get().replaceAll("\\{Command\\}", "/rt land"));
                break;
            default:
                break;
        }
        e.setCancelled(true);
        plugin.commandQueue.remove(pID);
    }

    private boolean isFlagSet(Object flag, Player player, Location location) {
        if(flag == null) { return false; }
        Flag f = (Flag)flag;
        Area area = System.getActive().getAreaAt(location);

        if(!player.hasPermission(f.getBypassPermission()) && !area.hasTrust(f, player)) {
            player.sendMessage(area.getMessage(f, player.getName()));
            return true;
        }
        return false;
    }

    /*
     * Handles a player using a fully configured rocket
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onActivateRocket(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if(e.getAction() != Action.RIGHT_CLICK_BLOCK
                && e.getAction() != Action.PHYSICAL
                || !TRIGGER_TYPES.contains(block.getType())) {
            return;
        }

        if(plugin.launchPad.hasRocket(block.getLocation())) {
            Player player = e.getPlayer();

            if(isFlagSet(flags.get("UseFlag"), player, e.getClickedBlock().getLocation())) { return; }

            e.setCancelled(false); // Undo anti-grief measures for rockets.
            Rocket rocket = plugin.launchPad.getRocket(block.getLocation());
            List<RocketLocation> possibleDestinations = rocket.getDestination();

            // Get a random location from the list of possible locations
            Location destination = possibleDestinations.get((int)(Math.random()*(possibleDestinations.size() - 1))).getLocation();

            if(rocket.getType().equals(RocketType.ELEMENT)) {
                Plugin plugin = Bukkit.getPluginManager().getPlugin("RocketTeleport");
                if(easterEggTimeout) {
                    new EasterEgg().run(player, destination);
                    easterEggTimeout = false;
                    new BukkitRunnable() {
                        public void run() {
                            easterEggTimeout = true;
                        }
                    }.runTaskLater(plugin, plugin.getConfig().getInt("EasterEggTimeout"));
                } else {
                    double timeout = plugin.getConfig().getInt("EasterEggTimeout") / 60 / 20;
                    player.sendMessage(ChatColor.RED + "This can only be used once every " + timeout + " minutes server wide.");
                }
                return;
            }

            if(rocket.getType().equals(RocketType.RANDOM)) {
                // Get a random radius around the location
                destination = getRandomLocation(destination, rocket.getRadius());
                if(destination == null) {
                    player.sendMessage(ChatColor.RED + "Failed to locate suitable destination after " + retries +" attempts.");
                    return;
                }
            }

            if(rocket.getType().equals(RocketType.HARD)) {
                destination = destination.add(0D, 75D, 0D);
            }

            new Launch(player, destination).liftOff();
        }
    }

    private Location getRandomLocation(Location landingArea, double radius) {
        int count = 0;
        Block landing;
        do {
            double x = (landingArea.getX() - radius) + Math.random() * (radius*2);
            double z = (landingArea.getZ() - radius) + Math.random() * (radius*2);
            Location loc = new Location(landingArea.getWorld(), x, 0D, z);
            landing = loc.getWorld().getHighestBlockAt(loc);
            count ++;
        } while (invalidType(landing.getType()) && count <= retries);
        if(count > retries) { return null; }
        return landing.getLocation().add(0, 1, 0);
    }

    private boolean invalidType(Material type) {
        for(Material m : exclusions) {
            if(type.equals(m)) { return true; }
        }
        return false;
    }
}
