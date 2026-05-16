package com.stss.online_testing.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class QuestionExcelDTO {
    @ExcelProperty(value = "课程ID", index = 0)
    private Long courseId;
    
    @ExcelProperty(value = "题型(1单选/2是非)", index = 1)
    private Integer type;
    
    @ExcelProperty(value = "题干", index = 2)
    private String stem;
    
    @ExcelProperty(value = "选项(JSON数组格式)", index = 3)
    private String optionsStr; // Excel里只能填平铺的字符串，如: ["A. 1", "B. 2"]
    
    @ExcelProperty(value = "标准答案", index = 4)
    private String answer;
    
    @ExcelProperty(value = "难度(1-3)", index = 5)
    private Integer difficulty;

    @ExcelProperty(value = "知识点(JSON数组格式)", index = 6)
    private String knowledgePointsStr;
}
