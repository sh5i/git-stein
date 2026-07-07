package jp.ac.titech.c.se.stein.historage;

/**
 * Computes the base file name (without extension or conflict index) of a Historage module from its
 * kind, simple name, and chain of enclosing modules.
 */
public interface NamingStrategy {
    String basename(Module module);
}
