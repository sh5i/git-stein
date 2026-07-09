package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

/**
 * The naming material an analyzer extracts for a source element: the raw parts a Historage naming
 * strategy needs to build a file name, without assembling them itself. The analyzer's job ends at
 * producing these parts (each already made file-name-safe for its language); composing them into a
 * leaf name — joining and parenthesizing the parameters, prefixing the type parameters, digesting or
 * unqualifying — is the naming strategy's job (see
 * {@link jp.ac.titech.c.se.stein.app.blob.Historage.NamingStrategy#leafName}).
 *
 * <p>{@code name} is the element's simple (or, where a language qualifies it, qualified) name.
 * {@code typeParameters} is the generic type parameters, or null when the element has none (only Java
 * and C# use them). {@code parameters} is the per-parameter discriminators a language distinguishes
 * overloads by — the parameter types for a statically typed language, the parameter names for a
 * dynamically typed one — and distinguishes three cases: null means the name carries no parenthesized
 * parameter list at all (a class or field, or a method whose name already encodes its arguments such
 * as an Objective-C selector); the empty list means an empty list, rendered {@code ()}; a non-empty
 * list is the parameter list.</p>
 */
public record Signature(String name, List<String> typeParameters, List<String> parameters) {
    /**
     * A bare name with no type parameters and no parameter list (a class, a field, or a method whose
     * name already encodes its signature).
     */
    public static Signature of(final String name) {
        return new Signature(name, null, null);
    }
}
