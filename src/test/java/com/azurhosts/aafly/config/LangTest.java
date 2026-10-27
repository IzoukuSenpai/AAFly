package com.azurhosts.aafly.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

class LangTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static Lang lang() throws SerializationException, ConfigException {
        ConfigurationNode root = BasicConfigurationNode.root();
        root.node("prefix").set("&8[&bAAFly&8] ");
        root.node("fly", "enabled").set("<prefix>Vol activé pour <player>");
        root.node("help", "player").setList(String.class, java.util.List.of("ligne 1", "ligne 2"));
        return new Lang(MessagesConfig.from(root), Logger.getLogger("AAFly-test"));
    }

    @Test
    void conversionLegacy() {
        assertEquals("<green>Salut", Lang.legacyToMiniMessage("&aSalut"));
        assertEquals("<#ff00aa>Rose", Lang.legacyToMiniMessage("&#FF00AARose"));
        assertEquals("<#112233>x", Lang.legacyToMiniMessage("&x&1&1&2&2&3&3x"));
        assertEquals("<bold><red>!", Lang.legacyToMiniMessage("§l§c!"));
        assertEquals("Tom & Jerry", Lang.legacyToMiniMessage("Tom & Jerry"));
        assertEquals("<yellow>a", Lang.legacyToMiniMessage("<yellow>a"));
    }

    @Test
    void prefixeEtPlaceholderNonInterprete() throws Exception {
        Lang lang = lang();
        Component c = lang.msg("fly.enabled", Lang.unparsed("player", "<red>Hacker</red><click:run_command:'/op x'>"));
        String text = plain(c);
        assertTrue(text.startsWith("[AAFly] Vol activé pour "), text);
        // La valeur dynamique apparaît littéralement : aucune balise n'est interprétée.
        assertTrue(text.endsWith("<red>Hacker</red><click:run_command:'/op x'>"), text);
    }

    @Test
    void cleAbsenteEtListe() throws Exception {
        Lang lang = lang();
        assertEquals("cle.inconnue", plain(lang.msg("cle.inconnue")));
        assertEquals(2, lang.lines("help.player").size());
        assertEquals("ligne 1\nligne 2", plain(lang.msg("help.player")));
    }
}
