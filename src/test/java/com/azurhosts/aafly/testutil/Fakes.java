package com.azurhosts.aafly.testutil;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * Doublures de test par {@link Proxy} (aucune dépendance de test supplémentaire) : chaque appel est journalisé,
 * les méthodes sans réponse configurée renvoient une valeur neutre (faux, 0, chaîne vide, {@code null}).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class Fakes {

    private Fakes() {
    }

    /**
     * @param type    interface simulée
     * @param answers réponses par nom de méthode
     * @param calls   journal des noms de méthodes appelées (peut être {@code null})
     * @param <T>     type
     * @return doublure
     */
    public static <T> T proxy(Class<T> type, Map<String, Function<Object[], Object>> answers,
                              List<String> calls) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, m, args) -> {
            Object[] a = args == null ? new Object[0] : args;
            switch (m.getName()) {
                case "equals" -> {
                    if (a.length == 1 && m.getParameterTypes()[0] == Object.class) {
                        return self == a[0];
                    }
                }
                case "hashCode" -> {
                    if (a.length == 0) {
                        return System.identityHashCode(self);
                    }
                }
                case "toString" -> {
                    if (a.length == 0) {
                        return "Fake" + type.getSimpleName();
                    }
                }
                default -> {
                }
            }
            if (calls != null) {
                calls.add(m.getName());
            }
            Function<Object[], Object> answer = answers.get(m.getName());
            return answer != null ? answer.apply(a) : neutral(m);
        });
        return type.cast(proxy);
    }

    private static Object neutral(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) {
            return false;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == double.class) {
            return 0D;
        }
        if (r == float.class) {
            return 0F;
        }
        if (r == short.class) {
            return (short) 0;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        if (r == char.class) {
            return (char) 0;
        }
        if (r == String.class) {
            return "";
        }
        if (r == List.class || r == java.util.Collection.class) {
            return List.of();
        }
        return null;
    }

    /**
     * Joueur simulé à état mutable (vol, mode de jeu, PDC).
     */
    public static final class FakePlayer {
        public final UUID uuid = UUID.randomUUID();
        public final String name;
        public final AtomicBoolean allowFlight = new AtomicBoolean();
        public final AtomicBoolean flying = new AtomicBoolean();
        public final AtomicBoolean online = new AtomicBoolean(true);
        public final AtomicReference<GameMode> gameMode = new AtomicReference<>(GameMode.SURVIVAL);
        public final Map<NamespacedKey, Object> pdc = new HashMap<>();
        public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        public final Player player;

        /**
         * @param name pseudo
         */
        public FakePlayer(String name) {
            this.name = name;
            PersistentDataContainer container = proxy(PersistentDataContainer.class, Map.of(
                    "has", a -> pdc.containsKey((NamespacedKey) a[0]),
                    "set", a -> {
                        pdc.put((NamespacedKey) a[0], a[2]);
                        return null;
                    },
                    "remove", a -> {
                        pdc.remove((NamespacedKey) a[0]);
                        return null;
                    },
                    "get", a -> pdc.get((NamespacedKey) a[0])), null);
            Location location = new Location(null, 0.5D, 64D, 0.5D);
            Map<String, Function<Object[], Object>> answers = new HashMap<>();
            answers.put("getUniqueId", a -> uuid);
            answers.put("getName", a -> name);
            answers.put("getAllowFlight", a -> allowFlight.get());
            answers.put("setAllowFlight", a -> {
                allowFlight.set((Boolean) a[0]);
                if (!(Boolean) a[0]) {
                    flying.set(false);
                }
                return null;
            });
            answers.put("isFlying", a -> flying.get());
            answers.put("setFlying", a -> {
                flying.set((Boolean) a[0]);
                return null;
            });
            answers.put("isOnline", a -> online.get());
            answers.put("getGameMode", a -> gameMode.get());
            answers.put("getPersistentDataContainer", a -> container);
            answers.put("getLocation", a -> location.clone());
            this.player = proxy(Player.class, answers, calls);
        }

        /**
         * @param method nom de méthode
         * @return nombre d'appels
         */
        public long count(String method) {
            synchronized (calls) {
                return calls.stream().filter(method::equals).count();
            }
        }
    }
}
