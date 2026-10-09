package dev.ultimatecombat.core.effect;

import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.config.ConfigService;
import dev.ultimatecombat.core.scheduler.TaskScheduler;
import dev.ultimatecombat.core.util.Registries;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Named sound and particle presets from {@code sounds.yml} and {@code particles.yml}. Every other
 * file refers to effects by preset name, so each effect is configured in exactly one place.
 */
public final class EffectService {

    private final ConfigService configs;
    private final TaskScheduler scheduler;
    private volatile Map<String, SoundSpec> sounds = Map.of();
    private volatile Map<String, ParticleSpec> particles = Map.of();
    private volatile boolean soundsEnabled = true;
    private volatile boolean particlesEnabled = true;

    public record SoundSpec(String key, float volume, float pitch) {
    }

    public record ParticleSpec(Particle particle, int count, double offsetX, double offsetY, double offsetZ, double speed,
                               Color color, float size, double yOffset) {
    }

    public EffectService(ConfigService configs, TaskScheduler scheduler) {
        this.configs = configs;
        this.scheduler = scheduler;
    }

    public void load() {
        ConfigReader s = configs.file("sounds.yml").reader();
        soundsEnabled = s.bool("enabled", true);
        Map<String, SoundSpec> newSounds = new HashMap<>();
        for (String name : s.keys("sounds")) {
            ConfigReader e = s.section("sounds." + name);
            if (!e.bool("enabled", true)) {
                continue;
            }
            String raw = e.string("sound", "");
            String key = Registries.sound(raw);
            if (key == null) {
                e.warn("sound", raw, "Unknown sound (preset disabled)", "a sound key such as entity.player.levelup", "silent");
                continue;
            }
            newSounds.put(name.toLowerCase(Locale.ROOT), new SoundSpec(key,
                    (float) e.decimal("volume", 1.0, 0.0, 10.0), (float) e.decimal("pitch", 1.0, 0.5, 2.0)));
        }
        sounds = newSounds;

        ConfigReader p = configs.file("particles.yml").reader();
        particlesEnabled = p.bool("enabled", true);
        Map<String, ParticleSpec> newParticles = new HashMap<>();
        for (String name : p.keys("particles")) {
            ConfigReader e = p.section("particles." + name);
            if (!e.bool("enabled", true)) {
                continue;
            }
            String raw = e.string("particle", "");
            Particle particle = Registries.particle(raw);
            if (particle == null) {
                e.warn("particle", raw, "Unknown particle (preset disabled)", "a particle such as HEART or DUST", "no particles");
                continue;
            }
            Class<?> data = particle.getDataType();
            if (data != Void.class && data != Particle.DustOptions.class) {
                e.warn("particle", raw, "Particle needs extra data that presets do not support", "a particle without data, or DUST", "no particles");
                continue;
            }
            Color color = parseColor(e, "color", Color.RED);
            newParticles.put(name.toLowerCase(Locale.ROOT), new ParticleSpec(particle,
                    e.integer("count", 10, 0, 1000),
                    e.decimal("offset-x", 0.3, 0, 16), e.decimal("offset-y", 0.3, 0, 16), e.decimal("offset-z", 0.3, 0, 16),
                    e.decimal("speed", 0.0, 0, 10), color, (float) e.decimal("size", 1.0, 0.01, 4.0),
                    e.decimal("y-offset", 1.0, -5, 5)));
        }
        particles = newParticles;
    }

    public static Color parseColor(ConfigReader e, String path, Color def) {
        String raw = e.string(path, "");
        if (raw.isEmpty()) {
            return def;
        }
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        try {
            return Color.fromRGB(Integer.parseInt(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            String[] parts = raw.split(",");
            if (parts.length == 3) {
                try {
                    return Color.fromRGB(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
                } catch (IllegalArgumentException ignored) {
                    // fall through to warning
                }
            }
            e.info(path, "Invalid colour '" + raw + "', expected #RRGGBB or R,G,B");
            return def;
        }
    }

    public boolean hasSound(String preset) {
        return preset != null && sounds.containsKey(preset.toLowerCase(Locale.ROOT));
    }

    /**
     * Plays a preset (or a raw sound key) to one player only.
     */
    public void playSound(Player player, String preset) {
        SoundSpec spec = resolveSound(preset);
        if (spec == null || player == null) {
            return;
        }
        player.playSound(player.getLocation(), spec.key(), spec.volume(), spec.pitch());
    }

    /**
     * Plays a preset at a location for everyone nearby.
     */
    public void playSound(Location location, String preset) {
        SoundSpec spec = resolveSound(preset);
        if (spec == null || location == null || location.getWorld() == null) {
            return;
        }
        scheduler.executeAt(location, () -> location.getWorld().playSound(location, spec.key(), spec.volume(), spec.pitch()));
    }

    private SoundSpec resolveSound(String preset) {
        if (!soundsEnabled || preset == null || preset.isEmpty() || preset.equalsIgnoreCase("none")) {
            return null;
        }
        SoundSpec spec = sounds.get(preset.toLowerCase(Locale.ROOT));
        if (spec == null) {
            String key = Registries.sound(preset);
            if (key != null && (preset.contains(".") || preset.contains(":") || preset.contains("_"))) {
                spec = new SoundSpec(key, 1f, 1f);
            }
        }
        return spec;
    }

    public void spawnParticle(Location location, String preset) {
        ParticleSpec spec = resolveParticle(preset);
        if (spec == null || location == null || location.getWorld() == null) {
            return;
        }
        Location at = location.clone().add(0, spec.yOffset(), 0);
        scheduler.executeAt(at, () -> spawn(at.getWorld(), null, at, spec));
    }

    /**
     * Shows a particle preset only to one player.
     */
    public void spawnParticle(Player viewer, Location location, String preset) {
        ParticleSpec spec = resolveParticle(preset);
        if (spec == null || viewer == null || location == null) {
            return;
        }
        Location at = location.clone().add(0, spec.yOffset(), 0);
        spawn(null, viewer, at, spec);
    }

    private ParticleSpec resolveParticle(String preset) {
        if (!particlesEnabled || preset == null || preset.isEmpty() || preset.equalsIgnoreCase("none")) {
            return null;
        }
        return particles.get(preset.toLowerCase(Locale.ROOT));
    }

    private static void spawn(World world, Player viewer, Location at, ParticleSpec spec) {
        Object data = spec.particle().getDataType() == Particle.DustOptions.class
                ? new Particle.DustOptions(spec.color(), spec.size()) : null;
        if (viewer != null) {
            viewer.spawnParticle(spec.particle(), at, spec.count(), spec.offsetX(), spec.offsetY(), spec.offsetZ(), spec.speed(), data);
        } else if (world != null) {
            world.spawnParticle(spec.particle(), at, spec.count(), spec.offsetX(), spec.offsetY(), spec.offsetZ(), spec.speed(), data);
        }
    }
}
