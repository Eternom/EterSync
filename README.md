# EterSync

Synchronise l'état des joueurs entre les serveurs d'un même **groupe** : inventaire (armure et seconde main comprises),
slot en main, expérience, vie, faim, effets et mode de jeu. Pas le coffre de l'Ender (plugin dédié à venir).
Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.1.0+** (`depend`), avec **Redis activé** (`cache.enabled: true`) : sans Redis, EterSync se désactive.
- Même base de données et même Redis pour tous les serveurs du groupe.

## Fonctionnement

Velocity connecte le joueur au nouveau serveur **avant** de le déconnecter de l'ancien. Un verrou Redis
(`sync:lock:<groupe>:<uuid>`, TTL 60 s rafraîchi) garantit qu'un serveur ne charge jamais un état périmé :

1. **Arrivée sur B** : le joueur est **figé** (inventaire, drop, ramassage, blocs, commandes…). En tâche de fond, B attend
   que A ait enregistré et libéré le verrou (`lock-wait`, 10 s max), le prend et lit la dernière sauvegarde.
2. **Chargement** : la sauvegarde est appliquée. Un joueur **jamais synchronisé garde son inventaire actuel**, qui devient
   sa première sauvegarde : **rien n'est jamais remis à zéro**. Puis le joueur est libéré.
3. **Départ de A** : A enregistre, **puis** libère le verrou.

Garde-fous :
- un joueur dont l'état n'a pas été chargé n'est **jamais enregistré** (son inventaire local écraserait le vrai) ;
- toutes les écritures passent par **un seul fil, dans l'ordre** : une sauvegarde ancienne ne peut pas devenir « la dernière » ;
- sauvegarde automatique (`autosave-minutes`) et à l'arrêt du serveur ;
- si la base est injoignable, la sauvegarde est écrite dans `plugins/EterSync/failed/` (jamais perdue).

## Historique (lecture seule)

Table `etersync_snapshots` : une **ligne par sauvegarde** (jamais de mise à jour), les `history-size` plus récentes
gardées par joueur et par groupe. `/etersync history <joueur>` (`etersync.admin`, op par défaut) : liste et aperçu de
l'inventaire et de l'état, **sans restauration** : un joueur pourrait donner ses items puis demander à les récupérer
(duplication). Les clics dans l'aperçu sont annulés, aucun item ne peut en sortir.

Format : `PlayerSnapshot#toBytes` (version de format en tête), items via `ItemStack.serializeItemsAsBytes` de Paper,
mis à jour automatiquement lors des montées de version de Minecraft.
