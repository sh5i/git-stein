package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.rewriter.RewriterCommand;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

public class LoaderTest {
    @Test
    public void testLoadClass() {
        // fully qualified name
        assertEquals(Identity.class, Loader.loadClass("jp.ac.titech.c.se.stein.app.Identity"));

        // short name (resolved against built-in packages)
        assertEquals(Identity.class, Loader.loadClass("Identity"));

        // non-existent
        assertNull(Loader.loadClass("NonExistentClass"));
    }

    @Test
    public void testNewInstance() {
        assertInstanceOf(Identity.class, Loader.newInstance(Identity.class));
    }

    @Test
    public void testEnumerateCommands() {
        final Collection<Class<? extends RewriterCommand>> commands =
                Loader.enumerateCommands("jp.ac.titech.c.se.stein.app", true);
        assertFalse(commands.isEmpty());
        assertTrue(commands.contains(Identity.class));
    }
}
