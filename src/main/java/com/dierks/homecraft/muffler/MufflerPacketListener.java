package com.dierks.homecraft.muffler;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Everything that touches ProtocolLib, in one class that is only loaded when ProtocolLib is
 * running — so the plugin still starts without it.
 *
 * <p>Runs on whatever thread sends the packet, usually a network thread. It reads only the frozen
 * {@link MufflerZones} and never edits a packet: every listener hears the same verdict for a sound
 * (it depends on where the sound was made, not who hears it), and the game sends one packet object
 * to all of them. Silent cancels the packet for this player; Quieter cancels it and queues a
 * quieter copy (see {@link Replay}).
 *
 * <p>Anything unexpected — a field that moved in a new Minecraft version, a sound ProtocolLib
 * can't convert — lets the packet through untouched and is logged once. A muffler that stops
 * working is a nuisance; one that eats every sound on the server is not acceptable.
 */
final class MufflerPacketListener extends PacketAdapter {

    private final SoundMufflerService service;

    private MufflerPacketListener(Plugin plugin, SoundMufflerService service) {
        super(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.NAMED_SOUND_EFFECT,
                PacketType.Play.Server.ENTITY_SOUND,
                PacketType.Play.Server.WORLD_EVENT);
        this.service = service;
    }

    /** Register the listener; the returned action removes it again. */
    static Runnable install(Plugin plugin, SoundMufflerService service) {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        MufflerPacketListener listener = new MufflerPacketListener(plugin, service);
        manager.addPacketListener(listener);
        return () -> manager.removePacketListener(listener);
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (event.isCancelled() || !service.muffling()) {
            return;
        }
        try {
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            List<Muffler> here = service.zones().in(player.getWorld().getName());
            if (here.isEmpty()) {
                return;
            }
            PacketType type = event.getPacketType();
            if (type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
                positional(event, player, here);
            } else if (type == PacketType.Play.Server.ENTITY_SOUND) {
                attached(event, player, here);
            } else if (type == PacketType.Play.Server.WORLD_EVENT) {
                levelEvent(event, player, here);
            }
        } catch (Throwable t) {
            service.packetFailed(String.valueOf(event.getPacketType()), t);
        }
    }

    /** A sound at a point: the coordinates travel as whole eighths of a block. */
    private void positional(PacketEvent event, Player player, List<Muffler> here) {
        PacketContainer p = event.getPacket();
        double x;
        double y;
        double z;
        StructureModifier<Integer> ints = p.getIntegers();
        if (ints.size() >= 3) {
            x = ints.read(0) / 8.0;
            y = ints.read(1) / 8.0;
            z = ints.read(2) / 8.0;
        } else {
            StructureModifier<Double> doubles = p.getDoubles();
            if (doubles.size() < 3) {
                return;
            }
            x = doubles.read(0);
            y = doubles.read(1);
            z = doubles.read(2);
        }
        sound(event, player, here, p, x, y, z);
    }

    /**
     * A sound that follows an entity (a goat horn, a few others). Finding the entity by its id is
     * only safe on the main thread; off it the sound goes through, which is rare enough to accept.
     */
    private void attached(PacketEvent event, Player player, List<Muffler> here) {
        if (!Bukkit.isPrimaryThread()) {
            return;
        }
        PacketContainer p = event.getPacket();
        StructureModifier<Integer> ints = p.getIntegers();
        if (ints.size() < 1) {
            return;
        }
        Entity entity = ProtocolLibrary.getProtocolManager().getEntityFromID(player.getWorld(), ints.read(0));
        if (entity == null) {
            return;
        }
        Location at = entity.getLocation();
        sound(event, player, here, p, at.getX(), at.getY(), at.getZ());
    }

    private void sound(PacketEvent event, Player player, List<Muffler> here, PacketContainer p,
                       double x, double y, double z) {
        if (!MufflerZones.anyContains(here, x, y, z)) {
            return;
        }
        StructureModifier<Long> longs = p.getLongs();
        if (longs.size() > 0 && Replay.isReplay(longs.read(0))) {
            return; // our own quieter copy coming back
        }
        String key = soundKey(p);
        if (key == null) {
            return;
        }
        MufflerZones.Verdict verdict = MufflerZones.decide(here, x, y, z, key, service.heardSink());
        if (!verdict.changes()) {
            return;
        }
        event.setCancelled(true);
        if (!verdict.silent()) {
            StructureModifier<Float> floats = p.getFloat();
            float volume = floats.size() > 0 ? floats.read(0) : 1f;
            float pitch = floats.size() > 1 ? floats.read(1) : 1f;
            service.replay(player, x, y, z, key, category(p), volume * verdict.factor(), pitch);
        }
    }

    /** The dispenser click and friends: only the events {@link LevelEvents} knows are touched. */
    private void levelEvent(PacketEvent event, Player player, List<Muffler> here) {
        PacketContainer p = event.getPacket();
        StructureModifier<Integer> ints = p.getIntegers();
        if (ints.size() < 1) {
            return;
        }
        LevelEvents.Entry entry = LevelEvents.of(ints.read(0));
        if (entry == null) {
            return;
        }
        StructureModifier<Boolean> flags = p.getBooleans();
        if (flags.size() > 0 && Boolean.TRUE.equals(flags.read(0))) {
            return; // a "heard everywhere" event is not a noise from next door
        }
        BlockPosition pos = p.getBlockPositionModifier().read(0);
        if (pos == null) {
            return;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        if (!MufflerZones.anyContains(here, x, y, z)) {
            return;
        }
        MufflerZones.Verdict verdict = MufflerZones.decide(here, x, y, z, entry.key(), service.heardSink());
        if (!verdict.changes()) {
            return;
        }
        event.setCancelled(true);
        if (!verdict.silent()) {
            service.replay(player, x, y, z, entry.key(), SoundMufflerService.category(entry.category()),
                    entry.volume() * verdict.factor(), entry.pitch());
        }
    }

    /**
     * The sound's key. ProtocolLib's own conversion first; if it can't (a resource pack's sound is
     * not in the registry, or a new version moved things), read it from the game's sound object.
     */
    private static String soundKey(PacketContainer p) {
        try {
            Sound sound = p.getSoundEffects().readSafely(0);
            NamespacedKey key = sound == null ? null : Registry.SOUNDS.getKey(sound);
            if (key != null) {
                return key.asString();
            }
        } catch (Throwable ignored) {
            // fall back below
        }
        for (Object value : p.getModifier().getValues()) {
            if (value != null && value.getClass().getName().contains("Holder")) {
                String key = SoundNames.fromHolderText(String.valueOf(value));
                if (key != null) {
                    return key;
                }
            }
        }
        return null;
    }

    /** The sound's category, so its quieter copy still follows the right volume slider. */
    private static SoundCategory category(PacketContainer p) {
        try {
            EnumWrappers.SoundCategory c = p.getSoundCategories().readSafely(0);
            if (c != null) {
                return SoundMufflerService.category(c.name());
            }
        } catch (Throwable ignored) {
            // fall back below
        }
        try {
            for (Object value : p.getModifier().getValues()) {
                if (value instanceof Enum<?> e && value.getClass().getSimpleName().contains("SoundSource")) {
                    return SoundMufflerService.category(e.name());
                }
            }
        } catch (Throwable ignored) {
            // MASTER below
        }
        return SoundCategory.MASTER;
    }
}
