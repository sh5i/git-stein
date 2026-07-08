package jp.ac.titech.c.se.stein.ts;

/**
 * A single leaf token of a tree-sitter parse: its text, its grammar-derived type (the same typing the
 * FinerGit token sequence uses), its 1-based start line and column, and its byte offset in the source.
 * Consumed by cregit-style token-per-line output.
 */
public record Token(String text, String type, int line, int column, int start) {
}
