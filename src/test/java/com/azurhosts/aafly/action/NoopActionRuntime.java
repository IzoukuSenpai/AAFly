package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.Optional;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

/** Runtime de test : l'analyse ne doit jamais l'appeler. */
public final class NoopActionRuntime implements ActionRuntime {

    @Override
    public Lang lang() {
        throw new UnsupportedOperationException();
    }

    @Override
    public PlaceholderBridge placeholders() {
        return PlaceholderBridge.disabled();
    }

    @Override
    public Logger logger() {
        return Logger.getLogger("AAFly-test");
    }

    @Override
    public void dispatchConsole(String command) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isPrimaryThread() {
        return true;
    }

    @Override
    public void runOnMain(Runnable task) {
        task.run();
    }

    @Override
    public Optional<Key> resolveSoundName(String enumName) {
        return Optional.empty();
    }

    @Override
    public String remaining(Player player) {
        return null;
    }

    @Override
    public void warnOnce(String key, String message) {
    }
}
