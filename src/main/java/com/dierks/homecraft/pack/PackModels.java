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
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isDirectory(entry)) {
                    try (Stream<Path> walk = Files.walk(entry, 8)) {
                        walk.filter(Files::isRegularFile).forEach(p -> paths.add(entry.relativize(p).toString()));
                    }
                } else if (name.endsWith(".zip")) {
                    try (ZipFile zip = new ZipFile(entry.toFile())) {
                        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                            paths.add(e.nextElement().getName());
                        }
                    }
                }
                out.addAll(providedBy(paths));
            }
        } catch (IOException e) {
            log.warning("Pack: couldn't read CraftBridge's drop-ins (" + dropIns + "): " + e.getMessage());
        }
        return out;
    }
}
