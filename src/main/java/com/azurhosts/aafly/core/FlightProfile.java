package com.azurhosts.aafly.core;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Profil de vol d'un joueur.
 *
 * <p><b>Confinement :</b> instance mutable, lue et modifiée <b>uniquement sur le main thread</b>.
 * Le stockage ne manipule que des {@link FlightProfileSnapshot} immuables obtenus via {@link #snapshot()}.
 * Toute modification d'un champ persistant positionne le drapeau {@code dirty}.
 *
 * <p>Les champs « runtime » (suspension, immobilité, seuils notifiés) ne sont jamais persistés.
 */
public final class FlightProfile {

    /** Vitesse minimale autorisée. */
    public static final double MIN_SPEED = 0.1D;
    /** Vitesse maximale autorisée. */
    public static final double MAX_SPEED = 1.0D;
    /** Motif d'un pseudo Minecraft valide. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final UUID uuid;
    private String lastKnownName;
    private long remainingSeconds;
    private boolean flightEnabled;
    private long lastUpdateEpochSeconds;
    private long cooldownUntilEpochSeconds;
    private double flySpeed;
    private boolean dirty;

    // --- état runtime (non persisté) ---
    private @Nullable DisableCause suspendedBy;
    private long lastMoveEpochMillis;
    private long lastNotifiedThreshold = -1L;

    /**
     * @param uuid                      identifiant
     * @param lastKnownName             pseudo (remplacé par "" s'il est invalide)
     * @param remainingSeconds          temps restant (borné à ≥ 0)
     * @param flightEnabled             vol voulu
     * @param lastUpdateEpochSeconds    dernière mise à jour
     * @param cooldownUntilEpochSeconds fin de recharge timed-fly
     * @param flySpeed                  vitesse (bornée à [0.1, 1.0])
     */
    public FlightProfile(UUID uuid, String lastKnownName, long remainingSeconds, boolean flightEnabled,
                         long lastUpdateEpochSeconds, long cooldownUntilEpochSeconds, double flySpeed) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.lastKnownName = sanitizeName(lastKnownName);
        this.remainingSeconds = Math.max(0L, remainingSeconds);
        this.flightEnabled = flightEnabled;
        this.lastUpdateEpochSeconds = lastUpdateEpochSeconds;
        this.cooldownUntilEpochSeconds = Math.max(0L, cooldownUntilEpochSeconds);
        this.flySpeed = clampSpeed(flySpeed);
        this.lastMoveEpochMillis = System.currentTimeMillis();
    }

    /**
     * Crée un profil vierge (nouveau joueur), marqué {@code dirty}.
     *
     * @param uuid         identifiant
     * @param name         pseudo
     * @param defaultSpeed vitesse par défaut ({@code flight.default-speed})
     * @param nowEpochSeconds instant présent
     * @return nouveau profil
     */
    public static FlightProfile createNew(UUID uuid, String name, double defaultSpeed, long nowEpochSeconds) {
        FlightProfile p = new FlightProfile(uuid, name, 0L, false, nowEpochSeconds, 0L, defaultSpeed);
        p.dirty = true;
        return p;
    }

    /**
     * Reconstruit un profil depuis le stockage (non {@code dirty}).
     *
     * @param s instantané chargé
     * @return profil
     */
    public static FlightProfile fromSnapshot(FlightProfileSnapshot s) {
        return new FlightProfile(s.uuid(), s.lastKnownName(), s.remainingSeconds(), s.flightEnabled(),
                s.lastUpdateEpochSeconds(), s.cooldownUntilEpochSeconds(), s.flySpeed());
    }

    /**
     * @return copie immuable des champs persistants
     */
    public FlightProfileSnapshot snapshot() {
        return new FlightProfileSnapshot(uuid, lastKnownName, remainingSeconds, flightEnabled,
                lastUpdateEpochSeconds, cooldownUntilEpochSeconds, flySpeed);
    }

    /**
     * Borne une vitesse dans {@code [MIN_SPEED, MAX_SPEED]} ; NaN/infini → {@code MIN_SPEED}.
     *
     * @param speed vitesse brute
     * @return vitesse bornée
     */
    public static double clampSpeed(double speed) {
        if (!Double.isFinite(speed)) {
            return MIN_SPEED;
        }
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    private static String sanitizeName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches() ? name : "";
    }

    // --- accesseurs persistants ---

    public UUID uuid() {
        return uuid;
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    /**
     * Met à jour le pseudo (ignoré s'il est invalide).
     *
     * @param name nouveau pseudo
     */
    public void setLastKnownName(String name) {
        String clean = sanitizeName(name);
        if (!clean.isEmpty() && !clean.equals(lastKnownName)) {
            lastKnownName = clean;
            dirty = true;
        }
    }

    public long remainingSeconds() {
        return remainingSeconds;
    }

    /**
     * Fixe le temps restant (borné à ≥ 0). Le plafond {@code limits.max-seconds} est appliqué par
     * {@link FlightService} via {@code TimeMath}.
     *
     * @param seconds nouvelle valeur
     */
    public void setRemainingSeconds(long seconds) {
        long v = Math.max(0L, seconds);
        if (v != remainingSeconds) {
            remainingSeconds = v;
            dirty = true;
        }
    }

    public boolean flightEnabled() {
        return flightEnabled;
    }

    public void setFlightEnabled(boolean enabled) {
        if (enabled != flightEnabled) {
            flightEnabled = enabled;
            dirty = true;
        }
    }

    public long lastUpdateEpochSeconds() {
        return lastUpdateEpochSeconds;
    }

    /**
     * Horodatage utilisé pour le décompte hors-ligne. Ne marque pas {@code dirty} à lui seul
     * (il est écrit avec les autres champs lors du prochain flush ou à la déconnexion).
     *
     * @param epochSeconds instant
     */
    public void setLastUpdateEpochSeconds(long epochSeconds) {
        lastUpdateEpochSeconds = epochSeconds;
    }

    public long cooldownUntilEpochSeconds() {
        return cooldownUntilEpochSeconds;
    }

    public void setCooldownUntilEpochSeconds(long epochSeconds) {
        long v = Math.max(0L, epochSeconds);
        if (v != cooldownUntilEpochSeconds) {
            cooldownUntilEpochSeconds = v;
            dirty = true;
        }
    }

    public double flySpeed() {
        return flySpeed;
    }

    /**
     * @param speed nouvelle vitesse (bornée à [0.1, 1.0])
     */
    public void setFlySpeed(double speed) {
        double v = clampSpeed(speed);
        if (Double.compare(v, flySpeed) != 0) {
            flySpeed = v;
            dirty = true;
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Force le marquage {@code dirty}. */
    public void markDirty() {
        dirty = true;
    }

    /** Efface le drapeau {@code dirty} (appelé par le stockage après capture du snapshot). */
    public void clearDirty() {
        dirty = false;
    }

    // --- état runtime ---

    /**
     * @return la cause de suspension automatique en cours ({@link DisableCause#RULE} ou
     *         {@link DisableCause#NEARBY}), ou {@code null}
     */
    public @Nullable DisableCause suspendedBy() {
        return suspendedBy;
    }

    public void setSuspendedBy(@Nullable DisableCause cause) {
        this.suspendedBy = cause;
    }

    public boolean isSuspended() {
        return suspendedBy != null;
    }

    /**
     * @return dernier mouvement de bloc détecté (epoch, millisecondes), alimenté par le MoveListener
     */
    public long lastMoveEpochMillis() {
        return lastMoveEpochMillis;
    }

    public void setLastMoveEpochMillis(long epochMillis) {
        this.lastMoveEpochMillis = epochMillis;
    }

    /**
     * @return dernier seuil {@code commands-time-remaining} déclenché (-1 si aucun)
     */
    public long lastNotifiedThreshold() {
        return lastNotifiedThreshold;
    }

    public void setLastNotifiedThreshold(long threshold) {
        this.lastNotifiedThreshold = threshold;
    }

    @Override
    public String toString() {
        return "FlightProfile[" + uuid + ", " + lastKnownName + ", remaining=" + remainingSeconds
                + ", enabled=" + flightEnabled + ", dirty=" + dirty + "]";
    }
}
