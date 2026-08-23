import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Independent OOXML reader used by scripts/verify.sh. */
public final class VerifyXlsx {

    private static final String OFFICE_REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private VerifyXlsx() {}

    public static void main(String[] args) {
        try {
            Arguments arguments = Arguments.parse(args);
            WorkbookData workbook = read(arguments.workbook());
            if (workbook.rows().isEmpty()) {
                throw new IllegalArgumentException("first worksheet is empty");
            }
            int dataRows = workbook.rows().size() - 1;
            if (dataRows < arguments.minDataRows()) {
                throw new IllegalArgumentException(
                        "expected at least " + arguments.minDataRows()
                                + " data rows, found " + dataRows);
            }
            if (arguments.expectedHeader() != null
                    && !workbook.rows().get(0).equals(arguments.expectedHeader())) {
                throw new IllegalArgumentException(
                        "unexpected header: " + workbook.rows().get(0));
            }
            System.out.printf(
                    "sheet=%s dataRows=%d header=%s lastRow=%s%n",
                    workbook.sheetName(),
                    dataRows,
                    workbook.rows().get(0),
                    workbook.rows().get(workbook.rows().size() - 1));
        } catch (Exception exception) {
            System.err.println("invalid XLSX: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static WorkbookData read(Path path) throws Exception {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!names.add(name)) {
                    throw new IllegalArgumentException("duplicate ZIP entry: " + name);
                }
                if (name.startsWith("/") || Arrays.asList(name.split("/")).contains("..")) {
                    throw new IllegalArgumentException("unsafe ZIP path: " + name);
                }
            }
            requireEntry(archive, "xl/workbook.xml");
            requireEntry(archive, "xl/_rels/workbook.xml.rels");

            Document workbook = parse(read(archive, "xl/workbook.xml"), "workbook");
            NodeList sheetNodes = workbook.getElementsByTagNameNS("*", "sheet");
            if (sheetNodes.getLength() == 0) {
                throw new IllegalArgumentException("workbook has no worksheets");
            }
            Element firstSheet = (Element) sheetNodes.item(0);
            String sheetName = firstSheet.getAttribute("name");
            String relationshipId = firstSheet.getAttributeNS(OFFICE_REL_NS, "id");
            if (relationshipId.isBlank()) {
                throw new IllegalArgumentException(
                        "first worksheet has no relationship id");
            }

            Document relationships = parse(
                    read(archive, "xl/_rels/workbook.xml.rels"),
                    "workbook relationships");
            Map<String, String> targets = new HashMap<>();
            NodeList relationshipNodes = relationships.getElementsByTagNameNS(
                    "*", "Relationship");
            for (int index = 0; index < relationshipNodes.getLength(); index++) {
                Element relationship = (Element) relationshipNodes.item(index);
                if (relationship.getAttribute("Type").endsWith("/worksheet")) {
                    targets.put(
                            relationship.getAttribute("Id"),
                            relationship.getAttribute("Target"));
                }
            }
            String target = targets.get(relationshipId);
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException(
                        "first worksheet relationship cannot be resolved");
            }
            String sheetPath = Path.of("xl").resolve(target).normalize()
                    .toString().replace('\\', '/');
            requireEntry(archive, sheetPath);

            List<String> sharedStrings = archive.getEntry("xl/sharedStrings.xml") == null
                    ? List.of()
                    : sharedStrings(read(archive, "xl/sharedStrings.xml"));
            List<List<String>> rows = rows(read(archive, sheetPath), sharedStrings);
            return new WorkbookData(sheetName, rows);
        }
    }

    private static List<String> sharedStrings(byte[] xml) throws Exception {
        Document document = parse(xml, "shared strings");
        NodeList items = document.getElementsByTagNameNS("*", "si");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) {
            values.add(descendantText((Element) items.item(index), "t"));
        }
        return List.copyOf(values);
    }

    private static List<List<String>> rows(
            byte[] xml, List<String> sharedStrings) throws Exception {
        Document document = parse(xml, "first worksheet");
        NodeList rowNodes = document.getElementsByTagNameNS("*", "row");
        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
            Element row = (Element) rowNodes.item(rowIndex);
            List<String> values = new ArrayList<>();
            NodeList children = row.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node child = children.item(index);
                if (!(child instanceof Element cell)
                        || !"c".equals(cell.getLocalName())) {
                    continue;
                }
                int column = columnIndex(cell.getAttribute("r"));
                while (values.size() <= column) {
                    values.add("");
                }
                String type = cell.getAttribute("t");
                String value = "inlineStr".equals(type)
                        ? descendantText(cell, "t")
                        : descendantText(cell, "v");
                if ("s".equals(type)) {
                    int sharedIndex = Integer.parseInt(value);
                    if (sharedIndex < 0 || sharedIndex >= sharedStrings.size()) {
                        throw new IllegalArgumentException(
                                "invalid shared string index");
                    }
                    value = sharedStrings.get(sharedIndex);
                } else if ("b".equals(type)) {
                    value = "1".equals(value) ? "TRUE" : "FALSE";
                }
                values.set(column, value);
            }
            rows.add(List.copyOf(values));
        }
        return List.copyOf(rows);
    }

    private static Document parse(byte[] xml, String label) throws Exception {
        String prefix = new String(
                xml, 0, Math.min(xml.length, 512), java.nio.charset.StandardCharsets.UTF_8)
                .toUpperCase();
        if (prefix.contains("<!DOCTYPE") || prefix.contains("<!ENTITY")) {
            throw new IllegalArgumentException("unsafe XML in " + label);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String descendantText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.append(nodes.item(index).getTextContent());
        }
        return result.toString();
    }

    private static int columnIndex(String reference) {
        int value = 0;
        int letters = 0;
        while (letters < reference.length()
                && Character.isLetter(reference.charAt(letters))) {
            value = value * 26
                    + Character.toUpperCase(reference.charAt(letters)) - 'A' + 1;
            letters++;
        }
        if (letters == 0) {
            throw new IllegalArgumentException(
                    "invalid cell reference: " + reference);
        }
        return value - 1;
    }

    private static byte[] read(ZipFile archive, String name) throws Exception {
        return archive.getInputStream(requireEntry(archive, name)).readAllBytes();
    }

    private static ZipEntry requireEntry(ZipFile archive, String name) {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IllegalArgumentException("missing XLSX part: " + name);
        }
        return entry;
    }

    private record WorkbookData(String sheetName, List<List<String>> rows) {}

    private record Arguments(
            Path workbook, int minDataRows, List<String> expectedHeader) {

        private static Arguments parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException("workbook path is required");
            }
            Path workbook = Path.of(args[0]);
            int minDataRows = 0;
            List<String> expectedHeader = null;
            for (int index = 1; index < args.length; index++) {
                switch (args[index]) {
                    case "--min-data-rows" ->
                            minDataRows = Integer.parseInt(args[++index]);
                    case "--expect-header" ->
                            expectedHeader = List.of(args[++index].split(",", -1));
                    default -> throw new IllegalArgumentException(
                            "unknown argument: " + args[index]);
                }
            }
            return new Arguments(workbook, minDataRows, expectedHeader);
        }
    }
}
