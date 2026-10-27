package com.azurhosts.aafly.apiimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.api.AAFlyApi;
import com.azurhosts.aafly.api.AAFlyProvider;
import com.azurhosts.aafly.api.GrantMode;
import com.azurhosts.aafly.api.GrantRequest;
import com.azurhosts.aafly.api.GrantResult;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.ThreatResolverRegistry;
import com.azurhosts.aafly.core.TimeChangeResult;
import com.azurhosts.aafly.core.ToggleResult;
import com.azurhosts.aafly.rules.ConditionRegistry;
import com.azurhosts.aafly.testutil.Fakes;
import com.azurhosts.aafly.testutil.Fakes.FakePlayer;
import com.azurhosts.aafly.testutil.TestServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultAAFlyApi} (SPEC-phase2 B2/B3) avec un {@link FlightService} simulé : crédit puis activation,
 * événement annulé, main thread, sources, joueur non chargé, {@link AAFlyProvider}.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
class DefaultAAFlyApiTest {

    private static final Logger LOG = Logger.getLogger("DefaultAAFlyApiTest");
    private static final Settings SETTINGS = new Settings(1, false, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    private TestServer server;
    private FakePlayer fake;
    private FlightProfile profile;
    private boolean owns;
    /** Comportement de service.enable (par défaut : ENABLED). */
    private Function<Object[], Object> enable;
    /** Statut renvoyé par service.setTime : OK applique la valeur, CANCELLED ne change rien. */
    private TimeChangeResult.Status setTimeStatus;
    private final List<String> calls = new ArrayList<>();
    private DefaultAAFlyApi api;

    @BeforeEach
    void setUp() {
        server = TestServer.install();
        fake = new FakePlayer("Pilot");
        server.join(fake.player);
        profile = FlightProfile.createNew(fake.uuid, "Pilot", 0.1D, 1L);
        owns = true;
        enable = a -> ToggleResult.ENABLED;
        setTimeStatus = TimeChangeResult.Status.OK;
        Map<String, Function<Object[], Object>> answers = new HashMap<>();
        answers.put("profile", a -> a[0].equals(fake.uuid) && fake.online.get() ? profile : null);
        answers.put("ownsFlight", a -> owns);
        answers.put("enable", a -> enable.apply(a));
        answers.put("disable", a -> ToggleResult.DISABLED);
        answers.put("isSuspendedByRule", a -> profile.isSuspended());
        answers.put("setTime", a -> {
            long old = profile.remainingSeconds();
            if (setTimeStatus == TimeChangeResult.Status.CANCELLED) {
                return CompletableFuture.completedFuture(new TimeChangeResult(setTimeStatus, fake.uuid, old, old,
                        profile));
            }
            profile.setRemainingSeconds((Long) a[1]);
            return CompletableFuture.completedFuture(new TimeChangeResult(setTimeStatus, fake.uuid, old,
                    profile.remainingSeconds(), profile));
        });
        FlightService service = Fakes.proxy(FlightService.class, answers, calls);
        ConditionRegistry conditions = Fakes.proxy(ConditionRegistry.class, Map.of(), null);
        api = new DefaultAAFlyApi(LOG, service, conditions, new ThreatResolverRegistry(LOG), () -> null,
                () -> SETTINGS, () -> true);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static GrantRequest extend(long seconds) {
        return new GrantRequest(seconds, GrantMode.EXTEND, 7200L, true, "aaitems");
    }

    @Test
    void grantReactivatesFlightLostToThirdParty() {
        // Vol AAFly voulu et non suspendu, mais un autre plugin a retiré allowFlight : AAFly ne le possède plus.
        profile.setFlightEnabled(true);
        owns = false;
        assertFalse(api.isFlightEnabled(fake.uuid));
        GrantResult r = api.grant(fake.player, extend(600L));
        assertEquals(GrantResult.Status.GRANTED, r.status());
        assertEquals(600L, r.remainingSeconds());
        assertTrue(calls.contains("enable"), "le vol doit être réactivé");
    }

    @Test
    void grantAlwaysDelegatesActivationToService() {
        profile.setFlightEnabled(true);
        enable = a -> ToggleResult.ALREADY; // vol réellement actif et possédé
        assertTrue(api.isFlightEnabled(fake.uuid));
        assertEquals(GrantResult.Status.GRANTED, api.grant(fake.player, extend(60L)).status());
        assertTrue(calls.contains("enable"));
    }

    @Test
    void exceptionDuringActivationKeepsGrantedStatus() {
        enable = a -> {
            throw new IllegalStateException("panne simulée");
        };
        GrantResult r = api.grant(fake.player, extend(300L));
        assertEquals(GrantResult.Status.GRANTED, r.status());
        assertEquals(300L, profile.remainingSeconds(), "le crédit est acquis");
    }

    @Test
    void activationDeniedByRuleKeepsCredit() {
        enable = a -> ToggleResult.DENIED_BY_RULE;
        GrantResult r = api.grant(fake.player, extend(120L));
        assertEquals(GrantResult.Status.GRANTED, r.status());
        assertEquals(120L, profile.remainingSeconds());
    }

    @Test
    void cancelledTimeChangeIsDeniedWithoutCreditNorActivation() {
        setTimeStatus = TimeChangeResult.Status.CANCELLED;
        GrantResult r = api.grant(fake.player, extend(120L));
        assertEquals(GrantResult.Status.DENIED_BY_RULE, r.status());
        assertEquals(0L, profile.remainingSeconds());
        assertFalse(calls.contains("enable"));
    }

    @Test
    void offlinePlayerIsNotLoaded() {
        fake.online.set(false);
        assertEquals(GrantResult.Status.NOT_LOADED, api.grant(fake.player, extend(120L)).status());
        assertFalse(calls.contains("setTime"));
    }

    @Test
    void invalidSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> api.enable(fake.player, "Bad Source!"));
        assertThrows(IllegalArgumentException.class, () -> api.disable(fake.player, "x".repeat(33)));
        assertTrue(api.enable(fake.player, "aaitems"));
        assertTrue(api.disable(fake.player, "aaitems"));
    }

    @Test
    void offMainThreadCallsAreRefused() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                api.grant(fake.player, extend(60L));
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        t.join();
        assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
        assertEquals(0L, profile.remainingSeconds());
    }

    @Test
    void providerIgnoresForeignRegistrations() {
        AAFlyApi foreign = Fakes.proxy(AAFlyApi.class, Map.of(), null);
        server.registrations.add(new RegisteredServiceProvider<>(AAFlyApi.class, foreign, ServicePriority.Highest,
                server.plugin("Imposteur", true, null)));
        assertThrows(IllegalStateException.class, AAFlyProvider::get);
        server.registrations.add(new RegisteredServiceProvider<>(AAFlyApi.class, api, ServicePriority.Normal,
                server.plugin(AAFlyProvider.PLUGIN_NAME, true, null)));
        assertSame(api, AAFlyProvider.get());
    }
}
