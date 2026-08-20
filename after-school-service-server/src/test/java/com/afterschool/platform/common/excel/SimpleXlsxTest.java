package com.afterschool.platform.common.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
}
