package fr.donjon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Donjon : /donjon téléporte dans un monde superplat, au cœur d'une salle gothique démesurée
 * qui abrite trois portails animés : gris, rouge et doré.
 */
public final class DonjonPlugin extends JavaPlugin implements Listener, TabExecutor {
    static final int ROOM_VERSION = 1;
    static final int PORTAL_Z = -22;        // plan des membranes
    static final int PORTAL_BASE_Y = 2;     // premier bloc de la membrane
    static final int PORTAL_TOP_Y = 9;      // dernier bloc de la membrane

    private World world;
    private boolean building;
    private RoomBuilder builder;
    private final Map<UUID, Location> returns = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final List<Portal> portals = new ArrayList<>();
    private final Map<String, Particle> pcache = new HashMap<>();
    private final Random rnd = new Random();
    private long tick;

    // ------------------------------------------------------------------ Cycle de vie

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("donjon");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        initPortals();
        ensureWorld();
        getServer().getScheduler().runTaskTimer(this, this::animate, 20L, 2L);
        getServer().getScheduler().runTaskTimer(this, this::lockSky, 100L, 200L);
        getLogger().info("Donjon activé.");
    }

    private static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s.replace('§', '&'));
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(c(getConfig().getString("prefix", "&4&l[&c&lDonjon&4&l] &7") + text));
    }

    private File stateFile() { return new File(getDataFolder(), "state.yml"); }

    private boolean isBuilt() {
        return stateFile().exists() && YamlConfiguration.loadConfiguration(stateFile()).getInt("room-version", 0) >= ROOM_VERSION;
    }

    private void saveBuilt() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("room-version", ROOM_VERSION);
        try {
            getDataFolder().mkdirs();
            y.save(stateFile());
        } catch (IOException e) {
            getLogger().warning("state.yml : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Monde

    /** Générateur superplat : bedrock puis deepslate jusqu'à y=-1 (surface à y=-1, pieds à y=0). */
    static final class FlatGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
            int min = info.getMinHeight();
            data.setRegion(0, min, 0, 16, min + 1, 16, Material.BEDROCK);
            data.setRegion(0, min + 1, 0, 16, 0, 16, Material.DEEPSLATE);
        }

        @Override
        public boolean shouldGenerateNoise() { return true; }

        @Override
        public Location getFixedSpawnLocation(World w, Random random) { return new Location(w, 0.5, 0, 12.5); }
    }

    private World ensureWorld() {
        if (world != null) return world;
        String name = getConfig().getString("world", "donjon");
        World w = Bukkit.getWorld(name);
        if (w == null) {
            WorldCreator wc = new WorldCreator(name);
            wc.generator(new FlatGenerator());
            wc.environment(World.Environment.NORMAL);
            wc.generateStructures(false);
            w = Bukkit.createWorld(wc);
        }
        if (w != null) {
            world = w;
            double size = getConfig().getDouble("border-size", 160);
            w.getWorldBorder().setCenter(0, 0);
            w.getWorldBorder().setSize(size);
            w.setSpawnLocation(0, 0, 12);
            lockSky();
        } else {
            getLogger().severe("Impossible de créer le monde du donjon.");
        }
        return world;
    }

    private void lockSky() {
        if (world == null || !getConfig().getBoolean("lock-night", true)) return;
        world.setTime(18000);
        world.setStorm(false);
        world.setThundering(false);
    }

    private Location spawn() { return new Location(world, 0.5, 0, 12.5, 180f, 0f); }

    // ------------------------------------------------------------------ Commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "go" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "go", "entrer" -> {
                if (!(s instanceof Player p)) {
                    msg(s, "&cCommande réservée aux joueurs.");
                    return true;
                }
                if (!p.hasPermission("donjon.use")) {
                    msg(s, "&cVous n'avez pas la permission.");
                    return true;
                }
                enter(p);
            }
            case "quitter", "leave", "sortir" -> {
                if (!(s instanceof Player p)) return true;
                leave(p);
            }
            case "rebuild" -> {
                if (!s.hasPermission("donjon.admin")) {
                    msg(s, "&cVous n'avez pas la permission.");
                    return true;
                }
                startBuild(s, true, null);
            }
            case "info" -> {
                msg(s, "&7Monde : &f" + getConfig().getString("world", "donjon") + " &7| Salle construite : "
                        + (isBuilt() ? "&aoui" : "&cnon") + (building ? " &e(construction en cours)" : ""));
                msg(s, "&7Portails : &7Cendres &8| &cÉcarlate &8| &6Doré");
            }
            default -> msg(s, "&cUsage : /donjon [quitter|rebuild|info]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("quitter", "info", "rebuild")) {
                if (x.equals("rebuild") && !s.hasPermission("donjon.admin")) continue;
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
            }
        }
        return out;
    }

    private void enter(Player p) {
        if (ensureWorld() == null) {
            msg(p, "&cMonde du donjon indisponible (voir la console).");
            return;
        }
        if (building) {
            msg(p, "&eLe donjon est en construction, patientez quelques secondes...");
            return;
        }
        if (!isBuilt()) {
            startBuild(p, false, () -> teleportIn(p));
            return;
        }
        teleportIn(p);
    }

    private void teleportIn(Player p) {
        if (!p.isOnline()) return;
        if (!p.getWorld().equals(world)) returns.put(p.getUniqueId(), p.getLocation());
        p.teleport(spawn());
        p.showTitle(Title.title(c("&4&l⛧ LE DONJON ⛧"), c("&7Trois portails... un seul destin."),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(800))));
        Location l = p.getLocation().add(0, 1, 0);
        sound(l, "entity.wither.spawn", 0.5f, 0.6f);
        sound(l, "block.portal.trigger", 0.7f, 0.5f);
        particle(l, "soul_fire_flame", 40, 0.6, 0.9, 0.6, 0.05);
        particle(l, "reverse_portal", 60, 0.5, 0.9, 0.5, 0.3);
    }

    private void leave(Player p) {
        Location r = returns.remove(p.getUniqueId());
        if (r == null || r.getWorld() == null) r = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleport(r);
        msg(p, "&7Vous quittez le donjon.");
    }

    // ------------------------------------------------------------------ Construction

    private void startBuild(CommandSender who, boolean clear, Runnable done) {
        if (building) {
            msg(who, "&cUne construction est déjà en cours.");
            return;
        }
        if (ensureWorld() == null) {
            msg(who, "&cMonde du donjon indisponible.");
            return;
        }
        building = true;
        builder = new RoomBuilder(world, clear);
        msg(who, "&7Construction de la salle des portails...");
        new BukkitRunnable() {
            int lastTen = -1;

            @Override
            public void run() {
                try {
                    boolean fin = builder.run(25_000_000L);
                    int pr = builder.progress();
                    if (who instanceof Player p && pr / 10 != lastTen) {
                        lastTen = pr / 10;
                        p.sendActionBar(c("&4⛧ &cConstruction du donjon... &f" + pr + "%"));
                    }
                    if (fin) {
                        cancel();
                        building = false;
                        saveBuilt();
                        msg(who, "&aSalle construite.");
                        if (done != null) done.run();
                    }
                } catch (RuntimeException ex) {
                    cancel();
                    building = false;
                    getLogger().log(java.util.logging.Level.SEVERE, "Construction du donjon interrompue", ex);
                    msg(who, "&cErreur pendant la construction (voir la console).");
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // ------------------------------------------------------------------ Effets utilitaires

    private Particle particleOf(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (pcache.containsKey(k)) return pcache.get(k);
        Particle p = null;
        try {
            p = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(k));
        } catch (Exception ignored) {
            // clé invalide
        }
        pcache.put(k, p);
        return p;
    }

    private void particle(Location l, String key, int count, double dx, double dy, double dz, double speed) {
        if (l.getWorld() == null) return;
        Particle p = particleOf(key);
        if (p == null || p.getDataType() != Void.class) return;
        l.getWorld().spawnParticle(p, l, count, dx, dy, dz, speed);
    }

    private void dust(Location l, Color color, float size, int count, double spread) {
        if (l.getWorld() == null) return;
        l.getWorld().spawnParticle(Particle.DUST, l, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    private void sound(Location l, String key, float vol, float pitch) {
        if (l.getWorld() != null) l.getWorld().playSound(l, key, vol, pitch);
    }

    private double rr(double a, double b) { return a + rnd.nextDouble() * (b - a); }

    // ------------------------------------------------------------------ Portails animés

    /** Un portail : cadre et membrane de verre teintée repeinte côté client en vagues de couleurs. */
    static final class Portal {
        final String key;
        final int cx;
        final BlockData[] palette;
        final Color c1;
        final Color c2;
        final String p1;
        final String p2;
        final String p3;
        final String ambient;
        final List<int[]> cells = new ArrayList<>();
        final int[] last;
        final Set<UUID> viewers = new HashSet<>();
        String name = "";
        String message = "";

        Portal(String key, int cx, Material[] pal, Color c1, Color c2, String p1, String p2, String p3, String ambient) {
            this.key = key;
            this.cx = cx;
            this.palette = new BlockData[pal.length];
            for (int i = 0; i < pal.length; i++) palette[i] = pal[i].createBlockData();
            this.c1 = c1;
            this.c2 = c2;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.ambient = ambient;
            for (int dx = -2; dx <= 2; dx++) {
                for (int y = PORTAL_BASE_Y; y <= PORTAL_TOP_Y; y++) {
                    if (Math.abs(dx) == 2 && y == PORTAL_TOP_Y) continue;
                    cells.add(new int[]{cx + dx, y});
                }
            }
            this.last = new int[cells.size()];
            java.util.Arrays.fill(last, -1);
        }
    }

    private void initPortals() {
        portals.clear();
        Portal gray = new Portal("gray", -10,
                new Material[]{Material.GRAY_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.BLACK_STAINED_GLASS},
                Color.fromRGB(170, 170, 180), Color.fromRGB(60, 60, 70), "ash", "smoke", "cloud", "block.sculk_sensor.clicking");
        Portal red = new Portal("red", 0,
                new Material[]{Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.RED_STAINED_GLASS, Material.BROWN_STAINED_GLASS},
                Color.fromRGB(230, 25, 20), Color.fromRGB(255, 120, 20), "flame", "lava", "smoke", "block.fire.ambient");
        Portal gold = new Portal("gold", 10,
                new Material[]{Material.YELLOW_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS},
                Color.fromRGB(255, 205, 40), Color.fromRGB(255, 245, 170), "end_rod", "electric_spark", "totem_of_undying", "block.beacon.ambient");
        for (Portal p : List.of(gray, red, gold)) {
            p.name = getConfig().getString("portals." + p.key + ".name", p.key);
            p.message = getConfig().getString("portals." + p.key + ".message", "");
            portals.add(p);
        }
    }

    private void animate() {
        if (world == null || building) return;
        tick += 2;
        List<Player> ps = world.getPlayers();
        if (ps.isEmpty()) return;
        for (Portal po : portals) animatePortal(po, ps);
        if (tick % 10 == 0) ambience(ps);
        for (Player p : ps) contact(p);
    }

    private void animatePortal(Portal po, List<Player> ps) {
        final double zc = PORTAL_Z + 0.5;
        final double xc = po.cx + 0.5;
        Location mid = new Location(world, xc, 6.0, zc);
        List<Player> near = new ArrayList<>();
        for (Player p : ps) if (p.getLocation().distanceSquared(mid) <= 45 * 45) near.add(p);
        po.viewers.removeIf(id -> {
            Player p = Bukkit.getPlayer(id);
            return p == null || !near.contains(p);
        });
        if (near.isEmpty()) return;

        // 1) membrane : vagues de verre teinté (client uniquement)
        if (tick % 4 == 0) {
            int n = po.palette.length;
            double t = tick;
            boolean[] changed = new boolean[po.cells.size()];
            for (int i = 0; i < po.cells.size(); i++) {
                int[] cell = po.cells.get(i);
                double dx = cell[0] - po.cx, dy = cell[1] - 5.5;
                double v = Math.sin(Math.hypot(dx, dy * 0.7) * 1.3 - t * 0.25) + 0.6 * Math.sin(dx * 0.8 + t * 0.12)
                        + 0.4 * Math.sin(cell[1] * 0.6 - t * 0.18);
                int idx = (int) Math.floor((v + 2.0) / 4.0 * n);
                idx = Math.max(0, Math.min(n - 1, idx));
                if (idx != po.last[i]) {
                    po.last[i] = idx;
                    changed[i] = true;
                }
            }
            for (Player p : near) {
                boolean full = po.viewers.add(p.getUniqueId());
                for (int i = 0; i < po.cells.size(); i++) {
                    if (!full && !changed[i]) continue;
                    int[] cell = po.cells.get(i);
                    p.sendBlockChange(new Location(world, cell[0], cell[1], PORTAL_Z), po.palette[po.last[i]]);
                }
            }
        }

        // 2) spirale d'énergie aspirée vers le cœur du portail
        double t = tick * 0.04;
        for (int i = 0; i < 4; i++) {
            double f = (t * 0.6 + i / 4.0) % 1.0;
            double a = t * 3 + i * Math.PI / 2 + f * 5;
            double k = 1 - f;
            Location pt = new Location(world, xc + Math.cos(a) * 2.3 * k, 6.0 + Math.sin(a) * 3.7 * k, zc);
            dust(pt, i % 2 == 0 ? po.c1 : po.c2, 1.4f, 1, 0.05);
            particle(pt, po.p1, 1, 0.05, 0.05, 0.05, 0.01);
        }
        // 3) anneau pulsant autour de l'ouverture
        if (tick % 6 == 0) {
            double pulse = 0.5 + 0.5 * Math.sin(tick * 0.15);
            for (int i = 0; i < 28; i++) {
                double a = i * 2 * Math.PI / 28;
                Location pt = new Location(world, xc + Math.cos(a) * (2.7 + pulse * 0.2), 6.0 + Math.sin(a) * (4.4 + pulse * 0.2), zc);
                particle(pt, po.p2, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }
        // 4) étincelles montantes et brume au pied
        particle(new Location(world, xc + rr(-2.5, 2.5), 2.2, zc + rr(-1, 1.5)), po.p3, 2, 0.2, 0.4, 0.2, 0.02);
        if (rnd.nextInt(10) == 0) particle(new Location(world, xc + rr(-2, 2), rr(3, 9), zc), "electric_spark", 6, 0.4, 0.4, 0.1, 0.2);
        // 5) bourdonnement
        if (tick % 80 == 0) sound(mid, po.ambient, 1.2f, po.key.equals("gold") ? 1.4f : 0.6f);
    }

    /** Ambiance de la salle : cendres, âmes, cercle rituel tournant et orages lointains. */
    private void ambience(List<Player> ps) {
        for (Player p : ps) {
            Location l = p.getLocation();
            if (Math.abs(l.getX()) > 18 || l.getZ() < -26 || l.getZ() > 18) continue;
            particle(l.clone().add(rr(-9, 9), rr(4, 11), rr(-9, 9)), "ash", 5, 0.5, 0.5, 0.5, 0.01);
            particle(l.clone().add(rr(-6, 6), rr(0.2, 3), rr(-6, 6)), "soul", 1, 0.2, 0.3, 0.2, 0.01);
            if (rnd.nextInt(3) == 0) particle(l.clone().add(rr(-8, 8), rr(1, 6), rr(-8, 8)), "flame", 1, 0.1, 0.1, 0.1, 0.0);
        }
        double base = tick * 0.03;
        for (int i = 0; i < 24; i++) {
            double a = base + i * 2 * Math.PI / 24;
            Location pt = new Location(world, 0.5 + Math.cos(a) * 6.5, 0.15, -1.5 + Math.sin(a) * 6.5);
            dust(pt, i % 2 == 0 ? Color.fromRGB(255, 190, 40) : Color.fromRGB(200, 15, 15), 1.3f, 1, 0.03);
            if (i % 6 == 0) particle(pt.clone().add(0, 0.3, 0), "soul_fire_flame", 1, 0.05, 0.1, 0.05, 0.01);
        }
        Location centre = new Location(world, 0.5, 0.2, -1.5);
        particle(centre.clone().add(0, 0.2 + (tick % 40) * 0.05, 0), "enchant", 6, 0.8, 0.1, 0.8, 0.2);
        if (tick % 600 == 0) {
            Location far = new Location(world, rr(-14, 14), 18, rr(-20, 12));
            world.strikeLightningEffect(far);
            sound(far, "entity.lightning_bolt.thunder", 0.8f, 0.7f);
        }
    }

    /** Un joueur s'approche de la membrane : message, effets et recul. */
    private void contact(Player p) {
        Location l = p.getLocation();
        if (l.getZ() < PORTAL_Z + 0.9 || l.getZ() > PORTAL_Z + 2.3 || l.getY() < PORTAL_BASE_Y - 0.5 || l.getY() > PORTAL_BASE_Y + 3) return;
        for (Portal po : portals) {
            if (Math.abs(l.getX() - (po.cx + 0.5)) > 2.6) continue;
            long now = System.currentTimeMillis();
            Long last = cooldown.get(p.getUniqueId());
            if (last != null && now - last < 3000) return;
            cooldown.put(p.getUniqueId(), now);
            p.showTitle(Title.title(c(po.name), c("&8Portail scellé"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(500))));
            p.sendMessage(c(po.message));
            Location at = new Location(world, po.cx + 0.5, 6.0, PORTAL_Z + 0.5);
            particle(at, po.p1, 60, 1.8, 3, 0.3, 0.2);
            dust(at, po.c1, 2f, 40, 1.8);
            sound(at, "block.end_portal_frame.fill", 1f, 0.6f);
            sound(at, po.ambient, 1.5f, 0.5f);
            p.setVelocity(new Vector(0, 0.35, 1.1));
            return;
        }
    }

    // ------------------------------------------------------------------ Protection du monde

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (world != null && e.getBlock().getWorld().equals(world) && !e.getPlayer().hasPermission("donjon.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (world != null && e.getBlock().getWorld().equals(world) && !e.getPlayer().hasPermission("donjon.admin")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        if (world == null || !e.getLocation().getWorld().equals(world) || getConfig().getBoolean("allow-natural-mobs", false)) return;
        switch (e.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, DISPENSE_EGG -> { }
            default -> e.setCancelled(true);
        }
    }

    // ================================================================== Construction de la salle

    /**
     * Construit la salle par petites tranches (budget de temps par tick) : nef voûtée en berceau, murs de deepslate à pilastres,
     * fenêtres d'obsidienne pleureuse, plancher en damier avec cercle rituel, estrade de trois marches et trois portails.
     * Repère : sol y=-1 (pieds y=0) ; intérieur x∈[-16,16], z∈[-24,16] ; portails sur l'estrade au nord.
     */
    static final class RoomBuilder {
        static final int[] RIBS = {-21, -15, -9, -3, 3, 9, 15};

        private final World w;
        private final Deque<Runnable> steps = new ArrayDeque<>();
        private final int total;

        RoomBuilder(World w, boolean clear) {
            this.w = w;
            steps.add(this::loadChunks);
            if (clear) {
                for (int z = -28; z <= 20; z++) {
                    final int zz = z;
                    steps.add(() -> clearSlice(zz));
                }
            }
            for (int z = -26; z <= 18; z++) {
                final int zz = z;
                steps.add(() -> shellSlice(zz));
            }
            steps.add(this::pilasters);
            steps.add(this::windows);
            steps.add(this::lanterns);
            steps.add(this::stalactites);
            steps.add(this::braziers);
            steps.add(this::obelisks);
            steps.add(() -> frame(-10, new Mat(Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.IRON_BLOCK, Material.GRAY_STAINED_GLASS, Material.CHISELED_STONE_BRICKS)));
            steps.add(() -> frame(0, new Mat(Material.NETHER_BRICKS, Material.RED_NETHER_BRICKS, Material.SHROOMLIGHT, Material.RED_STAINED_GLASS, Material.CHISELED_NETHER_BRICKS)));
            steps.add(() -> frame(10, new Mat(Material.GOLD_BLOCK, Material.GILDED_BLACKSTONE, Material.GLOWSTONE, Material.YELLOW_STAINED_GLASS, Material.RAW_GOLD_BLOCK)));
            steps.add(this::platformBraziers);
            this.total = steps.size();
        }

        record Mat(Material pillar, Material trim, Material accent, Material glass, Material key) {}

        boolean run(long budgetNanos) {
            long end = System.nanoTime() + budgetNanos;
            while (!steps.isEmpty() && System.nanoTime() < end) steps.poll().run();
            return steps.isEmpty();
        }

        int progress() { return total == 0 ? 100 : (int) (100.0 * (total - steps.size()) / total); }

        private void set(int x, int y, int z, Material m) { w.getBlockAt(x, y, z).setType(m, false); }

        private static int hash(int x, int y, int z) {
            return Math.floorMod(x * 73856093 ^ y * 19349663 ^ z * 83492791, 100);
        }

        private void loadChunks() {
            for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) w.getChunkAt(cx, cz);
        }

        private void clearSlice(int z) {
            for (int x = -20; x <= 20; x++) for (int y = -1; y <= 30; y++) if (y >= 0) set(x, y, z, Material.AIR);
        }

        private static int yin(int x) {
            int ax = Math.min(Math.abs(x), 16);
            return 14 + (int) Math.round(Math.sqrt(256 - ax * ax) * 0.5);
        }

        private static boolean isRib(int z) {
            for (int r : RIBS) if (r == z) return true;
            return false;
        }

        private Material floor(int x, int z) {
            int ax = Math.abs(x);
            if (ax == 16) return Material.CHISELED_POLISHED_BLACKSTONE;
            double d = Math.hypot(x, z + 2);
            if (d <= 7.0) {
                if (d >= 6.0) return Material.CRYING_OBSIDIAN;
                if (d <= 1.6) return d < 0.8 ? Material.GILDED_BLACKSTONE : Material.CRYING_OBSIDIAN;
                if (d >= 3.0 && d < 4.0) return Material.GILDED_BLACKSTONE;
                double f = ((Math.atan2(z + 2, x) + Math.PI) / (Math.PI / 4)) % 1.0;
                if (f < 0.09 || f > 0.91) return Material.RED_NETHER_BRICKS;
                return Material.POLISHED_DEEPSLATE;
            }
            if (ax <= 2) {
                if (ax <= 1) return Material.RED_NETHER_BRICKS;
                return (z & 1) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
            }
            int h = hash(x, -1, z);
            if (h >= 97) return Material.GILDED_BLACKSTONE;
            if (h >= 92) return Material.CRACKED_DEEPSLATE_TILES;
            return (((x >> 1) + (z >> 1)) & 1) == 0 ? Material.POLISHED_BLACKSTONE : Material.DEEPSLATE_TILES;
        }

        private Material platform(int x, int y, int z) {
            if (y == 0) return Material.POLISHED_BLACKSTONE_BRICKS;
            if (z == -16) return ((x + z) & 1) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
            return (((x >> 1) + (z >> 1)) & 1) == 0 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_TILES;
        }

        private Material wall(int x, int y, int z) {
            if (y <= 1) return Material.POLISHED_BLACKSTONE_BRICKS;
            if (y == 8) return ((x + z) & 1) == 0 ? Material.RED_NETHER_BRICKS : Material.DEEPSLATE_BRICKS;
            return hash(x, y, z) < 12 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS;
        }

        private Material ceiling(int x, int y, int z, int yin) {
            if (y == yin && isRib(z)) return x == 0 ? Material.CRYING_OBSIDIAN : Material.CHISELED_POLISHED_BLACKSTONE;
            if (y == yin && hash(x, y, z) < 6) return Material.CRACKED_DEEPSLATE_TILES;
            return Material.DEEPSLATE_TILES;
        }

        private Material cell(int x, int y, int z) {
            int ax = Math.abs(x);
            boolean inside = ax <= 16 && z >= -24 && z <= 16;
            int yin = yin(x);
            if (inside) {
                if (y == -1) return floor(x, z);
                if (y < 0) return null;
                if (z <= -16 && y <= 1) return platform(x, y, z);
                if (z == -15 && y == 0) return Material.POLISHED_BLACKSTONE_BRICKS;
                if (y >= yin && y <= yin + 2) return ceiling(x, y, z, yin);
                return null;
            }
            if (y > yin + 2) return null;
            if (y == -1) return Material.DEEPSLATE_BRICKS;
            return wall(x, y, z);
        }

        private void shellSlice(int z) {
            for (int x = -18; x <= 18; x++) {
                for (int y = -1; y <= 26; y++) {
                    Material m = cell(x, y, z);
                    if (m != null) set(x, y, z, m);
                }
            }
        }

        /** Pilastres engagés le long des deux murs, à veines d'obsidienne pleureuse. */
        private void pilasters() {
            for (int side = -1; side <= 1; side += 2) {
                for (int zc : RIBS) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int y = 0; y <= 13; y++) {
                            Material m;
                            if (y == 0 || y == 13) m = Material.CHISELED_POLISHED_BLACKSTONE;
                            else if (dz == 0 && y % 4 == 2) m = Material.CRYING_OBSIDIAN;
                            else m = Material.POLISHED_BLACKSTONE_BRICKS;
                            set(side * 16, y, zc + dz, m);
                        }
                    }
                }
            }
        }

        /** Hautes fenêtres lumineuses entre les pilastres. */
        private void windows() {
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k < RIBS.length - 1; k++) {
                    int zg = RIBS[k] + 3;
                    for (int y = 4; y <= 10; y++) set(side * 17, y, zg, Material.CRYING_OBSIDIAN);
                }
            }
        }

        private void lanterns() {
            Lantern hanging = (Lantern) Material.SOUL_LANTERN.createBlockData();
            hanging.setHanging(true);
            for (int k = 0; k < RIBS.length - 1; k++) {
                int z = RIBS[k] + 3;
                for (int x : new int[]{-8, 0, 8}) w.getBlockAt(x, yin(x) - 1, z).setBlockData(hanging, false);
            }
        }

        /** Stalactites de pierre qui pendent de la voûte. */
        private void stalactites() {
            PointedDripstone d = (PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
            d.setVerticalDirection(BlockFace.DOWN);
            d.setThickness(PointedDripstone.Thickness.TIP);
            for (int x = -14; x <= 14; x++) {
                for (int z = -22; z <= 14; z++) {
                    if (isRib(z) || hash(x, 3, z) >= 3) continue;
                    if ((x == -8 || x == 0 || x == 8) && ((z - 3 + 24) % 6 == 0)) continue;
                    w.getBlockAt(x, yin(x) - 1, z).setBlockData(d, false);
                }
            }
        }

        private void braziers() {
            for (int side = -1; side <= 1; side += 2) {
                for (int z : new int[]{14, 9, -9, -13}) {
                    int x = side * 5;
                    set(x, 0, z, Material.CHISELED_POLISHED_BLACKSTONE);
                    set(x, 1, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    set(x, 2, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    set(x, 3, z, Material.SOUL_CAMPFIRE);
                }
            }
        }

        /** Deux obélisques qui encadrent l'estrade. */
        private void obelisks() {
            for (int side = -1; side <= 1; side += 2) {
                int ox = side * 13, oz = -10;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        set(ox + dx, 0, oz + dz, Material.CHISELED_POLISHED_BLACKSTONE);
                        set(ox + dx, 1, oz + dz, Material.POLISHED_BLACKSTONE_BRICKS);
                        set(ox + dx, 11, oz + dz, Material.CHISELED_POLISHED_BLACKSTONE);
                    }
                }
                for (int y = 2; y <= 10; y++) {
                    Material m = y % 3 == 0 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE_BRICKS;
                    set(ox, y, oz, Material.POLISHED_DEEPSLATE);
                    set(ox + 1, y, oz, m);
                    set(ox - 1, y, oz, m);
                    set(ox, y, oz + 1, m);
                    set(ox, y, oz - 1, m);
                }
                set(ox, 12, oz, Material.SOUL_CAMPFIRE);
            }
        }

        /** Cadre d'un portail : plinthe, pilastres épais, linteau, clé de voûte, lanternes, et membrane de verre teinté. */
        private void frame(int cx, Mat pal) {
            int z0 = DonjonPlugin.PORTAL_Z;
            for (int dz = -1; dz <= 1; dz++) {
                int z = z0 + dz;
                for (int dx = -5; dx <= 5; dx++) {
                    int adx = Math.abs(dx);
                    int x = cx + dx;
                    if (adx == 5) {
                        set(x, 2, z, pal.trim());
                        set(x, 3, z, pal.pillar());
                    } else if (adx >= 3) {
                        for (int y = 2; y <= 11; y++) set(x, y, z, (y % 3 == 0) ? pal.trim() : pal.pillar());
                        set(x, 12, z, pal.trim());
                    } else {
                        set(x, 10, z, pal.trim());
                        set(x, 11, z, pal.pillar());
                        if (adx == 2) set(x, 9, z, pal.trim());
                    }
                }
                set(cx, 10, z, pal.key());
                set(cx, 11, z, pal.accent());
            }
            set(cx, 12, z0, Material.POINTED_DRIPSTONE);
            set(cx - 4, 13, z0, Material.SOUL_LANTERN);
            set(cx + 4, 13, z0, Material.SOUL_LANTERN);
            set(cx - 3, 12, z0 + 1, pal.accent());
            set(cx + 3, 12, z0 + 1, pal.accent());
            for (int dx = -2; dx <= 2; dx++) {
                for (int y = DonjonPlugin.PORTAL_BASE_Y; y <= DonjonPlugin.PORTAL_TOP_Y; y++) {
                    if (Math.abs(dx) == 2 && y == DonjonPlugin.PORTAL_TOP_Y) continue;
                    set(cx + dx, y, z0, pal.glass());
                }
            }
            for (int dx = -2; dx <= 2; dx++) set(cx + dx, 2, z0 + 1, pal.trim());
            // les cellules de plinthe devant la membrane restent franchissables : on retire le bloc devant
            for (int dx = -2; dx <= 2; dx++) set(cx + dx, 2, z0 + 1, Material.AIR);
        }

        private void platformBraziers() {
            for (int x : new int[]{-15, -5, 5, 15}) {
                set(x, 2, -19, Material.CHISELED_POLISHED_BLACKSTONE);
                set(x, 3, -19, Material.POLISHED_BLACKSTONE_BRICKS);
                set(x, 4, -19, Material.SOUL_CAMPFIRE);
            }
            for (int x : new int[]{-15, -5, 5, 15}) set(x, 2, -23, Material.WITHER_SKELETON_SKULL);
        }
    }
}
