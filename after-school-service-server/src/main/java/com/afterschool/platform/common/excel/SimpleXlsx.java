package com.afterschool.platform.common.excel;

import com.afterschool.platform.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Small, dependency-free XLSX reader/writer for the platform's tabular import
 * and export contracts. It deliberately supports one worksheet and plain
 * scalar cells only; macros, formulas, external links and embedded objects are
 * not evaluated.
 */
public final class SimpleXlsx {

    private static final int MAX_ENTRIES = 50;
    private static final int MAX_UNCOMPRESSED_BYTES = 20 * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 2_000;
    private static final int MAX_EXPORT_ROWS = 1_048_575;
    private static final int MAX_COLUMNS = 40;

    private SimpleXlsx() {}

    public static byte[] write(
            String requestedSheetName,
            List<String> headers,
            List<? extends List<?>> rows) {
        if (headers == null || headers.isEmpty() || headers.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("XLSX headers must contain 1 to 40 columns");
        }
        if (rows == null || rows.size() > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException(
                    "XLSX data rows cannot exceed the worksheet limit");
        }
        String sheetName = sanitizeSheetName(requestedSheetName);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRelationships());
            put(zip, "docProps/app.xml", appProperties(sheetName));
            put(zip, "docProps/core.xml", coreProperties());
            put(zip, "xl/workbook.xml", workbook(sheetName));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
            put(zip, "xl/styles.xml", styles());
            put(zip, "xl/worksheets/sheet1.xml", worksheet(headers, rows));
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("XLSX generation failed", exception);
        }
    }

    public static List<List<String>> readFirstSheet(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UNCOMPRESSED_BYTES) {
            throw invalid("Excel 文件为空或超过允许大小");
        }
        Map<String, byte[]> entries = unzip(bytes);
        byte[] sheet = entries.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("xl/worksheets/")
                        && entry.getKey().endsWith(".xml"))
                .min(Comparator.comparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .orElseThrow(() -> invalid("Excel 文件不包含工作表"));
        List<String> sharedStrings = entries.containsKey("xl/sharedStrings.xml")
                ? parseSharedStrings(entries.get("xl/sharedStrings.xml"))
                : List.of();
        return parseSheet(sheet, sharedStrings);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> entries = new HashMap<>();
        int entryCount = 0;
        int totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                String name = entry.getName();
                if (entryCount > MAX_ENTRIES
                        || name.startsWith("/")
                        || name.contains("..")
                        || entry.isDirectory()) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    throw invalid("Excel 压缩结构不安全");
                }
                ByteArrayOutputStream value = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    totalBytes += read;
                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw invalid("Excel 解压内容超过允许大小");
                    }
                    value.write(buffer, 0, read);
                }
                entries.put(name, value.toByteArray());
            }
        } catch (IOException exception) {
            throw invalid("Excel 文件无法读取");
        }
        return entries;
    }

    private static List<String> parseSharedStrings(byte[] xml) {
        Document document = parseXml(xml);
        NodeList items = document.getElementsByTagNameNS("*", "si");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) {
            values.add(descendantText(items.item(index), "t"));
        }
        return values;
    }

    private static List<List<String>> parseSheet(
            byte[] xml, List<String> sharedStrings) {
        Document document = parseXml(xml);
        NodeList rowNodes = document.getElementsByTagNameNS("*", "row");
        if (rowNodes.getLength() > MAX_IMPORT_ROWS + 1) {
            throw invalid("Excel 数据行不能超过 2000 行");
        }
        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
            Element row = (Element) rowNodes.item(rowIndex);
            List<String> values = new ArrayList<>();
            NodeList children = row.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node node = children.item(index);
                if (!(node instanceof Element cell)
                        || !"c".equals(cell.getLocalName())) {
                    continue;
                }
                int column = columnIndex(cell.getAttribute("r"));
                if (column < 0 || column >= MAX_COLUMNS) {
                    throw invalid("Excel 列数不能超过 40 列");
                }
                while (values.size() <= column) {
                    values.add("");
                }
                values.set(column, cellValue(cell, sharedStrings));
            }
            rows.add(List.copyOf(values));
        }
        return List.copyOf(rows);
    }

    private static String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            return descendantText(cell, "t");
        }
        String value = descendantText(cell, "v");
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(value);
                if (index < 0 || index >= sharedStrings.size()) {
                    throw invalid("Excel 共享字符串索引无效");
                }
                return sharedStrings.get(index);
            } catch (NumberFormatException exception) {
                throw invalid("Excel 共享字符串索引无效");
            }
        }
        if ("b".equals(type)) {
            return "1".equals(value) ? "TRUE" : "FALSE";
        }
        return value;
    }

    private static Document parseXml(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception exception) {
            throw invalid("Excel XML 结构无效");
        }
    }

    private static String descendantText(Node parent, String localName) {
        StringBuilder value = new StringBuilder();
        NodeList children = ((Element) parent).getElementsByTagNameNS("*", localName);
        for (int index = 0; index < children.getLength(); index++) {
            value.append(children.item(index).getTextContent());
        }
        return value.toString();
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
        return letters == 0 ? -1 : value - 1;
    }

    private static String worksheet(
            List<String> headers, List<? extends List<?>> rows) {
        List<List<?>> allRows = new ArrayList<>();
        allRows.add(new ArrayList<>(headers));
        allRows.addAll(rows);
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews>")
                .append("<cols>");
        for (int column = 0; column < headers.size(); column++) {
            int width = headers.get(column).length() + 4;
            for (List<?> row : rows) {
                if (column < row.size() && row.get(column) != null) {
                    width = Math.max(width, row.get(column).toString().length() + 2);
                }
            }
            width = Math.max(10, Math.min(40, width));
            xml.append("<col min=\"").append(column + 1)
                    .append("\" max=\"").append(column + 1)
                    .append("\" width=\"").append(width)
                    .append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData>");
        for (int rowIndex = 0; rowIndex < allRows.size(); rowIndex++) {
            List<?> row = allRows.get(rowIndex);
            xml.append("<row r=\"").append(rowIndex + 1).append("\">");
            for (int column = 0; column < headers.size(); column++) {
                Object raw = column < row.size() ? row.get(column) : null;
                String reference = columnName(column) + (rowIndex + 1);
                if (raw instanceof Number number) {
                    xml.append("<c r=\"").append(reference).append("\"")
                            .append(rowIndex == 0 ? " s=\"1\"" : "")
                            .append("><v>").append(number).append("</v></c>");
                } else if (raw instanceof Boolean bool) {
                    xml.append("<c r=\"").append(reference)
                            .append("\" t=\"b\"")
                            .append(rowIndex == 0 ? " s=\"1\"" : "")
                            .append("><v>").append(bool ? 1 : 0).append("</v></c>");
                } else {
                    String value = raw == null ? "" : safeSpreadsheetText(raw.toString());
                    xml.append("<c r=\"").append(reference)
                            .append("\" t=\"inlineStr\"")
                            .append(rowIndex == 0 ? " s=\"1\"" : "")
                            .append("><is><t xml:space=\"preserve\">")
                            .append(escape(value))
                            .append("</t></is></c>");
                }
            }
            xml.append("</row>");
        }
        xml.append("</sheetData><autoFilter ref=\"A1:")
                .append(columnName(headers.size() - 1)).append(allRows.size())
                .append("\"/></worksheet>");
        return xml.toString();
    }

    private static String safeSpreadsheetText(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0
                ? "'" + value
                : value;
    }

    private static String columnName(int column) {
        StringBuilder result = new StringBuilder();
        int value = column + 1;
        while (value > 0) {
            value--;
            result.append((char) ('A' + value % 26));
            value /= 26;
        }
        return result.reverse().toString();
    }

    private static void put(ZipOutputStream zip, String name, String value)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sanitizeSheetName(String value) {
        String normalized = value == null || value.isBlank()
                ? "数据"
                : value.replaceAll("[\\\\/*?:\\[\\]]", "_").strip();
        return normalized.substring(0, Math.min(31, normalized.length()));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_XLSX", message);
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                </Types>
                """;
    }

    private static String rootRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                </Relationships>
                """;
    }

    private static String workbookRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """;
    }

    private static String workbook(String sheetName) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """.formatted(escape(sheetName));
    }

    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="2"><font><sz val="11"/><name val="Aptos"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Aptos"/></font></fonts>
                  <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill></fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>
                  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                </styleSheet>
                """;
    }

    private static String appProperties(String sheetName) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
                  <Application>After-school Service Platform</Application>
                  <TitlesOfParts><vt:vector size="1" baseType="lpstr"><vt:lpstr>%s</vt:lpstr></vt:vector></TitlesOfParts>
                </Properties>
                """.formatted(escape(sheetName));
    }

    private static String coreProperties() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <dc:creator>中小学课后服务平台</dc:creator><cp:lastModifiedBy>中小学课后服务平台</cp:lastModifiedBy>
                </cp:coreProperties>
                """;
    }
}
