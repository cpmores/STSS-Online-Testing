package com.stss.online_testing.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;
import java.util.Date;

@Data
public class ExamScoreExcelDTO {
    @ExcelProperty("学生学号")
    @ColumnWidth(20)
    private Long studentId;

    @ExcelProperty("最终得分")
    private Integer totalScore;

    @ExcelProperty("交卷时间")
    @ColumnWidth(25)
    private Date submitTime;
}