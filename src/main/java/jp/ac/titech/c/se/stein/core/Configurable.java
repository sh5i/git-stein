package jp.ac.titech.c.se.stein.core;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

public interface Configurable {
    void addOptions(final Options opts);

    void configure(final CommandLine cmd);
}
