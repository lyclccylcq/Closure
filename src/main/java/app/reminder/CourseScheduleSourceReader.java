package app.reminder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

final class CourseScheduleSourceReader {
    private static final Charset[] TEXT_CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("Big5"),
            Charset.forName("windows-1252")
    };
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private CourseScheduleSourceReader() {
    }

    static List<String> readLines(Path path) throws IOException {
        if (isXlsxWorkbook(path)) {
            return readXlsxLines(path);
        }
        if (isLegacyExcelWorkbook(path)) {
            throw new IllegalArgumentException("当前还不能直接读取 .xls，请先另存为 .xlsx、CSV 或 ICS。");
        }

        return readTextLines(path);
    }

    static List<String> readTextLines(Path path) throws IOException {
        IOException lastException = null;
        for (Charset charset : TEXT_CHARSETS) {
            try {
                return Files.readAllLines(path, charset);
            } catch (MalformedInputException exception) {
                lastException = exception;
            }
        }

        if (lastException != null) {
            throw lastException;
        }

        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    static boolean isXlsxWorkbook(Path path) {
        return fileName(path).endsWith(".xlsx");
    }

    private static boolean isLegacyExcelWorkbook(Path path) {
        return fileName(path).endsWith(".xls");
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> readXlsxLines(Path path) throws IOException {
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            List<String> sharedStrings = readSharedStrings(zipFile);
            List<CellKind> styles = readStyles(zipFile);
            List<? extends ZipEntry> sheetEntries = zipFile.stream()
                    .filter(entry -> entry.getName().matches("xl/worksheets/sheet\\d+\\.xml"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            List<String> lines = new ArrayList<>();
            for (ZipEntry sheetEntry : sheetEntries) {
                lines.addAll(readSheetLines(zipFile, sheetEntry, sharedStrings, styles));
            }

            if (lines.isEmpty()) {
                throw new IllegalArgumentException("没有从 .xlsx 里读取到课程表内容。");
            }

            return lines;
        } catch (ZipException exception) {
            throw new IllegalArgumentException("这个 .xlsx 文件无法读取，请确认文件没有损坏，或先导出为 CSV/ICS。", exception);
        }
    }

    private static List<String> readSharedStrings(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }

        Document document = readXml(zipFile, entry);
        NodeList stringItems = document.getElementsByTagName("si");
        List<String> sharedStrings = new ArrayList<>();
        for (int index = 0; index < stringItems.getLength(); index++) {
            sharedStrings.add(normalizeCellText(stringItems.item(index).getTextContent()));
        }

        return sharedStrings;
    }

    private static List<CellKind> readStyles(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry("xl/styles.xml");
        if (entry == null) {
            return List.of();
        }

        Document document = readXml(zipFile, entry);
        Map<Integer, String> customFormats = new HashMap<>();
        NodeList numberFormats = document.getElementsByTagName("numFmt");
        for (int index = 0; index < numberFormats.getLength(); index++) {
            Element numberFormat = (Element) numberFormats.item(index);
            Integer id = parseInteger(numberFormat.getAttribute("numFmtId"));
            if (id != null) {
                customFormats.put(id, numberFormat.getAttribute("formatCode"));
            }
        }

        NodeList cellStyleContainers = document.getElementsByTagName("cellXfs");
        if (cellStyleContainers.getLength() == 0) {
            return List.of();
        }

        NodeList styleNodes = cellStyleContainers.item(0).getChildNodes();
        List<CellKind> styles = new ArrayList<>();
        for (int index = 0; index < styleNodes.getLength(); index++) {
            Node node = styleNodes.item(index);
            if (!(node instanceof Element styleElement) || !"xf".equals(styleElement.getTagName())) {
                continue;
            }

            Integer numberFormatId = parseInteger(styleElement.getAttribute("numFmtId"));
            styles.add(cellKindFor(numberFormatId, customFormats.get(numberFormatId)));
        }

        return styles;
    }

    private static List<String> readSheetLines(
            ZipFile zipFile,
            ZipEntry sheetEntry,
            List<String> sharedStrings,
            List<CellKind> styles
    ) throws IOException {
        Document document = readXml(zipFile, sheetEntry);
        NodeList rowNodes = document.getElementsByTagName("row");
        List<String> lines = new ArrayList<>();

        for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
            Element row = (Element) rowNodes.item(rowIndex);
            Map<Integer, String> valuesByColumn = new HashMap<>();
            int maxColumn = -1;
            NodeList cellNodes = row.getElementsByTagName("c");
            for (int cellIndex = 0; cellIndex < cellNodes.getLength(); cellIndex++) {
                Element cell = (Element) cellNodes.item(cellIndex);
                int columnIndex = columnIndex(cell.getAttribute("r"));
                if (columnIndex < 0) {
                    columnIndex = cellIndex;
                }

                String value = readCellValue(cell, sharedStrings, styles);
                if (!value.isBlank()) {
                    valuesByColumn.put(columnIndex, value);
                    maxColumn = Math.max(maxColumn, columnIndex);
                }
            }

            if (maxColumn < 0) {
                continue;
            }

            List<String> rowValues = new ArrayList<>();
            for (int columnIndex = 0; columnIndex <= maxColumn; columnIndex++) {
                rowValues.add(valuesByColumn.getOrDefault(columnIndex, ""));
            }
            lines.add(String.join("\t", rowValues));
        }

        return lines;
    }

    private static String readCellValue(Element cell, List<String> sharedStrings, List<CellKind> styles) {
        String type = cell.getAttribute("t");
        String value = firstChildText(cell, "v");
        if ("s".equals(type)) {
            Integer sharedStringIndex = parseInteger(value);
            if (sharedStringIndex != null && sharedStringIndex >= 0 && sharedStringIndex < sharedStrings.size()) {
                return sharedStrings.get(sharedStringIndex);
            }
            return "";
        }
        if ("inlineStr".equals(type)) {
            return normalizeCellText(cell.getTextContent());
        }
        if ("str".equals(type)) {
            return normalizeCellText(value);
        }
        if ("b".equals(type)) {
            return "1".equals(value) ? "TRUE" : "FALSE";
        }
        if (value.isBlank()) {
            return "";
        }

        CellKind cellKind = styleKind(cell, styles);
        if (cellKind != CellKind.GENERAL) {
            return formatExcelSerialDate(value, cellKind);
        }

        return normalizeCellText(value);
    }

    private static CellKind styleKind(Element cell, List<CellKind> styles) {
        Integer styleIndex = parseInteger(cell.getAttribute("s"));
        if (styleIndex == null || styleIndex < 0 || styleIndex >= styles.size()) {
            return CellKind.GENERAL;
        }

        return styles.get(styleIndex);
    }

    private static String formatExcelSerialDate(String rawValue, CellKind cellKind) {
        try {
            double serial = Double.parseDouble(rawValue);
            int wholeDays = (int) Math.floor(serial);
            long seconds = Math.round((serial - wholeDays) * 86_400);
            LocalDate date = EXCEL_EPOCH.plusDays(wholeDays);
            if (seconds >= 86_400) {
                date = date.plusDays(1);
                seconds -= 86_400;
            }
            LocalTime time = LocalTime.MIDNIGHT.plusSeconds(seconds);

            return switch (cellKind) {
                case DATE -> date.format(DATE_FORMATTER);
                case TIME -> time.format(TIME_FORMATTER);
                case DATE_TIME -> LocalDateTime.of(date, time).format(DATE_TIME_FORMATTER);
                case GENERAL -> rawValue;
            };
        } catch (NumberFormatException exception) {
            return normalizeCellText(rawValue);
        }
    }

    private static CellKind cellKindFor(Integer numberFormatId, String customFormatCode) {
        if (numberFormatId != null) {
            if (numberFormatId == 14 || numberFormatId == 15 || numberFormatId == 16 || numberFormatId == 17) {
                return CellKind.DATE;
            }
            if (numberFormatId == 18 || numberFormatId == 19 || numberFormatId == 20
                    || numberFormatId == 21 || numberFormatId == 45 || numberFormatId == 46 || numberFormatId == 47) {
                return CellKind.TIME;
            }
            if (numberFormatId == 22) {
                return CellKind.DATE_TIME;
            }
        }

        if (customFormatCode == null || customFormatCode.isBlank()) {
            return CellKind.GENERAL;
        }

        String normalizedFormat = customFormatCode
                .replaceAll("\"[^\"]*\"", "")
                .replace("\\", "")
                .toLowerCase(Locale.ROOT);
        boolean hasDate = normalizedFormat.contains("y") || normalizedFormat.contains("d");
        boolean hasTime = normalizedFormat.contains("h") || normalizedFormat.contains("s");
        if (hasDate && hasTime) {
            return CellKind.DATE_TIME;
        }
        if (hasDate) {
            return CellKind.DATE;
        }
        if (hasTime) {
            return CellKind.TIME;
        }

        return CellKind.GENERAL;
    }

    private static Document readXml(ZipFile zipFile, ZipEntry entry) throws IOException {
        try (InputStream inputStream = zipFile.getInputStream(entry)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (ParserConfigurationException ignored) {
                // Some XML providers do not support the hardening feature; xlsx files are local input.
            }
            return factory.newDocumentBuilder().parse(inputStream);
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("读取 .xlsx 内容失败：" + entry.getName(), exception);
        }
    }

    private static String firstChildText(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }

        return normalizeCellText(nodes.item(0).getTextContent());
    }

    private static String normalizeCellText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static Integer parseInteger(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int columnIndex(String cellReference) {
        if (cellReference == null || cellReference.isBlank()) {
            return -1;
        }

        int value = 0;
        for (int index = 0; index < cellReference.length(); index++) {
            char character = cellReference.charAt(index);
            if (!Character.isLetter(character)) {
                break;
            }

            value = value * 26 + (Character.toUpperCase(character) - 'A' + 1);
        }

        return value == 0 ? -1 : value - 1;
    }

    private enum CellKind {
        GENERAL,
        DATE,
        TIME,
        DATE_TIME
    }
}
