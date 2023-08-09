package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Mixin;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A cregit implementation.
 * This is based on the <a href="https://github.com/dmgerman/tokenizers">cregit tokenizer</a>.
 */
@Slf4j
@ToString
@Command(name = "@cregit", description = "cregit format via srcML")
public class Cregit implements BlobTranslator {
    public static final String CREGIT_VERSION = "0.0.1";

    /**
     * Defined based on <a href="https://github.com/srcML/srcML/blob/master/src/libsrcml/language_extension_registry.cpp">srcML extension list</a>
     */
    public static final String[] JAVA_EXT = {"*.java", "*.aj", "*.mjava", "*.fjava", "*.cjava"}; // @historage-jdt extensions added
    public static final String[] C_EXT = {"*.c", "*.h", "*.i"};
    public static final String[] CXX_EXT = {"*.cpp", "*.CPP", "*.cp", "*.hpp", "*.cxx", "*.hxx", "*.cc", "*.hh", "*.c++", "*.h++", "*.C", "*.H", "*.tcc", "*.ii"};
    public static final String[] CSHARP_EXT = {"*.cs"};

    public static final NameFilter JAVA_FILTER = new NameFilter(false, JAVA_EXT);
    public static final NameFilter C_FILTER = new NameFilter(false, C_EXT);
    public static final NameFilter CXX_FILTER = new NameFilter(false, CXX_EXT);
    public static final NameFilter CSHARP_FILTER = new NameFilter(false, CSHARP_EXT);

    @Option(names = "--srcml", description = "srcml command path")
    protected String srcml = "srcml";

    @Mixin
    private final NameFilter filter = new NameFilter();

    @SuppressWarnings("unused")
    @Option(names = {"-l", "--lang"}, description = "target language: either of 'C', 'C++', 'C#', 'Java'")
    protected void setLanguage(final String language) {
        this.language = language;
        if (filter.isDefault()) {
            switch (language) {
                case "C":
                    filter.setPatterns(C_EXT);
                    break;
                case "C++":
                    filter.setPatterns(CXX_EXT);
                    break;
                case "C#":
                    filter.setPatterns(CSHARP_EXT);
                    break;
                case "Java":
                    filter.setPatterns(JAVA_EXT);
                    break;
                default:
                    log.error("Unknown language: {}", language);
            }
        }
    }
    protected String language;

    @Override
    public HotEntry rewriteBlobEntry(HotEntry.Single entry, Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }

        String lang = language;
        if (lang == null) {
            lang = guessLanguage(entry);
            if (lang == null) {
                return entry;
            }
        }

        log.debug("Generate cregit module for {} as {} language {}", entry, lang, c);

        final String[] cmd = { srcml, "--language", lang };
        try (final ProcessRunner proc = new ProcessRunner(cmd, entry.getBlob(), c)) {
            final InputSource input = new InputSource(new ByteArrayInputStream(proc.getResult()));
            final SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            final Handler handler = new Handler();
            parser.parse(input, handler);
            return entry.update(handler.getResult());
        } catch (final IOException | ParserConfigurationException | SAXException e) {
            log.error(e.getMessage(), e);
            return entry;
        }
    }

    protected String guessLanguage(HotEntry.Single entry) {
        final File file = new File(entry.getName());
        if (JAVA_FILTER.accept(file)) {
            return "Java";
        }
        if (C_FILTER.accept(file)) {
            return "C";
        }
        if (CXX_FILTER.accept(file)) {
            return "C++";
        }
        if (CSHARP_FILTER.accept(file)) {
            return "C#";
        }
        return null;
    }

    static class Handler extends DefaultHandler {
        String content = "";

        final Stack<String> elements = new Stack<>();

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        final PrintStream out = new PrintStream(buffer, false, StandardCharsets.UTF_8);

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
             if (elements.size() <= 1)  {
                final String revision = attributes.getValue("revision");
                final String language = attributes.getValue("language");
                if (qName.equals("unit") && revision != null && language != null) {
                    out.println("begin_unit|" +
                                    "revision:" + revision + ";" +
                                    "language:" + language + ";" +
                                    "cregit-version:" + CREGIT_VERSION);
                } else {
                    out.println("begin_" + qName);
                }
            }

            if (!content.isEmpty()) {
                out.println(content);
                content = "";
            }
            elements.push(qName);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!content.isEmpty()) {
                out.println(content);
                content = "";
            }
            elements.pop();
            if (elements.size() <= 1)  {
                out.println("end_" + qName);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            String s = new String(ch, start, length).trim().replace('\n', ' ');
            if (!s.isEmpty()) {
                if (content.isEmpty()) {
                    content = elements.peek() + "|";
                }
                content += s;
            }
        }

        public byte[] getResult() {
            out.flush();
            return buffer.toByteArray();
        }
    }
}
