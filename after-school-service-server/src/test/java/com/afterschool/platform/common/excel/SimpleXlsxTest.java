package com.afterschool.platform.common.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class SimpleXlsxTest {

    @Test
    void writesAndReadsPlainWorkbookWithoutEvaluatingFormulas() {
        byte[] workbook = SimpleXlsx.write(
                "课程数据",
                List.of("课程编码", "课程名称", "容量"),
                List.of(List.of("=CMD()", "创意美术", 30)));

        List<List<String>> rows = SimpleXlsx.readFirstSheet(workbook);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("课程编码", "课程名称", "容量");
        assertThat(rows.get(1)).containsExactly("'=CMD()", "创意美术", "30");
    }

    @Test
    void writesMoreThanTwoThousandDataRows() throws Exception {
        List<List<?>> data = new ArrayList<>();
        for (int index = 1; index <= 2_001; index++) {
            data.add(List.of("S-" + index, index));
        }

        byte[] workbook = SimpleXlsx.write(
                "大批量成绩", List.of("学号", "成绩"), data);

        String sheetXml = null;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    sheetXml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        assertThat(sheetXml).isNotNull();
        assertThat(sheetXml.split("<row r=", -1)).hasSize(2_003);
        assertThat(sheetXml).contains("S-2001");
    }
}
