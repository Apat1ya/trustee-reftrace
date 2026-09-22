package dev.reftrace.sitemap;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public final class SitemapParser {

    private SitemapParser() {
    }

    public static Sitemap parse(String name, byte[] body) {
        Element root = rootOf(name, body);
        String kind = nameOf(root);
        return switch (kind) {
            case "sitemapindex" -> new SitemapIndex(files(name, root));
            case "urlset" -> new UrlSet(pages(name, root));
            default -> throw SitemapFormatException.of(
                    name + " is not a sitemap: its root element is <" + kind + ">");
        };
    }

    private static Element rootOf(String name, byte[] body) {
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            builder.setErrorHandler(strict());
            Document document = builder.parse(new ByteArrayInputStream(body));
            return document.getDocumentElement();
        } catch (SAXException | IOException | ParserConfigurationException broken) {
            throw SitemapFormatException.withCause(name + " is not readable xml: " + broken.getMessage(), broken);
        }
    }

    private static List<URI> files(String name, Element root) {
        List<URI> files = new ArrayList<>();
        for (Element entry : children(root, "sitemap")) {
            files.add(location(name, entry));
        }
        return files;
    }

    private static List<URI> pages(String name, Element root) {
        List<URI> pages = new ArrayList<>();
        for (Element entry : children(root, "url")) {
            pages.add(location(name, entry));
        }
        return pages;
    }

    private static URI location(String name, Element entry) {
        String location = text(entry, "loc");
        if (location == null || location.isBlank()) {
            throw SitemapFormatException.of(name + " has an entry without a location");
        }
        try {
            URI uri = new URI(location);
            if (!uri.isAbsolute()) {
                throw SitemapFormatException.of(name + " lists a location that is not absolute: " + location);
            }
            return uri;
        } catch (URISyntaxException malformed) {
            throw SitemapFormatException.withCause(name + " lists a location that is not a url: " + location,
                    malformed);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && name.equals(nameOf(element))) {
                children.add(element);
            }
        }
        return children;
    }

    private static @Nullable String text(Element parent, String name) {
        List<Element> matches = children(parent, name);
        return matches.isEmpty() ? null : matches.getFirst().getTextContent().strip();
    }

    private static String nameOf(Element element) {
        return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static ErrorHandler strict() {
        return new ErrorHandler() {
            @Override
            public void warning(SAXParseException problem) throws SAXException {
                throw problem;
            }

            @Override
            public void error(SAXParseException problem) throws SAXException {
                throw problem;
            }

            @Override
            public void fatalError(SAXParseException problem) throws SAXException {
                throw problem;
            }
        };
    }
}
