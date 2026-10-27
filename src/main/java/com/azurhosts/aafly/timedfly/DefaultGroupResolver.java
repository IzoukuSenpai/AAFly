package com.azurhosts.aafly.timedfly;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.hook.LuckPermsHook;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Résolution du groupe timed-fly : on parcourt les groupes <b>dans l'ordre de la configuration</b> et le
 * premier qui correspond gagne (SPEC §4 « TimedFlyService ») :
 * <ul>
 *   <li>LuckPerms présent et groupe primaire connu : seul le groupe dont le nom est ce groupe primaire
 *       correspond (les permissions {@code aafly.group.<nom>} sont alors ignorées) ;</li>
 *   <li>sinon : un groupe correspond si le joueur a la permission {@code aafly.group.<nom>}.
 *       {@code aafly.group.default} est déclarée {@code default: true} dans {@code paper-plugin.yml} : le
 *       groupe {@code default} s'applique donc à tous par défaut, et peut être retiré à un joueur.</li>
 * </ul>
 * Aucun joueur ne correspond → {@code timed-fly.no-group}.
 * Les noms de groupes sont validés par {@code ConfigManager} ({@code ^[a-z0-9_-]{1,32}$}) : aucune
 * donnée utilisateur n'entre dans le nœud de permission. Main thread uniquement.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class DefaultGroupResolver implements GroupResolver {

    private final @Nullable LuckPermsHook luckPerms;

    /**
     * @param luckPerms accroche LuckPerms, ou {@code null} si LuckPerms n'est pas installé
     */
    public DefaultGroupResolver(@Nullable LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    @Override
    public Optional<Settings.TimedFlyGroup> resolve(Player player, List<Settings.TimedFlyGroup> groups) {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        String primary = luckPerms == null ? null : luckPerms.primaryGroup(player).orElse(null);
        for (Settings.TimedFlyGroup group : groups) {
            String name = group.name();
            if (name.isEmpty()) {
                continue;
            }
            boolean matches = primary != null
                    ? name.equals(primary)
                    : player.hasPermission(GROUP_PERMISSION_PREFIX + name);
            if (matches) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }
}
