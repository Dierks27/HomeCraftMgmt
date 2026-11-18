package com.dierks.homecraft.pack;

import org.bukkit.Bukkit;
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
 * <p>All on the main thread; a handful of small files.
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
    /** 0.41.1: each broken drop-in model is said once (it is looked at once a minute). */
    private final Set<String> warned = new java.util.HashSet<>();
    private boolean registered;

    public PackModels(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.folder = plugin.getDataFolder().toPath().resolve("pack");
    }

    // ---- pure ---------------------------------------------------------------------------------------

    /**
     * Which of HCM's model names the paths of a drop-in source give an item definition for (the owner's models).
     * Only names HCM uses count; a path under {@code placeholder/} never does.
     */
    public static Set<String> providedBy(Collection<String> paths) {
        Set<String> names = new TreeSet<>();
        for (ClubModels.Model m : ClubModels.all()) {
            names.add(m.name());
        }
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
     * and a {@code minecraft:item/} texture there is the purple-black missing texture (0.41.0's own cabinets). A
     * definition of another type (a composite, a condition) is taken as it is.
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
            if (m.has("textures")) {
                for (Map.Entry<String, com.google.gson.JsonElement> t : m.getAsJsonObject("textures").entrySet()) {
                    String tex = t.getValue().getAsString();
                    if (tex.startsWith("#")) {
                        continue;
                    }
                    String tns = tex.contains(":") ? tex.substring(0, tex.indexOf(':')) : "minecraft";
                    String tpath = tex.contains(":") ? tex.substring(tex.indexOf(':') + 1) : tex;
                    if (tns.equals("minecraft")) {
                        if (tpath.startsWith("item/")) {
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
                    + " models.");
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
            log.info("Pack: the Clubhouse's models are in CraftBridge's pack (source '" + SOURCE + "'"
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
        try {
            sync();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Pack: couldn't refresh HCM's models", e);
            return;
        }
        if (!before.equals(owners)) {
            log.info("Pack: your models now stand in for HCM's placeholders: " + (owners.isEmpty() ? "none"
                    : String.join(", ", owners)) + " - CraftBridge rebuilds the pack.");
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

    /** CraftBridge's drop-in folder, or {@code null} without CraftBridge. */
    Path dropIns() {
        Plugin cb = Bukkit.getPluginManager().getPlugin(CRAFTBRIDGE);
        return cb == null ? null : cb.getDataFolder().toPath().resolve("pack").resolve("sources");
    }

    /** Read the drop-ins, then write HCM's folder: every placeholder but the owner's names; the rest removed. */
    private void sync() {
        owners = scan(dropIns());
        Map<String, String> files = ClubModels.placeholderFiles(owners);
        try {
            for (Map.Entry<String, String> f : files.entrySet()) {
                Path p = folder.resolve(f.getKey());
                Files.createDirectories(p.getParent());
                if (!Files.exists(p) || !Files.readString(p, StandardCharsets.UTF_8).equals(f.getValue())) {
                    Files.writeString(p, f.getValue(), StandardCharsets.UTF_8);
                }
            }
            for (ClubModels.Model m : ClubModels.all()) {
                if (owners.contains(m.name())) {
                    Files.deleteIfExists(folder.resolve(ClubModels.itemPath(m.name())));
                    Files.deleteIfExists(folder.resolve(ClubModels.placeholderModelPath(m.name())));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("writing " + folder + ": " + e.getMessage(), e);
        }
    }

    /** Every model name any drop-in (folder or zip) gives an item definition for. */
    private Set<String> scan(Path dropIns) {
        Set<String> out = new LinkedHashSet<>();
        if (dropIns == null || !Files.isDirectory(dropIns)) {
            return out;
        }
        try (Stream<Path> list = Files.list(dropIns)) {
            for (Path entry : list.sorted().toList()) {
                List<String> paths = new ArrayList<>();
                Map<String, String> json = new java.util.HashMap<>();
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isDirectory(entry)) {
                    try (Stream<Path> walk = Files.walk(entry, 8)) {
                        for (Path p : walk.filter(Files::isRegularFile).toList()) {
                            String rel = entry.relativize(p).toString().replace('\\', '/');
                            paths.add(rel);
                            if (rel.endsWith(".json") && rel.contains("assets/")) {
                                json.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                            }
                        }
                    }
                } else if (name.endsWith(".zip")) {
                    try (ZipFile zip = new ZipFile(entry.toFile())) {
                        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                            ZipEntry z = e.nextElement();
                            paths.add(z.getName());
                            if (!z.isDirectory() && z.getName().endsWith(".json") && z.getName().contains("assets/")) {
                                try (var in = zip.getInputStream(z)) {
                                    json.put(z.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                }
                Set<String> all = new java.util.HashSet<>(paths);
                for (String model : providedBy(paths)) {
                    String why = problem(model, json, all);
                    if (why == null) {
                        out.add(model);
                    } else if (warned.add(name + "/" + model + ": " + why)) {
                        log.warning("Pack: your model " + ClubModels.NS + ":" + model + " in " + name + " can't be"
                                + " drawn - " + why + ". HCM keeps its own placeholder for it until it's fixed.");
                    }
                }
            }
        } catch (IOException e) {
            log.warning("Pack: couldn't read CraftBridge's drop-ins (" + dropIns + "): " + e.getMessage());
        }
        return out;
    }
}
