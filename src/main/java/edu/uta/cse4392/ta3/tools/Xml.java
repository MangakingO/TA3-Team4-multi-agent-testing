package edu.uta.cse4392.ta3.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.InputSource;

/** XML reading for the JaCoCo and Surefire reports (external DTDs are never loaded). */
final class Xml {

    private Xml() {
    }

    static Document parse(Path file) throws Exception {
        return builder().parse(file.toFile());
    }

    static Document parseString(String xml) throws Exception {
        return builder().parse(new InputSource(new StringReader(xml)));
    }

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b;
    }

    /** The first direct child element with this tag, or null. */
    static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    /** The direct child elements with this tag. */
    static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }

    /** All descendant elements with this tag, in document order. */
    static List<Element> descendants(Element root, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList all = root.getElementsByTagName(tag);
        for (int i = 0; i < all.getLength(); i++) {
            out.add((Element) all.item(i));
        }
        return out;
    }

    static int intAttr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? 0 : Integer.parseInt(v.trim());
    }
}
