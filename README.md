# EterSync

Synchronise l'état des joueurs entre les serveurs d'un même **groupe** : inventaire (armure et seconde main comprises),
slot en main, expérience, vie, faim, effets, mode de jeu, **recettes débloquées** (elles ne font que s'ajouter) et
**coffre de l'Ender**. Format 3 des sauvegardes (la partie « succès » est écrite vide et ignorée à la lecture ; les
formats 2, sans coffre de l'Ender, et 1, sans recettes, sont encore lus) :
**tous les serveurs d'un groupe doivent avoir la même version**.
Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.10.0+** (`depend`, textes communs), et donc **Redis** (obligatoire) : il sert de verrou.
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
- pendant le chargement, le joueur est **invulnérable**, et une mort qui passerait quand même ne lâche rien : sinon il
  lâcherait au sol son ancien inventaire local avant de recevoir le vrai (duplication) ;
- un joueur dont l'état n'a pas été chargé n'est **jamais enregistré** (son inventaire local écraserait le vrai) ;
- toutes les écritures passent par **un seul fil, dans l'ordre** : une sauvegarde ancienne ne peut pas devenir « la dernière » ;
- sauvegarde automatique (`autosave-minutes`) et à l'arrêt du serveur ;
- si la base est injoignable, la sauvegarde est écrite dans `plugins/EterSync/failed/` (jamais perdue).

## Coffre de l'Ender

Synchronisé comme l'inventaire, avec une précaution : avant EterSync 1.2.0, **chaque serveur avait son propre coffre
local**, et rien ne doit être perdu. La sauvegarde garde la liste des serveurs dont le coffre local a déjà été versé
(`enderServers`, dans la même écriture que le coffre : jamais « versé » sans que le coffre versé soit enregistré). À
l'arrivée (`SyncService#applyEnderChest`) :
- serveur déjà dans la liste : le coffre synchronisé remplace le local ;
- serveur pas encore dans la liste : le coffre local est **versé** dans le coffre synchronisé (ce qui ne rentre pas va
  dans l'inventaire, puis au sol), le serveur est ajouté, et la sauvegarde est réécrite tout de suite (raison
  `ENDER_MERGE`, visible dans l'historique) ;
- sauvegarde d'avant le format 3 (coffre inconnu) : le coffre local devient la référence.

Les serveurs recréés (lobbys, ressources) ont un coffre local vide : rien à verser.

## Succès désactivés (toujours)

Chaque serveur a ses propres succès : les redonner à l'arrivée (synchronisation) fait rejouer au client toutes leurs
notifications et leurs sons, ce que le serveur ne peut pas cacher. Au démarrage, `module/sync/Advancements` les retire
donc du serveur (`UnsafeValues#removeAdvancement`), sauf les recettes (`recipes/...`, livre de recettes). Les recettes se débloquent sans
notification (`RecipeToastListener` : `PlayerRecipeDiscoverEvent#shouldShowNotification(false)`), sinon l'arrivée sur
un serveur en affiche des dizaines. Un plugin de succès propre au réseau (en base, sans notifications de Minecraft)
les remplacera.

## Historique (lecture seule)

Table `etersync_snapshots` : une **ligne par sauvegarde** (jamais de mise à jour), les `history-size` plus récentes
gardées par joueur et par groupe. `/etersync history <joueur>` (`etersync.admin`, op par défaut) : liste et aperçu de
l'inventaire, du coffre de l'Ender (bouton en bas à droite) et de l'état, **sans restauration** : un joueur pourrait donner ses items puis demander à les récupérer
(duplication). Les clics dans l'aperçu sont annulés, aucun item ne peut en sortir.

Format : `PlayerSnapshot#toBytes` (version de format en tête), items via `ItemStack.serializeItemsAsBytes` de Paper,
mis à jour automatiquement lors des montées de version de Minecraft.

## API (pour les autres plugins)

`fr.eternom.eterSync.api.SyncApi` (`SyncApi.get()`) : personne d'autre ne lit `etersync_snapshots`, et **personne
d'autre n'écrit un inventaire** (sinon, duplication).

- `isLoading(uuid)` : inventaire en cours de chargement, n'y touchez pas ;
- `lastInventory(uuid)` : copie du dernier inventaire enregistré (41 cases), en lecture seule (bloquant) — c'est ce que
  montrent `/invsee` d'EterEssential pour un joueur ailleurs et la fiche `/mod` ;
- `lastEnderChest(uuid)` : copie du dernier coffre de l'Ender enregistré (27 cases), en lecture seule (bloquant) — c'est ce
  que montre `/endersee` d'EterEssential pour un joueur ailleurs ;
- `openHistory(lecteur, pseudo)` : l'historique en lecture seule (`etersync.admin`).
