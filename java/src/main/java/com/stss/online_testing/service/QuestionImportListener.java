package com.stss.online_testing.service;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.dto.QuestionExcelDTO;
import com.stss.online_testing.entity.Question;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class QuestionImportListener implements ReadListener<QuestionExcelDTO> {

    private final IQuestionService questionService;
    private final Long creatorId;
    // 积攒多少条数据写入一次数据库（批处理）
    private static final int BATCH_COUNT = 100;
    private List<Question> cachedDataList = new ArrayList<>(BATCH_COUNT);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuestionImportListener(IQuestionService questionService, Long creatorId) {
        this.questionService = questionService;
        this.creatorId = creatorId;
    }

    @Override
    public void invoke(QuestionExcelDTO data, AnalysisContext context) {
        Question q = new Question();
        q.setCourseId(data.getCourseId());
        q.setType(data.getType());
        q.setStem(data.getStem());
        q.setAnswer(data.getAnswer());
        q.setDifficulty(data.getDifficulty());
        q.setCreatorId(creatorId);

        // 解析 optionsStr (JSON -> List)
        if (data.getOptionsStr() != null && !data.getOptionsStr().trim().isEmpty()) {
            try {
                List<String> options = objectMapper.readValue(
                        normalizeJsonArrayText(data.getOptionsStr()),
                        new TypeReference<List<String>>(){});
                q.setOptions(options);
            } catch (Exception e) {
                throw ApiBusinessException.unprocessable(
                        "选项格式解析失败，请确保使用标准的 JSON 数组格式，如: [\"A\", \"B\"]");
            }
        }

        if (data.getKnowledgePointsStr() != null && !data.getKnowledgePointsStr().trim().isEmpty()) {
            try {
                List<String> knowledgePoints = objectMapper.readValue(
                        normalizeJsonArrayText(data.getKnowledgePointsStr()),
                        new TypeReference<List<String>>() {});
                q.setKnowledgePoints(knowledgePoints);
            } catch (Exception e) {
                throw ApiBusinessException.unprocessable(
                        "知识点格式解析失败，请确保使用标准的 JSON 数组格式，如: [\"基础\", \"函数\"]");
            }
        }

        cachedDataList.add(q);
        // 达到批处理数量，执行入库操作
        if (cachedDataList.size() >= BATCH_COUNT) {
            saveData();
            cachedDataList.clear(); // 清理缓存
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 确保最后不足 BATCH_COUNT 的数据也能入库
        if (!cachedDataList.isEmpty()) {
            saveData();
        }
    }

    private void saveData() {
        questionService.saveBatch(cachedDataList);
    }

    private String normalizeJsonArrayText(String text) {
        if (text == null) {
            return null;
        }
        return text.trim()
                .replace('“', '"')
                .replace('”', '"')
                .replace('‘', '"')
                .replace('’', '"');
    }
}
