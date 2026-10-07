package fr.eternom.eterSync.module.sync;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Photo de tout ce qui est synchronisé : inventaire (avec armure et seconde main), slot en main,
 * expérience, vie, faim, effets, mode de jeu, progrès et recettes débloquées. Pas le coffre de l'Ender (plugin dédié).
 *
 * Progrès et recettes : sans eux, chaque serveur redécouvre tout et le joueur reçoit une pluie de notifications
 * (« Nouvelles recettes », progrès) à chaque changement de serveur. Ils ne font que s'ajouter (rien n'est retiré) ;
 * les progrès « recipes/... » ne sont pas gardés : ils se refont seuls, en silence, une fois les recettes connues.
 *
 * Les items passent par le format de Paper (serializeItemsAsBytes), qui suit les mises à jour de Minecraft :
 * un inventaire enregistré aujourd'hui reste lisible après une montée de version du serveur.
 */
public record PlayerSnapshot(ItemStack[] inventory, int heldSlot, GameMode gameMode, double health, int food,
                             float saturation, float exhaustion, int level, float exp, int totalExperience,
                             List<PotionEffect> effects, Map<String, List<String>> advancements, List<String> recipes) {

    /** Version du format binaire, à incrémenter si on ajoute un champ. */
    private static final int FORMAT = 2;
    /** Format 1 (avant les progrès et les recettes) : encore lu, sans eux. */
    private static final int FORMAT_WITHOUT_PROGRESS = 1;

    /** Thread principal : lit l'état du joueur. */
    public static PlayerSnapshot capture(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] copy = Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        return new PlayerSnapshot(copy, player.getInventory().getHeldItemSlot(), player.getGameMode(), player.getHealth(),
                player.getFoodLevel(), player.getSaturation(), player.getExhaustion(), player.getLevel(), player.getExp(),
                player.getTotalExperience(), List.copyOf(player.getActivePotionEffects()), advancements(player),
                player.getDiscoveredRecipes().stream().map(NamespacedKey::toString).toList());
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

        player.discoverRecipes(recipes.stream().map(NamespacedKey::fromString).filter(Objects::nonNull)
                .filter(key -> !player.hasDiscoveredRecipe(key)).toList());
        awardAdvancements(player);
    }

    /** Progrès faits (critères obtenus), sauf ceux qui débloquent des recettes. */
    private static Map<String, List<String>> advancements(Player player) {
        Map<String, List<String>> done = new HashMap<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> {
            if (advancement.getKey().getKey().startsWith("recipes/")) {
                return;
            }
            Collection<String> awarded = player.getAdvancementProgress(advancement).getAwardedCriteria();
            if (!awarded.isEmpty()) {
                done.put(advancement.getKey().toString(), List.copyOf(awarded));
            }
        });
        return done;
    }

    /** Ajoute les critères manquants, sans annonce dans le chat (la règle est rétablie juste après). */
    private void awardAdvancements(Player player) {
        World world = player.getWorld();
        Boolean announce = world.getGameRuleValue(GameRules.SHOW_ADVANCEMENT_MESSAGES);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        try {
            advancements.forEach((key, criteria) -> {
                NamespacedKey namespacedKey = NamespacedKey.fromString(key);
                Advancement advancement = namespacedKey == null ? null : Bukkit.getAdvancement(namespacedKey);
                if (advancement == null) {
                    return; // progrès retiré du jeu
                }
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                criteria.stream().filter(progress.getRemainingCriteria()::contains).forEach(progress::awardCriteria);
            });
        } finally {
            world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, announce == null || announce);
        }
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

            out.writeInt(recipes.size());
            for (String recipe : recipes) {
                out.writeUTF(recipe);
            }
            out.writeInt(advancements.size());
            for (Map.Entry<String, List<String>> advancement : advancements.entrySet()) {
                out.writeUTF(advancement.getKey());
                out.writeInt(advancement.getValue().size());
                for (String criterion : advancement.getValue()) {
                    out.writeUTF(criterion);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible d'enregistrer l'inventaire", e);
        }
    }

    public static PlayerSnapshot fromBytes(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int format = in.readInt();
            if (format != FORMAT && format != FORMAT_WITHOUT_PROGRESS) {
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

            List<String> recipes = new ArrayList<>();
            Map<String, List<String>> advancements = new HashMap<>();
            if (format == FORMAT) {
                int recipeCount = in.readInt();
                for (int i = 0; i < recipeCount; i++) {
                    recipes.add(in.readUTF());
                }
                int advancementCount = in.readInt();
                for (int i = 0; i < advancementCount; i++) {
                    String key = in.readUTF();
                    List<String> criteria = new ArrayList<>();
                    int criteriaCount = in.readInt();
                    for (int c = 0; c < criteriaCount; c++) {
                        criteria.add(in.readUTF());
                    }
                    advancements.put(key, List.copyOf(criteria));
                }
            }
            return new PlayerSnapshot(ItemStack.deserializeItemsFromBytes(items), heldSlot, gameMode, health, food,
                    saturation, exhaustion, level, exp, totalExperience, List.copyOf(effects), Map.copyOf(advancements),
                    List.copyOf(recipes));
        } catch (IOException e) {
            throw new IllegalStateException("Sauvegarde d'inventaire illisible", e);
        }
    }
}
