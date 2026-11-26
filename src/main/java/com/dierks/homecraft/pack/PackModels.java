package com.dierks.homecraft.pack;

import com.dierks.homecraft.util.Bedrock;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * HCM's models in CraftBridge's resource pack (0.41.0; CraftBridge 0.19.0's pack sources API).
 *
 * <p><b>HCM's source.</b> On enable, when CraftBridge is running, HCM writes its placeholder models
 * ({@link ClubModels#placeholderFiles}) into {@code plugins/HomeCraftManagement/pack/} and registers that folder as the
 * pack source {@value #SOURCE} ({@code CraftBridgePack.registerSource(plugin, "hcm", folder)}). CraftBridge is never a
 * compile dependency: the API is reached by name, and anything missing or different just leaves the models off (the
 * rooms show their vanilla stand-ins).
 *
 * <p><b>The owner's models win, without a conflict.</b> The owner's Blockbench models go in as a drop-in source,
 * a folder or zip in {@code plugins/CraftBridge/pack/sources/}. For every model name a drop-in has an item definition
 * for ({@code assets/hcm/items/<name>.json}), HCM takes its own placeholder for that name out of its folder, so the
 * pack has exactly one file at each path and nothing depends on {@code resource-pack.priority}. The drop-ins are looked
 * at on enable, on {@code /hcm reload} and once a minute; when what HCM leaves out changes, it asks CraftBridge for a
 * rebuild (the pack is re-sent to everyone online).
 *
 * <p><b>0.58.0, the playing cards.</b> The folder also holds the card games' 52 faces and back ({@link CardArt}): an
 * item definition, a flat model and a PNG picture each, written as bytes like everything else here (a file is only
 * rewritten when its bytes differ, so an unchanged picture never makes CraftBridge rebuild). The same rule covers them:
 * a drop-in with {@code assets/hcm/items/card_<id>.json} replaces that card, and HCM leaves out all three of its files
 * for it; a drop-in with only a picture at {@code assets/hcm/textures/item/cards/<id>.png} repaints it, and HCM leaves
 * out just its own picture. Who sees the art is {@link #drawsFor}.
 *
 * <p>All on the main thread; a few hundred small files, the pictures painted once.
 */
public final class PackModels {

    /** HCM's pack source id (and the name in CraftBridge's {@code resource-pack.priority}). */
    public static final String SOURCE = "hcm";
    /** CraftBridge's plugin name and its API's class. */
    static final String CRAFTBRIDGE = "CraftBridge";
    static final String API = "com.dierks.craftbridge.api.CraftBridgePack";

    /** {@code assets/hcm/items/<name>.json} anywhere in a source (a zip may have one extra folder). */
    private static final Pattern ITEM = Pattern.compile("(?:^|.*/)assets/" + ClubModels.NS + "/items/([a-z0-9_./-]+)\\.json$");

    private final Plugin plugin;
    private final Logger log;
    private final Path folder;
    private Object pack;
    private Method rebuild;
    private Set<String> owners = Set.of();
    /** 0.58.0: the cards whose picture the owner's drop-in repaints (HCM leaves its own picture out). */
    private Set<String> repainted = Set.of();
    /** 0.41.1: each broken drop-in model is said once (it is looked at once a minute). */
    private final Set<String> warned = new java.util.HashSet<>();
    private boolean registered;

    public PackModels(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.folder = plugin.getDataFolder().toPath().resolve("pack");
    }

    // ---- pure ---------------------------------------------------------------------------------------

    /** Every model name HCM puts in the pack: the Clubhouse's models and (0.58.0) the cards. */
    public static Set<String> modelNames() {
        Set<String> names = new TreeSet<>();
        for (ClubModels.Model m : ClubModels.all()) {
            names.add(m.name());
        }
        names.addAll(CardArt.names());
        return names;
    }

    /**
     * 0.58.0: HCM's files in the pack, by path, as bytes: the Clubhouse's placeholders and the cards', but none for a
     * model name in {@code owners} (the owner's drop-in has it) and no picture for a card in {@code repainted}.
     */
    public static Map<String, byte[]> files(Set<String> owners, Set<String> repainted) {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> f : ClubModels.placeholderFiles(owners).entrySet()) {
            out.put(f.getKey(), f.getValue().getBytes(StandardCharsets.UTF_8));
        }
        out.putAll(CardArt.files(owners, repainted));
        return out;
    }

    /**
     * 0.58.0, whether a player is drawn HCM's art (pure): HCM's models are in CraftBridge's pack, their last pack status
     * is "loaded", and they are on Java. Bedrock players (through Geyser) never get a Java pack, whatever their status
     * says; declined, failed, not answered yet or no CraftBridge: the vanilla item.
     */
    public static boolean drawsFor(boolean packOn, boolean bedrock, PlayerResourcePackStatusEvent.Status status) {
        return packOn && !bedrock && status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    /** {@link #drawsFor(boolean, boolean, PlayerResourcePackStatusEvent.Status)} for a player online now. */
    public boolean drawsFor(Player p) {
        if (p == null || !registered) {
            return false;
        }
        try {
            return drawsFor(true, Bedrock.is(p), p.getResourcePackStatus());
        } catch (RuntimeException | LinkageError e) {
            return false; // a server without the status: the vanilla item, never a missing model
        }
    }

    /**
     * Which of HCM's model names the paths of a drop-in source give an item definition for (the owner's models).
     * Only names HCM uses count; a path under {@code placeholder/} never does.
     */
    public static Set<String> providedBy(Collection<String> paths) {
        Set<String> names = modelNames();
        Set<String> out = new TreeSet<>();
        for (String p : paths) {
            Matcher m = ITEM.matcher(p.replace('\\', '/'));
            if (m.matches() && names.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /**
     * 0.41.1, the self-test: why model {@code name} in one drop-in source can't be drawn, or {@code null} when it can.
     * Its item definition must point at a model that is in the source (or a vanilla one); every texture that model
     * names must be in the source, or a vanilla {@code block/} texture: on 26.x a model's boxes read the block atlas,
     * and a {@code minecraft:item/} texture there is the purple-black missing texture (0.41.0's own cabinets). A flat
     * item icon (0.58.0: a card, {@code parent minecraft:item/generated}) has no boxes, so an item texture is fine
     * there. A definition of another type (a composite, a condition) is taken as it is.
     *
     * @param json  the source's JSON files by path (as listed: a zip may have one extra folder)
     * @param paths every path in the source (the textures are looked for here)
     */
    public static String problem(String name, Map<String, String> json, Set<String> paths) {
        String itemPath = null;
        for (String p : json.keySet()) {
            String n = p.replace('\\', '/');
            if (n.endsWith(ClubModels.itemPath(name)) && (n.equals(ClubModels.itemPath(name))
                    || n.charAt(n.length() - ClubModels.itemPath(name).length() - 1) == '/')) {
                itemPath = p;
            }
        }
        if (itemPath == null) {
            return "it has no " + ClubModels.itemPath(name);
        }
        String root = itemPath.substring(0, itemPath.length() - ClubModels.itemPath(name).length());
        String model;
        try {
            com.google.gson.JsonObject def = com.google.gson.JsonParser.parseString(json.get(itemPath)).getAsJsonObject();
            com.google.gson.JsonObject m = def.getAsJsonObject("model");
            if (m == null) {
                return ClubModels.itemPath(name) + " has no \"model\"";
            }
            String type = m.has("type") ? m.get("type").getAsString() : "";
            if (!type.equals("minecraft:model") && !type.equals("model")) {
                return null; // a composite or a condition: drawn as the owner made it
            }
            model = m.get("model").getAsString();
        } catch (RuntimeException e) {
            return ClubModels.itemPath(name) + " can't be read: " + e.getMessage();
        }
        return modelProblem(model, root, json, paths, 0);
    }

    /** A model's (and its parent's) files and textures, as {@link #problem} says. */
    private static String modelProblem(String model, String root, Map<String, String> json, Set<String> paths,
                                       int depth) {
        String ns = model.contains(":") ? model.substring(0, model.indexOf(':')) : "minecraft";
        String path = model.contains(":") ? model.substring(model.indexOf(':') + 1) : model;
        if (ns.equals("minecraft") || depth > 4) {
            return null; // a vanilla model, drawn by every client
        }
        String file = root + "assets/" + ns + "/models/" + path + ".json";
        String text = json.get(file);
        if (text == null) {
            return "its model " + ns + ":" + path + " isn't there (" + file + ")";
        }
        try {
            com.google.gson.JsonObject m = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            // 0.58.0: a flat item icon (a card) has no boxes: an item/ texture is what it is meant to draw
            String parent = m.has("parent") ? m.get("parent").getAsString() : "";
            boolean flat = parent.equals("minecraft:item/generated") || parent.equals("item/generated")
                    || parent.equals("minecraft:item/handheld") || parent.equals("item/handheld");
            if (m.has("textures")) {
                for (Map.Entry<String, com.google.gson.JsonElement> t : m.getAsJsonObject("textures").entrySet()) {
                    String tex = t.getValue().getAsString();
                    if (tex.startsWith("#")) {
                        continue;
                    }
                    String tns = tex.contains(":") ? tex.substring(0, tex.indexOf(':')) : "minecraft";
                    String tpath = tex.contains(":") ? tex.substring(tex.indexOf(':') + 1) : tex;
                    if (tns.equals("minecraft")) {
                        if (tpath.startsWith("item/") && !flat) {
                            return "its model uses " + tex + ", an item texture, which 26.x can't draw on a model's"
                                    + " boxes (purple-black): use a block/ texture or your own";
                        }
                    } else if (!paths.contains(root + "assets/" + tns + "/textures/" + tpath + ".png")) {
                        return "its texture " + tex + " isn't there (" + root + "assets/" + tns + "/textures/" + tpath
                                + ".png)";
                    }
                }
            }
            if (m.has("parent")) {
                return modelProblem(m.get("parent").getAsString(), root, json, paths, depth + 1);
            }
        } catch (RuntimeException e) {
            return file + " can't be read: " + e.getMessage();
        }
        return null;
    }

    // ---- the server ---------------------------------------------------------------------------------

    /** On enable: write the placeholders and register the source, when CraftBridge is running. */
    public void enable() {
        Plugin cb = Bukkit.getPluginManager().getPlugin(CRAFTBRIDGE);
        if (cb == null || !cb.isEnabled()) {
            log.info("Pack: CraftBridge isn't running - the Clubhouse's side rooms show vanilla stand-ins for their"
                    + " models, and the card games plain cards.");
            return;
        }
        try {
            Class<?> api = Class.forName(API, true, cb.getClass().getClassLoader());
            Object service = Bukkit.getServicesManager().load(api);
            if (service == null) {
                log.warning("Pack: CraftBridge is running but has no pack API (it needs 0.19.0 or newer) - the side"
                        + " rooms show vanilla stand-ins.");
                return;
            }
            sync();
            api.getMethod("registerSource", Plugin.class, String.class, Path.class).invoke(service, plugin, SOURCE,
                    folder);
            pack = service;
            rebuild = api.getMethod("requestRebuild");
            registered = true;
            log.info("Pack: the Clubhouse's models and the playing cards are in CraftBridge's pack (source '" + SOURCE
                    + "'"
                    + (owners.isEmpty() ? ", placeholders" : ", your models for " + String.join(", ", owners)) + ").");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log.log(Level.WARNING, "Pack: couldn't add HCM's models to CraftBridge's pack - the side rooms show vanilla"
                    + " stand-ins", e);
            registered = false;
        }
    }

    /**
     * Look at the drop-ins again (once a minute, and on {@code /hcm reload}): a model the owner added (or took away)
     * changes which placeholders HCM leaves out, and CraftBridge is asked to rebuild.
     */
    public void check() {
        if (!registered) {
            return;
        }
        Set<String> before = owners;
        Set<String> repaintedBefore = repainted;
        try {
            sync();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Pack: couldn't refresh HCM's models", e);
            return;
        }
        if (!before.equals(owners) || !repaintedBefore.equals(repainted)) {
            log.info("Pack: your models now stand in for HCM's placeholders: " + (owners.isEmpty() ? "none"
                    : String.join(", ", owners)) + (repainted.isEmpty() ? "" : "; your pictures for the cards "
                    + String.join(", ", repainted)) + " - CraftBridge rebuilds the pack.");
            try {
                rebuild.invoke(pack);
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.log(Level.WARNING, "Pack: couldn't ask CraftBridge for a rebuild - run /craftbridge pack rebuild",
                        e);
            }
        }
    }

    /** Whether HCM's models are in the pack (CraftBridge took the source). */
    public boolean active() {
        return registered;
    }

    /** The model names the owner's drop-in has (HCM leaves its placeholders for them out). */
    public Set<String> owners() {
        return owners;
    }

    /** 0.58.0: the cards the owner's drop-in has a picture for (HCM leaves its own picture for them out). */
    public Set<String> repainted() {
        return repainted;
    }

    /** CraftBridge's drop-in folder, or {@code null} without CraftBridge. */
    Path dropIns() {
        Plugin cb = Bukkit.getPluginManager().getPlugin(CRAFTBRIDGE);
        return cb == null ? null : cb.getDataFolder().toPath().resolve("pack").resolve("sources");
    }

    /** Read the drop-ins, then write HCM's folder: every placeholder but the owner's names; the rest removed. */
    private void sync() {
        Set<String> repaints = new TreeSet<>();
        owners = scan(dropIns(), repaints);
        repainted = repaints;
        write(folder, files(owners, repainted), owners, repainted);
    }

    /**
     * Write HCM's {@code files} into {@code folder} (each only when its bytes differ, so an unchanged file keeps its
     * time and CraftBridge sees nothing new), then take out what the owner's drop-ins stand in for: every file of a
     * name in {@code owners}, the picture of a card in {@code repainted}.
     */
    static void write(Path folder, Map<String, byte[]> files, Set<String> owners, Set<String> repainted) {
        try {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                Path p = folder.resolve(f.getKey());
                Files.createDirectories(p.getParent());
                if (!Files.exists(p) || !java.util.Arrays.equals(Files.readAllBytes(p), f.getValue())) {
                    Files.write(p, f.getValue());
                }
            }
            Set<String> cards = new TreeSet<>(CardArt.names());
            for (String name : modelNames()) {
                if (owners.contains(name)) {
                    Files.deleteIfExists(folder.resolve(ClubModels.itemPath(name)));
                    Files.deleteIfExists(folder.resolve(ClubModels.placeholderModelPath(name)));
                }
                if (cards.contains(name) && (owners.contains(name) || repainted.contains(name))) {
                    Files.deleteIfExists(folder.resolve(CardArt.texturePath(name)));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("writing " + folder + ": " + e.getMessage(), e);
        }
    }

    /**
     * What one drop-in gave last time it was read: its stamp (see {@link #scan}), the models it supplies and (0.58.0)
     * the cards it repaints.
     */
    private record Seen(String stamp, Set<String> good, Set<String> repaints) {
    }

    private final Map<String, Seen> seen = new java.util.HashMap<>();

    /**
     * Every model name any drop-in (folder or zip) gives an item definition for.
     *
     * <p>0.45.0: a drop-in's files are read only when it changed (its stamp: for a zip its size and time, for a folder
     * its files' count, sizes and newest time) and only when it has an {@code hcm:} model at all. Before, every JSON of
     * every drop-in was read on the server thread each minute.
     *
     * <p>0.58.0: the cards any drop-in has a picture for go into {@code repaints} (from its paths alone).
     */
    private Set<String> scan(Path dropIns, Set<String> repaints) {
        Set<String> out = new LinkedHashSet<>();
        if (dropIns == null || !Files.isDirectory(dropIns)) {
            return out;
        }
        Set<String> here = new java.util.HashSet<>();
        try (Stream<Path> list = Files.list(dropIns)) {
            for (Path entry : list.sorted().toList()) {
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean folder = Files.isDirectory(entry);
                if (!folder && !name.endsWith(".zip")) {
                    continue;
                }
                here.add(name);
                List<String> paths = new ArrayList<>();
                List<Path> files = new ArrayList<>();
                String stamp;
                if (folder) {
                    long newest = 0;
                    long bytes = 0;
                    try (Stream<Path> walk = Files.walk(entry, 8)) {
                        for (Path p : walk.filter(Files::isRegularFile).toList()) {
                            paths.add(entry.relativize(p).toString().replace('\\', '/'));
                            files.add(p);
                            newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
                            bytes += Files.size(p);
                        }
                    }
                    stamp = "d:" + paths.size() + ":" + bytes + ":" + newest;
                } else {
                    stamp = "z:" + Files.size(entry) + ":" + Files.getLastModifiedTime(entry).toMillis();
                }
                Seen was = seen.get(name);
                if (was != null && was.stamp().equals(stamp)) {
                    out.addAll(was.good());
                    repaints.addAll(was.repaints());
                    continue;
                }
                Map<String, String> json = new java.util.HashMap<>();
                if (folder) {
                    if (!providedBy(paths).isEmpty()) {
                        for (int n = 0; n < paths.size(); n++) {
                            String rel = paths.get(n);
                            if (rel.endsWith(".json") && rel.contains("assets/")) {
                                json.put(rel, Files.readString(files.get(n), StandardCharsets.UTF_8));
                            }
                        }
                    }
                } else {
                    try (ZipFile zip = new ZipFile(entry.toFile())) {
                        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                            paths.add(e.nextElement().getName());
                        }
                        if (!providedBy(paths).isEmpty()) {
                            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                                ZipEntry z = e.nextElement();
                                if (!z.isDirectory() && z.getName().endsWith(".json")
                                        && z.getName().contains("assets/")) {
                                    try (var in = zip.getInputStream(z)) {
                                        json.put(z.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                                    }
                                }
                            }
                        }
                    }
                }
                Set<String> all = new java.util.HashSet<>(paths);
                Set<String> good = new LinkedHashSet<>();
                for (String model : providedBy(paths)) {
                    String why = problem(model, json, all);
                    if (why == null) {
                        good.add(model);
                    } else if (warned.add(name + "/" + model + ": " + why)) {
                        log.warning("Pack: your model " + ClubModels.NS + ":" + model + " in " + name + " can't be"
                                + " drawn - " + why + ". HCM keeps its own placeholder for it until it's fixed.");
                    }
                }
                Set<String> pictures = CardArt.repaintedBy(paths);
                seen.put(name, new Seen(stamp, good, pictures));
                out.addAll(good);
                repaints.addAll(pictures);
            }
            seen.keySet().retainAll(here);
        } catch (IOException e) {
            log.warning("Pack: couldn't read CraftBridge's drop-ins (" + dropIns + "): " + e.getMessage());
        }
        return out;
    }
}
