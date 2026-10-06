package fr.eternom.eterSync.module.sync;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Photo de tout ce qui est synchronisé : inventaire (avec armure et seconde main), slot en main,
 * expérience, vie, faim, effets et mode de jeu. Pas le coffre de l'Ender (plugin dédié).
 *
 * Les items passent par le format de Paper (serializeItemsAsBytes), qui suit les mises à jour de Minecraft :
 * un inventaire enregistré aujourd'hui reste lisible après une montée de version du serveur.
 */
public record PlayerSnapshot(ItemStack[] inventory, int heldSlot, GameMode gameMode, double health, int food,
                             float saturation, float exhaustion, int level, float exp, int totalExperience,
                             List<PotionEffect> effects) {

    /** Version du format binaire, à incrémenter si on ajoute un champ. */
    private static final int FORMAT = 1;

    /** Thread principal : lit l'état du joueur. */
    public static PlayerSnapshot capture(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] copy = Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        return new PlayerSnapshot(copy, player.getInventory().getHeldItemSlot(), player.getGameMode(), player.getHealth(),
                player.getFoodLevel(), player.getSaturation(), player.getExhaustion(), player.getLevel(), player.getExp(),
                player.getTotalExperience(), List.copyOf(player.getActivePotionEffects()));
    }

    /** Thread principal : remplace l'état du joueur par cette photo. */
    public void apply(Player player) {
        player.getInventory().setContents(Arrays.copyOf(inventory, player.getInventory().getContents().length));
        player.getInventory().setHeldItemSlot(Math.clamp(heldSlot, 0, 8));
        player.setGameMode(gameMode);

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20 : maxHealth.getValue();
        // Vie à 0 = photo prise sur l'écran de mort : on ne tue pas le joueur à l'arrivée
        if (health > 0) {
            player.setHealth(Math.min(health, max));
        }
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);

        player.setTotalExperience(totalExperience);
        player.setLevel(level);
        player.setExp(Math.clamp(exp, 0f, 1f));

        new ArrayList<>(player.getActivePotionEffects()).forEach(effect -> player.removePotionEffect(effect.getType()));
        effects.forEach(player::addPotionEffect);
    }

    /** Nombre d'items (piles non vides), pour l'historique. */
    public int itemCount() {
        return (int) Arrays.stream(inventory).filter(item -> item != null && !item.isEmpty()).count();
    }

    public byte[] toBytes() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT);
            out.writeUTF(gameMode.name());
            out.writeInt(heldSlot);
            out.writeDouble(health);
            out.writeInt(food);
            out.writeFloat(saturation);
            out.writeFloat(exhaustion);
            out.writeInt(level);
            out.writeFloat(exp);
            out.writeInt(totalExperience);

            out.writeInt(effects.size());
            for (PotionEffect effect : effects) {
                out.writeUTF(effect.getType().getKey().toString());
                out.writeInt(effect.getDuration());
                out.writeInt(effect.getAmplifier());
                out.writeBoolean(effect.isAmbient());
                out.writeBoolean(effect.hasParticles());
                out.writeBoolean(effect.hasIcon());
            }

            byte[] items = ItemStack.serializeItemsAsBytes(inventory);
            out.writeInt(items.length);
            out.write(items);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible d'enregistrer l'inventaire", e);
        }
    }

    public static PlayerSnapshot fromBytes(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int format = in.readInt();
            if (format != FORMAT) {
                throw new IllegalStateException("Format de sauvegarde inconnu : " + format);
            }
            GameMode gameMode = GameMode.valueOf(in.readUTF());
            int heldSlot = in.readInt();
            double health = in.readDouble();
            int food = in.readInt();
            float saturation = in.readFloat();
            float exhaustion = in.readFloat();
            int level = in.readInt();
            float exp = in.readFloat();
            int totalExperience = in.readInt();

            int effectCount = in.readInt();
            List<PotionEffect> effects = new ArrayList<>();
            for (int i = 0; i < effectCount; i++) {
                String key = in.readUTF();
                int duration = in.readInt();
                int amplifier = in.readInt();
                boolean ambient = in.readBoolean();
                boolean particles = in.readBoolean();
                boolean icon = in.readBoolean();
                NamespacedKey namespacedKey = NamespacedKey.fromString(key);
                PotionEffectType type = namespacedKey == null ? null : Registry.EFFECT.get(namespacedKey);
                if (type != null) { // effet retiré du jeu : ignoré plutôt que de bloquer toute la sauvegarde
                    effects.add(new PotionEffect(type, duration, amplifier, ambient, particles, icon));
                }
            }

            byte[] items = new byte[in.readInt()];
            in.readFully(items);
            return new PlayerSnapshot(ItemStack.deserializeItemsFromBytes(items), heldSlot, gameMode, health, food,
                    saturation, exhaustion, level, exp, totalExperience, List.copyOf(effects));
        } catch (IOException e) {
            throw new IllegalStateException("Sauvegarde d'inventaire illisible", e);
        }
    }
}
