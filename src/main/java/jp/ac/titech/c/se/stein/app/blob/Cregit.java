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

@Slf4j
@ToString
@Command(name = "@cregit", description = "cregit format via srcML")
public class Cregit implements BlobTranslator {
    public static final String CREGIT_VERSION = "0.0.1";

    @Option(names = "--srcml", description = "srcml command path")
    protected String srcml = "srcml";

    @Mixin
    private final NameFilter filter = new NameFilter();

    @SuppressWarnings("unused")
    @Option(names = {"-l", "--lang"}, description = "target language: either of 'C', 'C++', 'C#', 'Java'",
            required = true)
    protected void setLanguage(final String language) {
        this.language = language;
        if (filter.isDefault()) {
            switch (language) {
                case "C":
                    filter.setPatterns("*.c", "*.h");
                    break;
                case "C++":
                    filter.setPatterns("*.cpp", "*.hpp", "*.cc", "*.hh", "*.cxx", "*.hxx", "*.c", "*.h");
                    break;
                case "C#":
                    filter.setPatterns("*.cs");
                    break;
                case "Java":
                    filter.setPatterns("*.java");
                    break;
                default:
                    log.error("Unknown language: {}", language);
            }
        }
    }
    protected String language = "Java";

    @Override
    public HotEntry rewriteBlobEntry(HotEntry.SingleHotEntry entry, Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        final String[] cmd = { srcml, "--language", language };
        try (final ProcessRunner proc = new ProcessRunner(cmd, entry.getBlob(), c)) {
            final InputSource input = new InputSource(proc.getResult());
            final SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            final Handler handler = new Handler();
            parser.parse(input, handler);
            return entry.update(handler.getResult());
        } catch (final IOException | ParserConfigurationException | SAXException e) {
            log.error("Error: {} {}", e, c);
            return entry;
        }
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
