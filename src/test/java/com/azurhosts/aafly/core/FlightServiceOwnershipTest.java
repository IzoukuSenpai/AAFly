package com.azurhosts.aafly.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.action.ActionExecutor;
import com.azurhosts.aafly.action.TimeThresholdNotifier;
import com.azurhosts.aafly.api.ThreatResolver;
import com.azurhosts.aafly.config.ConfigManager;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import com.azurhosts.aafly.rules.RuleEngine;
import com.azurhosts.aafly.storage.StorageManager;
import com.azurhosts.aafly.testutil.Fakes;
import com.azurhosts.aafly.testutil.Fakes.FakePlayer;
import com.azurhosts.aafly.testutil.TestServer;
import com.azurhosts.aafly.time.TimeFormatter;
import com.azurhosts.aafly.timedfly.TimedFlyService;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coexistence (SPEC-phase2 B3) côté {@link DefaultFlightService} : AAFly ne retire que le vol qu'il a accordé,
 * y compris à la reconnexion (marqueur persistant {@code aafly:owns_flight}).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
class FlightServiceOwnershipTest {

    private static final Logger LOG = Logger.getLogger("FlightServiceOwnershipTest");
    private static final Settings SETTINGS = new Settings(1, false, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    @TempDir
    Path dir;

    private TestServer server;
    private final Map<UUID, FlightProfile> profiles = new HashMap<>();
    private int landings;
    private DefaultFlightService service;

    @BeforeEach
    void setUp() throws Exception {
        server = TestServer.install();
        ConfigManager cm = new ConfigManager(dir, LOG,
                name -> FlightServiceOwnershipTest.class.getClassLoader().getResourceAsStream(name));
        cm.load();
        Lang lang = new Lang(cm.messages(), LOG);
        StorageManager storage = Fakes.proxy(StorageManager.class, Map.of(
                "cached", a -> profiles.get((UUID) a[0])), null);
        SafeLanding landing = Fakes.proxy(SafeLanding.class, Map.of("land", a -> {
            landings++;
            return true;
        }), null);
        ThreatResolver fallback = Fakes.proxy(ThreatResolver.class, Map.of(), null);
        TimeFormatter formatter = new TimeFormatter(Settings.FormatSection.defaults());
        service = new DefaultFlightService(LOG, () -> SETTINGS, lang, () -> formatter, storage,
                Fakes.proxy(RuleEngine.class, Map.of(), null), PlaceholderBridge.disabled(),
                Fakes.proxy(ActionExecutor.class, Map.of(), null),
                Fakes.proxy(TimeThresholdNotifier.class, Map.of(), null),
                Fakes.proxy(TimedFlyService.class, Map.of(), null), landing,
                Fakes.proxy(FlySpeedService.class, Map.of(), null),
                new NearbyEnemyDetector(() -> SETTINGS, new ThreatResolverRegistry(LOG), fallback));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private FakePlayer flyingThirdParty(String name) {
        FakePlayer p = new FakePlayer(name);
        p.allowFlight.set(true); // vol accordé par un autre plugin (AAItems…)
        p.flying.set(true);
        server.join(p.player);
        return p;
    }

    private FlightProfile profile(FakePlayer p, boolean enabled, long seconds) {
        FlightProfile profile = FlightProfile.createNew(p.uuid, p.name, 0.1D, System.currentTimeMillis() / 1000L);
        profile.setFlightEnabled(enabled);
        profile.setRemainingSeconds(seconds);
        profiles.put(p.uuid, profile);
        return profile;
    }

    @Test
    void stopFlightLeavesThirdPartyFlightUntouched() {
        FakePlayer p = flyingThirdParty("Other");
        service.stopFlight(p.player);
        assertTrue(p.allowFlight.get());
        assertTrue(p.flying.get());
        assertEquals(0L, p.count("setAllowFlight"));
        assertEquals(0L, p.count("setFlying"));
        assertEquals(0, landings);
    }

    @Test
    void revokeOwnedFlightsSparesThirdPartyFlights() {
        FakePlayer owned = new FakePlayer("Owned");
        server.join(owned.player);
        service.startFlight(owned.player, profile(owned, true, 100L));
        assertTrue(service.ownsFlight(owned.uuid));
        assertTrue(owned.pdc.containsKey(DefaultFlightService.OWNS_FLIGHT_KEY), "marqueur persistant posé");
        FakePlayer other = flyingThirdParty("Other");

        assertEquals(1, service.revokeOwnedFlights());
        assertFalse(owned.allowFlight.get());
        assertFalse(owned.pdc.containsKey(DefaultFlightService.OWNS_FLIGHT_KEY));
        assertTrue(other.allowFlight.get());
        assertTrue(other.flying.get());
        assertEquals(0L, other.count("setAllowFlight"));
    }

    @Test
    void reconnectRevokesMarkedMayflyNoLongerWanted() {
        // Vol AAFly sauvegardé actif (mayfly + marqueur) mais coupé ailleurs : flight_enabled=false en base.
        FakePlayer p = flyingThirdParty("Pilot");
        p.pdc.put(DefaultFlightService.OWNS_FLIGHT_KEY, (byte) 1);
        service.restore(p.player, profile(p, false, 500L));
        assertFalse(p.allowFlight.get(), "aucun vol gratuit permanent");
        assertFalse(p.flying.get());
        assertFalse(p.pdc.containsKey(DefaultFlightService.OWNS_FLIGHT_KEY));
        assertFalse(service.ownsFlight(p.uuid));
        assertEquals(1, landings, "atterrissage sécurisé");
    }

    @Test
    void reconnectNeverTouchesUnmarkedThirdPartyFlight() {
        // Vol voulu mais impossible à restaurer (plus de temps), mayfly posé par AAItems : AAFly n'y touche pas.
        FakePlayer p = flyingThirdParty("Pilot");
        service.restore(p.player, profile(p, true, 0L));
        assertTrue(p.allowFlight.get());
        assertTrue(p.flying.get());
        assertEquals(0L, p.count("setAllowFlight"));
        assertFalse(service.ownsFlight(p.uuid));

        FakePlayer q = flyingThirdParty("Other");
        service.restore(q.player, profile(q, false, 100L));
        assertTrue(q.allowFlight.get());
        assertEquals(0L, q.count("setAllowFlight"));
    }

    @Test
    void lostFlightReleasesPersistentMarker() {
        FakePlayer p = new FakePlayer("Pilot");
        server.join(p.player);
        service.startFlight(p.player, profile(p, true, 100L));
        p.allowFlight.set(false); // retiré par un tiers
        service.releaseLostFlight(p.player);
        assertFalse(service.ownsFlight(p.uuid));
        assertFalse(p.pdc.containsKey(DefaultFlightService.OWNS_FLIGHT_KEY));
    }
}
